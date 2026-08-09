package com.example.btaudiomonitor.ui.devices

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.btaudiomonitor.data.BluetoothRepository
import com.example.btaudiomonitor.data.BluetoothRepositoryImpl
import com.example.btaudiomonitor.data.DEFAULT_POLL_INTERVAL_MS
import com.example.btaudiomonitor.data.MAX_POLL_INTERVAL_MS
import com.example.btaudiomonitor.data.MIN_POLL_INTERVAL_MS
import com.example.btaudiomonitor.data.model.AudioRouteInfo
import com.example.btaudiomonitor.data.model.ConnectedDevice
import com.example.btaudiomonitor.data.tier2.Tier2Repository
import com.example.btaudiomonitor.data.tier2.Tier2RepositoryImpl
import com.example.btaudiomonitor.data.tier2.Tier2State
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BtAudioUiState(
    val hasBluetoothPermission: Boolean = false,
    val devices: List<ConnectedDevice> = emptyList(),
    val audioRoute: AudioRouteInfo? = null,
    val tier2: Tier2State = Tier2State.Unavailable,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    val lastUpdatedAtMillis: Long? = null,
)

/** The tier 1 half of the UI state, combined separately only because `combine` has no
 * six-flow overload. */
private data class Tier1Snapshot(
    val hasPermission: Boolean,
    val devices: List<ConnectedDevice>,
    val audioRoute: AudioRouteInfo?,
    val lastUpdatedAtMillis: Long?,
)

/**
 * [pollIntervalMs] drives the repository's actual re-query cadence for the device list
 * (see [BluetoothRepository.connectedDevices] — connection-state broadcasts aren't
 * reliably delivered on every OEM build, so polling is the source of truth). The audio
 * route stays purely event-driven (AudioDeviceCallback / AudioPlaybackCallback), since
 * that one has held up fine in testing. [BtAudioUiState.lastUpdatedAtMillis] reflects
 * the last time either stream actually emitted, not a synthetic tick.
 */
class DeviceListViewModel(
    private val repository: BluetoothRepository,
    private val tier2Repository: Tier2Repository,
) : ViewModel() {

    private val hasPermission = MutableStateFlow(false)
    private val devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    private val audioRoute = MutableStateFlow<AudioRouteInfo?>(null)
    private val tier2 = MutableStateFlow<Tier2State>(Tier2State.Unavailable)
    private val pollIntervalMs = MutableStateFlow(DEFAULT_POLL_INTERVAL_MS)
    private val lastUpdatedAtMillis = MutableStateFlow<Long?>(null)

    private val tier1 = combine(
        hasPermission,
        devices,
        audioRoute,
        lastUpdatedAtMillis,
        ::Tier1Snapshot,
    )

    val uiState: StateFlow<BtAudioUiState> = combine(
        tier1,
        tier2,
        pollIntervalMs,
    ) { snapshot, tier2State, interval ->
        BtAudioUiState(
            hasBluetoothPermission = snapshot.hasPermission,
            devices = snapshot.devices,
            audioRoute = snapshot.audioRoute,
            tier2 = tier2State,
            pollIntervalMs = interval,
            lastUpdatedAtMillis = snapshot.lastUpdatedAtMillis,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtAudioUiState())

    private var collectJob: Job? = null

    fun onPermissionResult(granted: Boolean) {
        hasPermission.value = granted
        if (granted) startCollecting() else stopCollecting()
    }

    fun setPollIntervalMs(intervalMs: Long) {
        pollIntervalMs.value = intervalMs.coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)
    }

    private fun startCollecting() {
        if (collectJob?.isActive == true) return
        collectJob = viewModelScope.launch {
            launch {
                repository.connectedDevices(pollIntervalMs).collect {
                    devices.value = it
                    lastUpdatedAtMillis.value = System.currentTimeMillis()
                }
            }
            launch {
                repository.activeAudioRoute(pollIntervalMs).collect {
                    audioRoute.value = it
                    lastUpdatedAtMillis.value = System.currentTimeMillis()
                }
            }
            // Strictly additive: this flow reports Unavailable rather than failing when
            // DUMP isn't granted, so tier 1 above is unaffected either way.
            launch {
                tier2Repository.codecStatus(pollIntervalMs).collect { tier2.value = it }
            }
        }
    }

    private fun stopCollecting() {
        collectJob?.cancel()
        collectJob = null
        devices.value = emptyList()
        audioRoute.value = null
        tier2.value = Tier2State.Unavailable
    }

    override fun onCleared() {
        super.onCleared()
        stopCollecting()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DeviceListViewModel(
                    repository = BluetoothRepositoryImpl(context.applicationContext),
                    tier2Repository = Tier2RepositoryImpl(),
                )
            }
        }
    }
}

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
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    val lastUpdatedAtMillis: Long? = null,
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
) : ViewModel() {

    private val hasPermission = MutableStateFlow(false)
    private val devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    private val audioRoute = MutableStateFlow<AudioRouteInfo?>(null)
    private val pollIntervalMs = MutableStateFlow(DEFAULT_POLL_INTERVAL_MS)
    private val lastUpdatedAtMillis = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<BtAudioUiState> = combine(
        hasPermission,
        devices,
        audioRoute,
        pollIntervalMs,
        lastUpdatedAtMillis,
    ) { permission, deviceList, route, interval, updatedAt ->
        BtAudioUiState(
            hasBluetoothPermission = permission,
            devices = deviceList,
            audioRoute = route,
            pollIntervalMs = interval,
            lastUpdatedAtMillis = updatedAt,
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
        }
    }

    private fun stopCollecting() {
        collectJob?.cancel()
        collectJob = null
        devices.value = emptyList()
        audioRoute.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopCollecting()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DeviceListViewModel(BluetoothRepositoryImpl(context.applicationContext))
            }
        }
    }
}

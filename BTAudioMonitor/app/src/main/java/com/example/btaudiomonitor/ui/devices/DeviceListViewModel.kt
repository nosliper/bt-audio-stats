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
import com.example.btaudiomonitor.data.tier2.BenchmarkState
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

/** One minute is long enough to average out ABR swings without being tedious. */
const val DEFAULT_BENCHMARK_MILLIS = 60_000L

data class BtAudioUiState(
    val hasBluetoothPermission: Boolean = false,
    val devices: List<ConnectedDevice> = emptyList(),
    val audioRoute: AudioRouteInfo? = null,
    val tier2: Tier2State = Tier2State.Unavailable,
    val benchmark: BenchmarkState = BenchmarkState.Idle,
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
 * Tier 1 polls; **tier 2 does not**. Reading `dumpsys` costs ~2.5 MB and 500-900 ms and
 * was confirmed to make Bluetooth audio stutter when done once a second, so tier 2 is
 * strictly on demand: one read when the screen opens, one when the user refreshes, and
 * two per benchmark run. See [Tier2Repository].
 */
class DeviceListViewModel(
    private val repository: BluetoothRepository,
    private val tier2Repository: Tier2Repository,
) : ViewModel() {

    private val hasPermission = MutableStateFlow(false)
    private val devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    private val audioRoute = MutableStateFlow<AudioRouteInfo?>(null)
    private val tier2 = MutableStateFlow<Tier2State>(Tier2State.Unavailable)
    private val benchmark = MutableStateFlow<BenchmarkState>(BenchmarkState.Idle)
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
        benchmark,
        pollIntervalMs,
    ) { snapshot, tier2State, benchmarkState, interval ->
        BtAudioUiState(
            hasBluetoothPermission = snapshot.hasPermission,
            devices = snapshot.devices,
            audioRoute = snapshot.audioRoute,
            tier2 = tier2State,
            benchmark = benchmarkState,
            pollIntervalMs = interval,
            lastUpdatedAtMillis = snapshot.lastUpdatedAtMillis,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtAudioUiState())

    private var collectJob: Job? = null
    private var tier2Job: Job? = null

    fun onPermissionResult(granted: Boolean) {
        hasPermission.value = granted
        if (granted) startCollecting() else stopCollecting()
    }

    fun setPollIntervalMs(intervalMs: Long) {
        pollIntervalMs.value = intervalMs.coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)
    }

    /** One-shot tier 2 read. Costs a full dumpsys, so it is only ever user-initiated. */
    fun refreshTier2() {
        if (tier2Job?.isActive == true) return
        tier2Job = viewModelScope.launch { tier2.value = tier2Repository.read() }
    }

    fun startBenchmark(durationMillis: Long = DEFAULT_BENCHMARK_MILLIS) {
        if (benchmark.value is BenchmarkState.Running) return
        tier2Job?.cancel()
        tier2Job = viewModelScope.launch {
            tier2Repository.runBenchmark(durationMillis).collect { benchmark.value = it }
            // Refresh the snapshot afterwards so codec details reflect the end of the run.
            tier2.value = tier2Repository.read()
        }
    }

    fun cancelBenchmark() {
        tier2Job?.cancel()
        tier2Job = null
        benchmark.value = BenchmarkState.Idle
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
        // Deliberately a single read, not a subscription.
        refreshTier2()
    }

    private fun stopCollecting() {
        collectJob?.cancel()
        collectJob = null
        tier2Job?.cancel()
        tier2Job = null
        devices.value = emptyList()
        audioRoute.value = null
        tier2.value = Tier2State.Unavailable
        benchmark.value = BenchmarkState.Idle
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

package com.example.btaudiomonitor.data

import com.example.btaudiomonitor.data.model.AudioRouteInfo
import com.example.btaudiomonitor.data.model.ConnectedDevice
import kotlinx.coroutines.flow.Flow

/**
 * Everything the UI knows about Bluetooth audio comes through here — nothing above this
 * layer touches BluetoothAdapter, BluetoothProfile, or AudioManager directly. Tier 1
 * only for now; a Shizuku-backed tier 2 implementation can wrap or decorate this later
 * without the UI layer changing (see CLAUDE.md, "Architecture: two tiers").
 */
interface BluetoothRepository {

    /**
     * Devices currently connected on A2DP, HEADSET, LE_AUDIO, or HEARING_AID. Emits an
     * empty list (never throws) when BLUETOOTH_CONNECT is not granted or Bluetooth is
     * off.
     *
     * Re-queried every [pollIntervalMs] rather than driven off connection-state
     * broadcasts — those broadcasts are not reliably delivered on every OEM build (see
     * CLAUDE.md's dumpsys-parsing warning about OEM variance; the same caution applies
     * here). Polling is the only mechanism that reliably reflects reality.
     */
    fun connectedDevices(pollIntervalMs: Flow<Long>): Flow<List<ConnectedDevice>>

    /**
     * The current Bluetooth audio output route, or null when none is active. Updates
     * immediately on route/playback-state change events, and is also re-checked every
     * [pollIntervalMs] as a safety net: [android.media.AudioManager.isMusicActive] has
     * been observed to lag its own change callback (buffered/deep-buffer playback can
     * leave it stuck at true briefly after playback actually stops), so a pure
     * event-driven read can go stale with nothing left to correct it.
     */
    fun activeAudioRoute(pollIntervalMs: Flow<Long>): Flow<AudioRouteInfo?>
}

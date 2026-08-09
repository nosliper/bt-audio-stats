package com.example.btaudiomonitor.data.model

/**
 * The Bluetooth audio profile a device is currently connected under. A single physical
 * device can appear more than once here (e.g. a headset connected on both HEADSET and
 * A2DP) — that is intentional and surfaced as-is rather than collapsed.
 */
enum class AudioProfile {
    A2DP,
    HEADSET,
    LE_AUDIO,
    HEARING_AID,
}

enum class BondState {
    NONE,
    BONDING,
    BONDED,
    UNKNOWN,
}

/**
 * A Bluetooth audio device currently connected on [profile], built entirely from
 * unprivileged [android.bluetooth.BluetoothDevice] fields. No codec or battery data —
 * those require BLUETOOTH_PRIVILEGED, which this app cannot obtain. See CLAUDE.md.
 */
data class ConnectedDevice(
    val address: String,
    val name: String?,
    val profile: AudioProfile,
    val bondState: BondState,
)

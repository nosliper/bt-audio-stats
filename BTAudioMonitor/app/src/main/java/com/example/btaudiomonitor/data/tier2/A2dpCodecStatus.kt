package com.example.btaudiomonitor.data.tier2

/**
 * The real, negotiated A2DP codec state, parsed out of `dumpsys bluetooth_manager`.
 *
 * None of this is reachable in tier 1 — `BluetoothA2dp.getCodecStatus()` needs
 * BLUETOOTH_PRIVILEGED, and the platform redacts live formats from unprivileged apps.
 * Reading it requires shell-level privileges (tier 2 / Shizuku). Verified empirically:
 * changing the codec from LDAC 96 kHz/32-bit to AAC 44.1 kHz/16-bit produced *no*
 * observable change anywhere in tier 1, so this is the only way to know the truth.
 *
 * Every field is nullable on purpose. The dump format is undocumented, varies by OEM
 * and Android version, and carries per-codec fields that simply do not exist for other
 * codecs (`LDAC quality mode`, `AAC bitrate mode`). Absent means null; never guess.
 *
 * @param pcmReadBytes cumulative count of PCM bytes the encoder has read, from
 *   `PCM read bytes (expected/actual)`. This is a *monotonic counter*, not a rate —
 *   sampling it twice and dividing by elapsed time yields genuinely measured
 *   throughput. It counts uncompressed PCM into the encoder; [encoderBitrateBps] is
 *   the compressed side. Note the counter can reset when the codec renegotiates, so a
 *   consumer must treat a decrease as a reset rather than negative throughput.
 * @param isPlaying from `mIsPlaying` on the device's state machine — a per-device
 *   playback flag, and strictly better than tier 1's system-wide
 *   `AudioManager.isMusicActive()`, which is not attributable to one route and was
 *   observed latching true after playback stopped.
 */
data class A2dpCodecStatus(
    val codecName: String,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val channelMode: String?,
    val encoderBitrateBps: Int?,
    val ldacQualityMode: String?,
    val ldacBitrateKbps: Int?,
    val pcmReadBytes: Long?,
    val packetsExpected: Long?,
    val packetsDropped: Long?,
    val effectiveMtuBytes: Int?,
    val isPlaying: Boolean?,
)

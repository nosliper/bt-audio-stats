package com.example.btaudiomonitor.ui.format

import com.example.btaudiomonitor.data.model.AudioProfile
import com.example.btaudiomonitor.data.model.BondState
import java.util.Locale

fun formatThroughput(bytesPerSecond: Long?): String {
    if (bytesPerSecond == null) return "Not available"
    val kb = bytesPerSecond / 1024.0
    return if (kb < 1024) {
        String.format(Locale.US, "%.1f KB/s", kb)
    } else {
        String.format(Locale.US, "%.2f MB/s", kb / 1024.0)
    }
}

fun formatSampleRate(hz: Int?): String = hz?.let { "%,d Hz".format(Locale.US, it) } ?: "Unknown"

fun formatBitDepth(bits: Int?): String = bits?.let { "$it-bit" } ?: "Unknown"

/** Over-the-air bitrate. Codecs report this in kbps (LDAC) or bps (AAC encoder). */
fun formatBitrateKbps(kbps: Int?): String =
    kbps?.let { String.format(Locale.US, "%,d kbps", it) } ?: "Unknown"

fun formatBitrateBps(bps: Int?): String =
    bps?.takeIf { it > 0 }?.let { String.format(Locale.US, "%,d kbps", it / 1000) } ?: "Unknown"

fun formatBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb < 1024) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}

fun formatDuration(millis: Long): String =
    String.format(Locale.US, "%.1f s", millis / 1000.0)

/** LDAC's ABR mode moves during a run, so show the endpoints rather than one figure. */
fun formatLdacRange(startKbps: Int?, endKbps: Int?): String? = when {
    startKbps == null && endKbps == null -> null
    startKbps == null -> formatBitrateKbps(endKbps)
    endKbps == null -> formatBitrateKbps(startKbps)
    startKbps == endKbps -> formatBitrateKbps(startKbps)
    else -> "${formatBitrateKbps(startKbps)} → ${formatBitrateKbps(endKbps)}"
}

fun formatPacketLoss(expected: Long?, dropped: Long?): String {
    if (expected == null || dropped == null) return "Unknown"
    if (expected <= 0) return "$dropped dropped"
    val percent = dropped.toDouble() / expected * 100
    return String.format(Locale.US, "%,d dropped (%.2f%%)", dropped, percent)
}

fun formatChannelCount(count: Int?): String = when (count) {
    null -> "Unknown"
    1 -> "Mono"
    2 -> "Stereo"
    else -> "$count channels"
}

fun AudioProfile.label(): String = when (this) {
    AudioProfile.A2DP -> "A2DP"
    AudioProfile.HEADSET -> "Headset (HFP)"
    AudioProfile.LE_AUDIO -> "LE Audio"
    AudioProfile.HEARING_AID -> "Hearing Aid"
}

fun BondState.label(): String = when (this) {
    BondState.NONE -> "Not paired"
    BondState.BONDING -> "Pairing…"
    BondState.BONDED -> "Paired"
    BondState.UNKNOWN -> "Unknown"
}

/** Seconds elapsed since [sinceMillis], as a short "Ns ago" / "just now" string. */
fun formatElapsedSince(sinceMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String {
    if (sinceMillis == null) return "—"
    val elapsedSeconds = (nowMillis - sinceMillis) / 1000
    return if (elapsedSeconds <= 0) "just now" else "${elapsedSeconds}s ago"
}

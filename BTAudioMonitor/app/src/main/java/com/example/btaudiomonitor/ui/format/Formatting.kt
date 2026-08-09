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

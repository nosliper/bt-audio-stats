package com.example.btaudiomonitor.data

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import androidx.core.content.ContextCompat
import com.example.btaudiomonitor.data.model.AudioProfile
import com.example.btaudiomonitor.data.model.AudioRouteInfo
import com.example.btaudiomonitor.data.model.BondState
import com.example.btaudiomonitor.data.model.ConnectedDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tier 1 implementation: [BluetoothProfile.ServiceListener] to obtain profile proxies,
 * [AudioManager] + [AudioDeviceCallback] + [AudioManager.AudioPlaybackCallback] for the
 * active route. Everything here works with only BLUETOOTH_CONNECT / BLUETOOTH_SCAN
 * granted — no Shizuku, no privileged APIs. See CLAUDE.md, "Tier 1 — unprivileged".
 *
 * Both flows poll on top of their event callbacks rather than trusting events alone.
 * Two separate staleness bugs were observed on the dev device (Samsung/One UI, API 36)
 * that events alone could not recover from: connection-state broadcasts never arriving
 * for a real A2DP reconnect, and `isMusicActive` staying true after playback stopped.
 * See the individual `connectedDevices` / `activeAudioRoute` docs on
 * [BluetoothRepository] for details.
 */
class BluetoothRepositoryImpl(
    private val context: Context,
) : BluetoothRepository {

    private val monitoredProfiles = listOf(
        BluetoothProfile.A2DP to AudioProfile.A2DP,
        BluetoothProfile.HEADSET to AudioProfile.HEADSET,
        BluetoothProfile.LE_AUDIO to AudioProfile.LE_AUDIO,
        BluetoothProfile.HEARING_AID to AudioProfile.HEARING_AID,
    )

    override fun connectedDevices(pollIntervalMs: Flow<Long>): Flow<List<ConnectedDevice>> = callbackFlow {
        if (!context.hasBluetoothConnectPermission()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        // Keyed by BluetoothProfile int id (A2DP, HEADSET, LE_AUDIO, HEARING_AID).
        val proxies = mutableMapOf<Int, BluetoothProfile>()
        val devicesByProfile = mutableMapOf<Int, List<ConnectedDevice>>()

        fun emitMerged() = trySend(devicesByProfile.values.flatten())

        fun refreshAll() {
            proxies.forEach { (profileId, proxy) ->
                val type = monitoredProfiles.firstOrNull { it.first == profileId }?.second
                    ?: return@forEach
                devicesByProfile[profileId] = if (context.hasBluetoothConnectPermission()) {
                    try {
                        proxy.connectedDevices.map { it.toConnectedDevice(type) }
                    } catch (e: SecurityException) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        }

        val serviceListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                proxies[profileId] = proxy
                refreshAll()
                emitMerged()
            }

            override fun onServiceDisconnected(profileId: Int) {
                proxies.remove(profileId)
                devicesByProfile.remove(profileId)
                emitMerged()
            }
        }

        monitoredProfiles.forEach { (profileId, _) ->
            adapter.getProfileProxy(context, serviceListener, profileId)
        }

        // BluetoothA2dp/Headset/LeAudio/HearingAid ACTION_CONNECTION_STATE_CHANGED is
        // the "correct" way to hear about this, but it was verified undelivered on a
        // real, confirmed reconnect on a Samsung/One UI build (registered receiver,
        // toggled Bluetooth off/on, device came back per `dumpsys bluetooth_manager`,
        // zero broadcasts received). Polling doesn't depend on OEM broadcast behavior.
        val pollJob = launch {
            pollIntervalMs.collectLatest { interval ->
                while (isActive) {
                    refreshAll()
                    emitMerged()
                    delay(interval)
                }
            }
        }

        awaitClose {
            pollJob.cancel()
            proxies.forEach { (profileId, proxy) -> adapter.closeProfileProxy(profileId, proxy) }
        }
    }

    override fun activeAudioRoute(pollIntervalMs: Flow<Long>): Flow<AudioRouteInfo?> = callbackFlow {
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        fun emitCurrent() = trySend(audioManager.findBluetoothRoute())

        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                emitCurrent()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                emitCurrent()
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)

        // isMusicActive() is a snapshot, not an event stream — this callback fires
        // whenever *any* app starts/stops/changes playback anywhere on the device, so
        // we use it purely as a trigger to re-read isMusicActive(). That flag has also
        // been observed to lag the callback itself by a beat (deep-buffered playback
        // can leave it stuck at true briefly after playback actually stops) — the poll
        // loop below is what corrects that once the buffer drains.
        val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                emitCurrent()
            }
        }
        audioManager.registerAudioPlaybackCallback(playbackCallback, null)

        val pollJob = launch {
            pollIntervalMs.collectLatest { interval ->
                while (isActive) {
                    emitCurrent()
                    delay(interval)
                }
            }
        }

        awaitClose {
            pollJob.cancel()
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        }
    }
}

private fun Context.hasBluetoothConnectPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

private fun BluetoothDevice.toConnectedDevice(profile: AudioProfile): ConnectedDevice {
    val deviceName = try {
        name
    } catch (e: SecurityException) {
        null
    }
    val state = try {
        when (bondState) {
            BluetoothDevice.BOND_NONE -> BondState.NONE
            BluetoothDevice.BOND_BONDING -> BondState.BONDING
            BluetoothDevice.BOND_BONDED -> BondState.BONDED
            else -> BondState.UNKNOWN
        }
    } catch (e: SecurityException) {
        BondState.UNKNOWN
    }
    return ConnectedDevice(address = address, name = deviceName, profile = profile, bondState = state)
}

/**
 * Preference order for picking a route when we have to fall back to guessing. A phone
 * can expose several Bluetooth outputs for one physical headset — a WH-1000XM3
 * connected on both A2DP and HFP shows up as both `bt_a2dp` and `bt_sco_hs` — and SCO
 * is the narrowband voice channel, whose capabilities say nothing useful about music
 * playback. Media-capable routes therefore rank first and SCO last.
 */
private val BLUETOOTH_OUTPUT_TYPE_PREFERENCE = listOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST, // API 34+; safe as a constant on API 33 too.
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
)

private val BLUETOOTH_OUTPUT_TYPES = BLUETOOTH_OUTPUT_TYPE_PREFERENCE.toSet()

private val MEDIA_ATTRIBUTES = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

private fun AudioManager.findBluetoothRoute(): AudioRouteInfo? {
    // Ask the platform where media would actually go, rather than guessing from the
    // full output list. getAudioDevicesForAttributes is public API as of exactly our
    // minSdk (33). Defensive against OEM variance: fall back rather than propagate.
    val routedForMedia = try {
        getAudioDevicesForAttributes(MEDIA_ATTRIBUTES)
            .firstOrNull { it.type in BLUETOOTH_OUTPUT_TYPES }
    } catch (e: RuntimeException) {
        null
    }

    val device = routedForMedia
        ?: getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in BLUETOOTH_OUTPUT_TYPES }
            .minByOrNull { BLUETOOTH_OUTPUT_TYPE_PREFERENCE.indexOf(it.type) }

    return device?.toAudioRouteInfo(
        isAudioActive = isMusicActive,
        pipelineSampleRateHz = getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 },
    )
}

private fun AudioDeviceInfo.toAudioRouteInfo(
    isAudioActive: Boolean,
    pipelineSampleRateHz: Int?,
): AudioRouteInfo {
    val label = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Audio (headset)"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Audio (speaker)"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE Audio (broadcast)"
        else -> "Bluetooth"
    }

    // Highest rate the headset advertises support for. Purely a spec — the pipeline
    // does not necessarily (and here, does not) run at it.
    val maxSampleRate = sampleRates.filter { it > 0 }.maxOrNull()

    // AudioFormat.channelCountFromOutChannelMask() isn't public API; each CHANNEL_OUT_*
    // constant is a distinct bit, so a plain popcount gives the same answer.
    val channelCount = channelCounts.filter { it > 0 }.maxOrNull()
        ?: channelMasks.toList().mapNotNull { mask ->
            Integer.bitCount(mask).takeIf { it > 0 }
        }.maxOrNull()

    val bestPcmEncoding: Pair<Int, Int>? = encodings.toList()
        .mapNotNull { encoding -> encoding.pcmBytesPerSample()?.let { bytes -> encoding to bytes } }
        .maxByOrNull { it.second }
    val encodingLabel = (bestPcmEncoding?.first ?: encodings.firstOrNull())?.let(::describeEncoding)
        ?: "Unknown"

    // Estimate off the rate the HAL actually mixes to, not the headset's ceiling. Fall
    // back to the ceiling only if the platform property is unreadable, since a
    // too-high estimate beats none at all — but that fallback is the less honest path,
    // hence the preference order.
    val estimateSampleRate = pipelineSampleRateHz ?: maxSampleRate
    val estimate = bestPcmEncoding?.second?.let { bytesPerSample ->
        if (estimateSampleRate != null && channelCount != null && channelCount > 0) {
            estimateSampleRate.toLong() * channelCount * bytesPerSample
        } else {
            null
        }
    }

    val name = try {
        productName?.toString()?.takeIf { it.isNotBlank() }
    } catch (e: SecurityException) {
        null
    }

    return AudioRouteInfo(
        deviceTypeLabel = label,
        productName = name,
        maxSampleRateHz = maxSampleRate,
        pipelineSampleRateHz = pipelineSampleRateHz,
        channelCount = channelCount,
        encodingLabel = encodingLabel,
        estimatedBytesPerSecond = estimate,
        isAudioActive = isAudioActive,
    )
}

/** Bytes per sample for recognized PCM encodings; null for compressed/unknown ones. */
private fun Int.pcmBytesPerSample(): Int? = when (this) {
    AudioFormat.ENCODING_PCM_8BIT -> 1
    AudioFormat.ENCODING_PCM_16BIT -> 2
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
    AudioFormat.ENCODING_PCM_32BIT -> 4
    AudioFormat.ENCODING_PCM_FLOAT -> 4
    else -> null
}

private fun describeEncoding(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
    AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
    AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
    AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
    else -> "Compressed/unrecognized (0x${encoding.toString(16)})"
}

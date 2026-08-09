package com.example.btaudiomonitor.data.model

/**
 * A snapshot of the active Bluetooth audio output route.
 *
 * Two different sample rates are tracked here, and conflating them was a real bug worth
 * not repeating:
 *
 * - [maxSampleRateHz] comes from [android.media.AudioDeviceInfo.getSampleRates], which
 *   is an unordered list of rates the *headset supports* (e.g. [48000, 96000, 44100,
 *   88200] for a WH-1000XM3). It is a static capability, not a live reading.
 * - [pipelineSampleRateHz] comes from
 *   [android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE] — the platform's native
 *   output rate, which is what the HAL actually mixes to before handing PCM to the
 *   Bluetooth encoder. This is the best public proxy for what the pipeline is really
 *   running at, though it is a property of the phone's primary output rather than a
 *   guaranteed readout of the A2DP encoder's input.
 *
 * Neither varies with the audio being played. The *actual* per-stream format is
 * deliberately redacted by the platform for other apps' players — an unprivileged app
 * reads back `FormatInfo{channelMask=0x0, sampleRate=0}` — so there is no way to see
 * that a given track is 44.1 kHz rather than 48 kHz. See CLAUDE.md's hard constraints.
 *
 * [estimatedBytesPerSecond] is [pipelineSampleRateHz] x [channelCount] x bytes-per-sample:
 * the size of the raw PCM stream fed *into* the codec. It is not the over-the-air
 * bitrate — after LDAC/SBC/AAC compression the radio carries substantially less (LDAC
 * negotiates 330/660/990 kbps), and the negotiated codec is not readable without
 * BLUETOOTH_PRIVILEGED. It is null when the encoding isn't a recognized PCM format.
 * Present it as a constant-rate estimate, never as a measurement.
 *
 * [isAudioActive] reflects [android.media.AudioManager.isMusicActive] at the time of
 * this snapshot — true when something is playing system-wide, not specific to this
 * route (there's no public API to attribute playback to one output device).
 */
data class AudioRouteInfo(
    val deviceTypeLabel: String,
    val productName: String?,
    val maxSampleRateHz: Int?,
    val pipelineSampleRateHz: Int?,
    val channelCount: Int?,
    val encodingLabel: String,
    val estimatedBytesPerSecond: Long?,
    val isAudioActive: Boolean,
)

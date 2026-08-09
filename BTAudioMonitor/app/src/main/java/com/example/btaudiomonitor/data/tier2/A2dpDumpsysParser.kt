package com.example.btaudiomonitor.data.tier2

/**
 * Parses `dumpsys bluetooth_manager` output into an [A2dpCodecStatus].
 *
 * Pure Kotlin with no Android dependencies, so it is unit-testable on the JVM against
 * captured fixtures — which is the only sane way to work on this, since the format is
 * undocumented and OEM-specific. See CLAUDE.md: "Parse defensively: never assume field
 * order, never assume a field exists, always handle a total parse failure by falling
 * back to tier 1 values."
 *
 * Two traps this parser exists to avoid, both found in real output from a Galaxy S20 FE
 * (Android 13, One UI 5.1):
 *
 * 1. **Per-codec blocks are stale, not absent, for inactive codecs.** With AAC active,
 *    the LDAC block still advertised `Config: Rate=96000 Bits=32` — its last-used
 *    values — while reporting `LDAC transmission bitrate (Kbps): -1`. Naively grepping
 *    for a codec's block therefore yields plausible-looking nonsense. Only
 *    `mCodecConfig` identifies the active codec, so that is read first and the block is
 *    matched to it.
 * 2. **Negative values are invalid sentinels, not data.** `-1` shows up for bitrates and
 *    priorities that do not apply. They are normalized to null rather than reported.
 *
 * Also note the codec set is not fixed: this device exposes Samsung's `SSC` and
 * `SSCUHQ` alongside the AOSP codecs, and `aptX HD` contains a space. Nothing here
 * enumerates codec names.
 *
 * Verified against real output from two Android versions with materially different
 * surrounding structure (Android 13 / One UI 5.1 and Android 16 / One UI 8). The parts
 * this parser depends on — `mCodecConfig`, `mActiveDevice`, the state-machine headers
 * and the `A2DP <codec> State:` blocks — are identical on both.
 *
 * Multiple devices can be bonded simultaneously (`mMaxConnectedAudioDevices: 2`), each
 * contributing its own `mCodecConfig`; the Android 16 capture has two, on different
 * codecs. `mActiveDevice` selects the right one, so this does not rely on ordering.
 *
 * Remaining limitation: the `A2DP <codec> State:` statistics blocks are global and not
 * attributed to any device, so with two devices streaming at once their counters cannot
 * be told apart. Acceptable for a single-target-device app, but it is a real limit
 * rather than an oversight.
 */
object A2dpDumpsysParser {

    private val ACTIVE_CODEC_CONFIG = Regex("""mCodecConfig:\s*\{([^}]*)\}""")
    private val IS_PLAYING = Regex("""mIsPlaying:\s*(true|false)""")
    private val ACTIVE_DEVICE = Regex("""mActiveDevice:\s*(\S+)""")

    /** `=== A2dpStateMachine for AA:BB:.. ===`, which Android 16 suffixes with
     * `(Active)` before the closing `===`. */
    private val STATE_MACHINE_HEADER = Regex("""===\s*A2dpStateMachine for\s+(\S+)[^=\n]*===""")

    private val NO_DEVICE = setOf("null", "00:00:00:00:00:00")

    /** Matches a codec block header exactly — `A2DP Source State: Enabled` must not
     * match, hence the end-of-line anchor after the colon. */
    private val CODEC_BLOCK_HEADER = Regex("""^A2DP (.+?) State:[ \t]*$""", RegexOption.MULTILINE)

    /**
     * Returns the active codec's status, or null when the dump contains no usable A2DP
     * codec config at all (Bluetooth off, nothing connected, or a format we don't
     * recognize). Callers must fall back to tier 1 on null.
     */
    fun parse(dump: String): A2dpCodecStatus? {
        // Several devices can be bonded and hold state machines at once, each with its
        // own mCodecConfig. Scope to the one mActiveDevice names; only fall back to a
        // dump-wide search if that pairing can't be resolved.
        val deviceSection = activeDeviceSection(dump)

        val configFields = (deviceSection?.let(ACTIVE_CODEC_CONFIG::find) ?: ACTIVE_CODEC_CONFIG.find(dump))
            ?.groupValues?.get(1)
            ?.let(::parseBracedFields)
            ?: return null

        val codecName = configFields["codecName"]?.takeIf { it.isNotBlank() } ?: return null
        val block = findCodecBlock(dump, codecName)
        val packets = block?.pairField("""Packet counts \(expected/dropped\)""")

        return A2dpCodecStatus(
            codecName = codecName,
            sampleRateHz = configFields.parenValue("mSampleRate")?.singleIntOrNull(),
            bitsPerSample = configFields.parenValue("mBitsPerSample")?.singleIntOrNull(),
            channelMode = configFields.parenValue("mChannelMode")?.takeIf { it.isNotBlank() },
            encoderBitrateBps = block?.intField("Current encoder bitrate"),
            ldacQualityMode = block?.wordField("LDAC quality mode"),
            ldacBitrateKbps = block?.intField("""LDAC transmission bitrate \(Kbps\)"""),
            pcmReadBytes = block?.pairField("""PCM read bytes \(expected/actual\)""")?.second,
            packetsExpected = packets?.first,
            packetsDropped = packets?.second,
            effectiveMtuBytes = block?.intField("Effective MTU"),
            isPlaying = (deviceSection ?: dump).let(IS_PLAYING::find)
                ?.groupValues?.get(1)
                ?.toBooleanStrictOrNull(),
        )
    }

    /**
     * The slice of the dump describing the active device's A2DP state machine, or null
     * when there is no active device or its state machine can't be located.
     */
    private fun activeDeviceSection(dump: String): String? {
        val active = ACTIVE_DEVICE.find(dump)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it.lowercase() !in NO_DEVICE }
            ?: return null

        val headers = STATE_MACHINE_HEADER.findAll(dump).toList()
        val index = headers.indexOfFirst { it.groupValues[1].equals(active, ignoreCase = true) }
        if (index < 0) return null

        val start = headers[index].range.last + 1
        val end = headers.getOrNull(index + 1)?.range?.first ?: dump.length
        return dump.substring(start, end)
    }

    /** Splits `codecName:AAC,mSampleRate:0x1(44100),...` on commas that separate
     * top-level fields. Values may contain parens but not commas in practice. */
    private fun parseBracedFields(body: String): Map<String, String> =
        body.split(',')
            .mapNotNull { field ->
                val name = field.substringBefore(':', missingDelimiterValue = "").trim()
                val value = field.substringAfter(':', missingDelimiterValue = "").trim()
                if (name.isEmpty()) null else name to value
            }
            .toMap()

    /** `mSampleRate` reads `0x1(44100)`; we want what's in the parens. */
    private fun Map<String, String>.parenValue(key: String): String? =
        this[key]?.substringAfter('(', missingDelimiterValue = "")
            ?.substringBefore(')')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * A bitmask paren value can hold several alternatives (`44100|48000|88200|96000`).
     * For an *active* config the platform reports a single negotiated value, so anything
     * ambiguous is reported as unknown rather than guessed at — this data feeds a
     * "measured" display and must not become an estimate.
     */
    private fun String.singleIntOrNull(): Int? =
        if (contains('|')) null else trim().toIntOrNull()?.takeIf { it > 0 }

    /**
     * Extracts the `A2DP <codec> State:` block for [codecName], from its header to the
     * next block header or end of dump.
     */
    private fun findCodecBlock(dump: String, codecName: String): String? {
        val headers = CODEC_BLOCK_HEADER.findAll(dump).toList()
        val index = headers.indexOfFirst { it.groupValues[1].trim().equals(codecName, ignoreCase = true) }
        if (index < 0) return null
        val start = headers[index].range.last + 1
        val end = headers.getOrNull(index + 1)?.range?.first ?: dump.length
        return dump.substring(start, end)
    }

    /** Field spacing is wildly inconsistent (`Effective MTU: 879` vs a padded
     * `LDAC quality mode                    : ABR`), so whitespace is always flexible.
     * [label] is a regex fragment; callers escape parens themselves. */
    private fun String.intField(label: String): Int? =
        Regex("""$label\s*:\s*(-?\d+)""").find(this)
            ?.groupValues?.get(1)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 } // -1 and friends are "not applicable", not measurements

    private fun String.pairField(label: String): Pair<Long, Long>? =
        Regex("""$label\s*:\s*(\d+)\s*/\s*(\d+)""").find(this)?.let { match ->
            val first = match.groupValues[1].toLongOrNull() ?: return null
            val second = match.groupValues[2].toLongOrNull() ?: return null
            first to second
        }

    private fun String.wordField(label: String): String? =
        Regex("""$label\s*:\s*(\S+)""").find(this)?.groupValues?.get(1)
}

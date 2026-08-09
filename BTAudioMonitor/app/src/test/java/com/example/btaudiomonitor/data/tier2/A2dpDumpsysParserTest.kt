package com.example.btaudiomonitor.data.tier2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture is real, unmodified `dumpsys bluetooth_manager` output (MACs redacted) from a
 * Galaxy S20 FE, Android 13 / One UI 5.1, with a WH-1000XM3 connected and the codec
 * forced to AAC via developer options. Capturing it in a non-default codec state is
 * deliberate: it is what exposes the stale-inactive-block behaviour below.
 */
class A2dpDumpsysParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing test fixture: $name"
        }.bufferedReader().use { it.readText() }

    private val aacActive: String get() = fixture("dumpsys_a2dp_android13_aac_active.txt")

    private fun parseOrFail(dump: String): A2dpCodecStatus =
        checkNotNull(A2dpDumpsysParser.parse(dump)) { "parser unexpectedly returned null" }

    @Test
    fun `reads the active codec config`() {
        val status = parseOrFail(aacActive)

        assertEquals("AAC", status.codecName)
        assertEquals(44_100, status.sampleRateHz)
        assertEquals(16, status.bitsPerSample)
        assertEquals("STEREO", status.channelMode)
    }

    /**
     * The whole reason this parser reads `mCodecConfig` first. With AAC active, the
     * inactive LDAC block still advertises `Config: Rate=96000 Bits=32` — its last-used
     * values. A parser that searched for a codec block by name, or took the first block,
     * would confidently report 96 kHz/32-bit LDAC while the link actually runs AAC at
     * 44.1 kHz/16-bit.
     */
    @Test
    fun `ignores stale config on inactive codec blocks`() {
        val status = parseOrFail(aacActive)

        assertTrue("fixture must contain the stale LDAC values", aacActive.contains("Rate=96000 Bits=32"))
        assertEquals("AAC", status.codecName)
        assertEquals(44_100, status.sampleRateHz)
        assertEquals(16, status.bitsPerSample)
    }

    /**
     * LDAC-only fields must not leak in from the inactive LDAC block while AAC is the
     * active codec — and the value sitting there is `-1`, which would render as a
     * plausible-looking "-1 Kbps" if treated as data.
     */
    @Test
    fun `does not report ldac fields when ldac is not the active codec`() {
        val status = parseOrFail(aacActive)

        assertTrue("fixture must contain the -1 sentinel", aacActive.contains("LDAC transmission bitrate (Kbps)                        : -1"))
        assertNull(status.ldacBitrateKbps)
        assertNull(status.ldacQualityMode)
    }

    @Test
    fun `reads statistics from the active codec block`() {
        val status = parseOrFail(aacActive)

        assertEquals(302_842, status.encoderBitrateBps)
        assertEquals(2_487_296L, status.pcmReadBytes) // the "actual" side of expected/actual
        assertEquals(608L, status.packetsExpected)
        assertEquals(0L, status.packetsDropped)
        assertEquals(879, status.effectiveMtuBytes)
        assertEquals(false, status.isPlaying)
    }

    /** Negative sentinels mean "not applicable", never a measurement. */
    @Test
    fun `treats negative values as absent`() {
        val dump = """
            |    mCodecConfig: {codecName:LDAC,mCodecType:4,mSampleRate:0x8(96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO)}
            |
            |A2DP LDAC State:
            |  Config: Rate=96000 Bits=32 Mode=STEREO
            |  LDAC transmission bitrate (Kbps)                        : -1
            |  Current encoder bitrate : -1
            |  Effective MTU: 0
        """.trimMargin()

        val status = parseOrFail(dump)

        assertNull(status.ldacBitrateKbps)
        assertNull(status.encoderBitrateBps)
        assertEquals(0, status.effectiveMtuBytes) // zero is a real value, unlike -1
    }

    @Test
    fun `reads ldac fields when ldac is active`() {
        val dump = """
            |    mCodecConfig: {codecName:LDAC,mCodecType:4,mSampleRate:0x8(96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO)}
            |
            |A2DP LDAC State:
            |  Config: Rate=96000 Bits=32 Mode=STEREO
            |  LDAC quality mode                                       : ABR
            |  LDAC transmission bitrate (Kbps)                        : 330
        """.trimMargin()

        val status = parseOrFail(dump)

        assertEquals("LDAC", status.codecName)
        assertEquals(96_000, status.sampleRateHz)
        assertEquals(32, status.bitsPerSample)
        assertEquals("ABR", status.ldacQualityMode)
        assertEquals(330, status.ldacBitrateKbps)
    }

    /**
     * A bitmask holding several alternatives means nothing has been negotiated yet.
     * Reporting the max would turn a "measured" readout into a guess — the exact bug
     * that made the tier 1 estimate overstate reality by 2.2x.
     */
    @Test
    fun `reports ambiguous multi-value rates as unknown rather than guessing`() {
        val dump = """
            |    mCodecConfig: {codecName:LDAC,mCodecType:4,mSampleRate:0xf(44100|48000|88200|96000),mBitsPerSample:0x7(16|24|32),mChannelMode:0x2(STEREO)}
        """.trimMargin()

        val status = parseOrFail(dump)

        assertNull(status.sampleRateHz)
        assertNull(status.bitsPerSample)
    }

    /** `A2DP Source State: Enabled` is not a codec block and must not be mistaken for one. */
    @Test
    fun `does not treat the source state line as a codec block`() {
        val dump = """
            |    mCodecConfig: {codecName:Source,mCodecType:0,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO)}
            |
            |A2DP Source State: Enabled
            |  Effective MTU: 999
        """.trimMargin()

        val status = parseOrFail(dump)

        assertNull("must not read fields out of the Source line", status.effectiveMtuBytes)
    }

    /** Codec names are not a fixed set: Samsung ships SSC/SSCUHQ, and aptX HD has a space. */
    @Test
    fun `handles oem specific and multi word codec names`() {
        val dump = """
            |    mCodecConfig: {codecName:aptX HD,mCodecType:3,mSampleRate:0x2(48000),mBitsPerSample:0x2(24),mChannelMode:0x2(STEREO)}
            |
            |A2DP aptX HD State:
            |  Current encoder bitrate : 576000
        """.trimMargin()

        val status = parseOrFail(dump)

        assertEquals("aptX HD", status.codecName)
        assertEquals(576_000, status.encoderBitrateBps)
    }

    // ---------------------------------------------------------------------------
    // Android 16 / One UI 8 (Galaxy S24 Ultra), LDAC active, two devices bonded.
    // The surrounding dump structure differs substantially from Android 13, so these
    // guard the claim that the fields this parser depends on are version-stable.
    // ---------------------------------------------------------------------------

    private val ldacActiveA16: String get() = fixture("dumpsys_a2dp_android16_ldac_active.txt")

    @Test
    fun `reads android 16 output with the same field contract`() {
        val status = parseOrFail(ldacActiveA16)

        assertEquals("LDAC", status.codecName)
        assertEquals(96_000, status.sampleRateHz)
        assertEquals(32, status.bitsPerSample)
        assertEquals("STEREO", status.channelMode)
        assertEquals("ABR", status.ldacQualityMode)
        assertEquals(990, status.ldacBitrateKbps)
        assertEquals(879, status.effectiveMtuBytes)
        assertEquals(12_979_200L, status.pcmReadBytes)
        assertEquals(3_726L, status.packetsExpected)
        assertEquals(0L, status.packetsDropped)
        assertEquals(true, status.isPlaying)
    }

    /**
     * The Android 16 capture has two bonded devices with different codecs: the active
     * one on LDAC and a second on SBC. Selection must follow `mActiveDevice` rather than
     * document order, or a second paired device silently changes what gets reported.
     */
    @Test
    fun `selects the active device when several have codec configs`() {
        assertTrue("fixture must contain a second, non-active SBC device", ldacActiveA16.contains("codecName:SBC"))

        assertEquals("LDAC", parseOrFail(ldacActiveA16).codecName)
    }

    /** Order must not be what makes it work: the active device is chosen by address. */
    @Test
    fun `picks the active device even when it is not listed first`() {
        val dump = """
            |  mActiveDevice: DE:VI:CE:00:00:02
            |  === A2dpStateMachine for DE:VI:CE:00:00:01 ===
            |    mIsPlaying: false
            |    mCodecConfig: {codecName:SBC,mCodecType:0,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO)}
            |  === A2dpStateMachine for DE:VI:CE:00:00:02 (Active) ===
            |    mIsPlaying: true
            |    mCodecConfig: {codecName:LDAC,mCodecType:4,mSampleRate:0x8(96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO)}
        """.trimMargin()

        val status = parseOrFail(dump)

        assertEquals("LDAC", status.codecName)
        assertEquals(96_000, status.sampleRateHz)
        assertEquals(true, status.isPlaying)
    }

    /** With no resolvable active device, fall back rather than return nothing. */
    @Test
    fun `falls back to a dump wide search when no active device is named`() {
        val dump = """
            |  mActiveDevice: 00:00:00:00:00:00
            |    mCodecConfig: {codecName:AAC,mCodecType:1,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO)}
        """.trimMargin()

        assertEquals("AAC", parseOrFail(dump).codecName)
    }

    @Test
    fun `returns null when there is no codec config to read`() {
        assertNull(A2dpDumpsysParser.parse(""))
        assertNull(A2dpDumpsysParser.parse("Bluetooth is off"))
        assertNull(A2dpDumpsysParser.parse("A2DP LDAC State:\n  Config: Rate=96000 Bits=32 Mode=STEREO"))
    }

    /** Truncated or garbled output must degrade to nulls, never throw — callers fall
     * back to tier 1 on null, and an exception would take the whole flow down. */
    @Test
    fun `does not throw on malformed input`() {
        val truncated = aacActive.substring(0, aacActive.length / 3)
        A2dpDumpsysParser.parse(truncated)

        A2dpDumpsysParser.parse("mCodecConfig: {")
        A2dpDumpsysParser.parse("mCodecConfig: {}")
        A2dpDumpsysParser.parse("mCodecConfig: {codecName:}")
        A2dpDumpsysParser.parse("mCodecConfig: {codecName:AAC,mSampleRate:0x1(notanumber)}")
    }
}

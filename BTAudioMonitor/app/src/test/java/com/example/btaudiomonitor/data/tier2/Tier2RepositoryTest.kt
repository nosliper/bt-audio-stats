package com.example.btaudiomonitor.data.tier2

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the state mapping and counter-baseline handling. Uses a short poll interval and
 * a scripted source rather than a virtual-time test dispatcher, to avoid pulling in a
 * test-only coroutines dependency for what is a handful of emissions.
 */
class Tier2RepositoryTest {

    /** Returns each script entry in turn, repeating the last one forever. */
    private class ScriptedSource(private vararg val script: String?) : DumpsysSource {
        var calls = 0
            private set

        override suspend fun readBluetoothManagerDump(): String? =
            script[minOf(calls++, script.size - 1)]
    }

    private fun dumpWith(codec: String, pcmBytes: Long) = """
        |  mActiveDevice: DE:VI:CE:00:00:00
        |  === A2dpStateMachine for DE:VI:CE:00:00:00 ===
        |    mIsPlaying: true
        |    mCodecConfig: {codecName:$codec,mCodecType:4,mSampleRate:0x8(96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO)}
        |
        |A2DP $codec State:
        |  PCM read bytes (expected/actual)                        : $pcmBytes / $pcmBytes
    """.trimMargin()

    private fun collect(source: DumpsysSource, count: Int): List<Tier2State> = runBlocking {
        Tier2RepositoryImpl(source).codecStatus(flowOf(20L)).take(count).toList()
    }

    /** An unreadable dump means DUMP is not granted — the UI must offer setup, not an error. */
    @Test
    fun `reports unavailable when the dump cannot be read`() {
        assertEquals(listOf(Tier2State.Unavailable), collect(ScriptedSource(null), 1))
    }

    /** Readable but nothing connected is a different state from "not set up". */
    @Test
    fun `reports no active codec when the dump has nothing to parse`() {
        assertEquals(listOf(Tier2State.NoActiveCodec), collect(ScriptedSource("Bluetooth is off"), 1))
    }

    @Test
    fun `reports the parsed codec when available`() {
        val states = collect(ScriptedSource(dumpWith("LDAC", 1_000)), 1)

        val available = states.single() as Tier2State.Available
        assertEquals("LDAC", available.status.codecName)
        assertEquals(96_000, available.status.sampleRateHz)
    }

    /** No baseline exists on the first poll, so no rate may be claimed yet. */
    @Test
    fun `does not report throughput on the first sample`() {
        val states = collect(ScriptedSource(dumpWith("LDAC", 1_000)), 1)

        assertNull((states.single() as Tier2State.Available).measuredBytesPerSecond)
    }

    @Test
    fun `measures throughput once a baseline exists`() {
        val source = ScriptedSource(dumpWith("LDAC", 0), dumpWith("LDAC", 100_000))

        val measured = collect(source, 2)
            .filterIsInstance<Tier2State.Available>()
            .mapNotNull { it.measuredBytesPerSecond }

        assertTrue("expected a rate after the second sample", measured.isNotEmpty())
        assertTrue("rate should be positive", measured.first() > 0)
    }

    /**
     * Renegotiation zeroes the encoder counters. Differencing a fresh counter against
     * the previous codec's total would produce a wild figure, so the baseline resets.
     */
    @Test
    fun `resets the baseline when the codec changes`() {
        val source = ScriptedSource(
            dumpWith("LDAC", 5_000_000),
            dumpWith("AAC", 1_000), // renegotiated: counter restarted
        )

        val states = collect(source, 2).filterIsInstance<Tier2State.Available>()

        assertEquals("AAC", states.last().status.codecName)
        assertNull("must not difference across a codec change", states.last().measuredBytesPerSecond)
    }
}

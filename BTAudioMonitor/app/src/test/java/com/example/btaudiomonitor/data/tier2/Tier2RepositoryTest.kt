package com.example.btaudiomonitor.data.tier2

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Tier2RepositoryTest {

    /** Returns each script entry in turn, repeating the last one forever. */
    private class ScriptedSource(private vararg val script: String?) : DumpsysSource {
        var calls = 0
            private set

        override suspend fun readBluetoothManagerDump(): String? =
            script[minOf(calls++, script.size - 1)]
    }

    private fun dump(
        codec: String = "LDAC",
        pcmBytes: Long = 0,
        packetsExpected: Long = 0,
        packetsDropped: Long = 0,
        ldacKbps: Int = 990,
        playing: Boolean = true,
    ) = """
        |  mActiveDevice: DE:VI:CE:00:00:00
        |  === A2dpStateMachine for DE:VI:CE:00:00:00 ===
        |    mIsPlaying: $playing
        |    mCodecConfig: {codecName:$codec,mCodecType:4,mSampleRate:0x8(96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO)}
        |
        |A2DP $codec State:
        |  LDAC transmission bitrate (Kbps)                        : $ldacKbps
        |  Packet counts (expected/dropped)                        : $packetsExpected / $packetsDropped
        |  PCM read bytes (expected/actual)                        : $pcmBytes / $pcmBytes
    """.trimMargin()

    // --- read() -------------------------------------------------------------

    @Test
    fun `read reports unavailable when the dump cannot be read`() = runBlocking {
        assertEquals(Tier2State.Unavailable, Tier2RepositoryImpl(ScriptedSource(null)).read())
    }

    @Test
    fun `read reports no active codec when there is nothing to parse`() = runBlocking {
        assertEquals(
            Tier2State.NoActiveCodec,
            Tier2RepositoryImpl(ScriptedSource("Bluetooth is off")).read(),
        )
    }

    @Test
    fun `read returns the parsed codec`() = runBlocking {
        val state = Tier2RepositoryImpl(ScriptedSource(dump())).read()

        assertEquals("LDAC", (state as Tier2State.Available).status.codecName)
    }

    /** One read must cost exactly one dumpsys — this is the whole reason it is on demand. */
    @Test
    fun `read costs a single dumpsys call`() = runBlocking {
        val source = ScriptedSource(dump())

        Tier2RepositoryImpl(source).read()

        assertEquals(1, source.calls)
    }

    // --- runBenchmark() -----------------------------------------------------

    private fun benchmark(source: DumpsysSource, durationMillis: Long = 60) =
        runBlocking { Tier2RepositoryImpl(source).runBenchmark(durationMillis).toList() }

    @Test
    fun `benchmark measures average throughput across the window`() {
        val source = ScriptedSource(
            dump(pcmBytes = 0, packetsExpected = 0, packetsDropped = 0),
            dump(pcmBytes = 1_000_000, packetsExpected = 500, packetsDropped = 3),
        )

        val result = benchmark(source).filterIsInstance<BenchmarkState.Complete>().single().result

        assertEquals(1_000_000L, result.bytesTransferred)
        assertTrue("expected a positive rate", result.averageBytesPerSecond > 0)
        // Counters are reported as deltas over the run, not lifetime totals.
        assertEquals(500L, result.packetsExpected)
        assertEquals(3L, result.packetsDropped)
    }

    /**
     * The point of the redesign: a 60 s run must not cost 60 dumpsys calls. Two reads,
     * regardless of duration, because polling made audio stutter.
     */
    @Test
    fun `benchmark costs exactly two dumpsys calls regardless of duration`() {
        val source = ScriptedSource(dump(pcmBytes = 0), dump(pcmBytes = 100))

        benchmark(source, durationMillis = 500)

        assertEquals(2, source.calls)
    }

    @Test
    fun `benchmark emits progress before completing`() {
        val states = benchmark(
            ScriptedSource(dump(pcmBytes = 0), dump(pcmBytes = 100)),
            durationMillis = 600,
        )

        assertTrue(
            "expected progress ticks",
            states.filterIsInstance<BenchmarkState.Running>().isNotEmpty(),
        )
        assertTrue("expected a terminal state", states.last() is BenchmarkState.Complete)
    }

    @Test
    fun `benchmark fails when dumpsys is unreadable`() {
        val failure = benchmark(ScriptedSource(null)).last() as BenchmarkState.Failed

        assertTrue(failure.reason.contains("DUMP"))
    }

    /** Renegotiation restarts the counters, so an average across it is meaningless. */
    @Test
    fun `benchmark rejects a codec change mid run`() {
        val source = ScriptedSource(
            dump(codec = "LDAC", pcmBytes = 5_000_000),
            dump(codec = "AAC", pcmBytes = 1_000),
        )

        val failure = benchmark(source).last() as BenchmarkState.Failed

        assertTrue(failure.reason.contains("Codec changed"))
    }

    /** The counter can reset without the codec name changing. */
    @Test
    fun `benchmark rejects a counter reset mid run`() {
        val source = ScriptedSource(dump(pcmBytes = 5_000_000), dump(pcmBytes = 1_000))

        val failure = benchmark(source).last() as BenchmarkState.Failed

        assertTrue(failure.reason.contains("reset"))
    }

    /** A paused stream still produces a number; it just needs flagging as low. */
    @Test
    fun `benchmark flags playback that did not run throughout`() {
        val source = ScriptedSource(
            dump(pcmBytes = 0, playing = true),
            dump(pcmBytes = 100, playing = false),
        )

        val result = benchmark(source).filterIsInstance<BenchmarkState.Complete>().single().result

        assertEquals(false, result.streamingThroughout)
    }

    @Test
    fun `benchmark reports the ldac bitrate at both ends of the run`() {
        val source = ScriptedSource(
            dump(pcmBytes = 0, ldacKbps = 990),
            dump(pcmBytes = 100, ldacKbps = 330),
        )

        val result = benchmark(source).filterIsInstance<BenchmarkState.Complete>().single().result

        assertEquals(990, result.ldacBitrateStartKbps)
        assertEquals(330, result.ldacBitrateEndKbps)
    }
}

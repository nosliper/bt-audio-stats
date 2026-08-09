package com.example.btaudiomonitor.data.tier2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThroughputMeterTest {

    @Test
    fun `first sample has no baseline to compare against`() {
        assertNull(ThroughputMeter().sample(bytes = 1_000, atMillis = 0))
    }

    @Test
    fun `computes bytes per second across an interval`() {
        val meter = ThroughputMeter()

        meter.sample(bytes = 1_000, atMillis = 0)

        assertEquals(1_000L, meter.sample(bytes = 2_000, atMillis = 1_000))
    }

    /** Reproduces the real measurement: 1,462,272 bytes over 8.26 s of AAC playback. */
    @Test
    fun `matches the observed live measurement`() {
        val meter = ThroughputMeter()

        meter.sample(bytes = 10_424_320, atMillis = 0)
        val rate = meter.sample(bytes = 11_886_592, atMillis = 8_260)

        val expected = 44_100L * 2 * 2 // AAC 44.1 kHz, stereo, 16-bit
        val errorRatio = (rate!! - expected).toDouble() / expected
        assertEquals(0.0, errorRatio, 0.02)
    }

    /**
     * Codec renegotiation zeroes the encoder counters. Differencing across that would
     * produce a negative rate, so the meter re-baselines instead.
     */
    @Test
    fun `treats a backwards counter as a reset rather than negative throughput`() {
        val meter = ThroughputMeter()

        meter.sample(bytes = 5_000, atMillis = 0)

        assertNull(meter.sample(bytes = 100, atMillis = 1_000))
        // Re-baselined, so the following interval measures normally.
        assertEquals(400L, meter.sample(bytes = 500, atMillis = 2_000))
    }

    @Test
    fun `rejects non advancing timestamps`() {
        val meter = ThroughputMeter()

        meter.sample(bytes = 1_000, atMillis = 5_000)

        assertNull(meter.sample(bytes = 2_000, atMillis = 5_000))
        assertNull(meter.sample(bytes = 3_000, atMillis = 4_000))
    }

    @Test
    fun `an idle link measures zero rather than nothing`() {
        val meter = ThroughputMeter()

        meter.sample(bytes = 7_000, atMillis = 0)

        assertEquals(0L, meter.sample(bytes = 7_000, atMillis = 1_000))
    }

    @Test
    fun `reset drops the baseline`() {
        val meter = ThroughputMeter()

        meter.sample(bytes = 1_000, atMillis = 0)
        meter.reset()

        assertNull(meter.sample(bytes = 2_000, atMillis = 1_000))
    }
}

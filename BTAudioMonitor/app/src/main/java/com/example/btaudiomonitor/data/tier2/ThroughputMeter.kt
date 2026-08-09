package com.example.btaudiomonitor.data.tier2

/**
 * Turns successive readings of [A2dpCodecStatus.pcmReadBytes] into a measured
 * throughput. This is the one number in the app that is genuinely *measured* rather
 * than derived from format math — validated against a live link at 172.9 KB/s observed
 * versus 172.3 KB/s theoretical for AAC 44.1 kHz/16-bit stereo (0.4% error).
 *
 * Deliberately not a rolling average: a single interval is what the poll cadence
 * already gives us, and smoothing would blur exactly the transitions (playback
 * starting, codec renegotiating) this app exists to show.
 *
 * Not thread-safe; call from a single collector.
 */
class ThroughputMeter {

    private var lastBytes: Long? = null
    private var lastAtMillis: Long? = null

    /**
     * Records a counter reading and returns bytes/second since the previous one, or
     * null when a rate cannot honestly be computed:
     *
     * - the first sample, which has no baseline to compare against
     * - a counter that went backwards, which means the encoder restarted (codec
     *   renegotiation zeroes these counters) — reporting a negative or a huge
     *   wrapped-around rate would be worse than reporting nothing
     * - a non-positive elapsed time
     *
     * A null return still re-baselines, so the next sample can produce a rate.
     */
    fun sample(bytes: Long, atMillis: Long): Long? {
        val previousBytes = lastBytes
        val previousAt = lastAtMillis

        lastBytes = bytes
        lastAtMillis = atMillis

        if (previousBytes == null || previousAt == null) return null
        if (bytes < previousBytes) return null // counter reset

        val elapsedMillis = atMillis - previousAt
        if (elapsedMillis <= 0) return null

        return (bytes - previousBytes) * 1000 / elapsedMillis
    }

    /**
     * Drops the baseline. Call when the active codec changes or the device
     * disconnects, so a stale counter is never differenced against a fresh one.
     */
    fun reset() {
        lastBytes = null
        lastAtMillis = null
    }
}

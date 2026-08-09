package com.example.btaudiomonitor.data.tier2

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A single tier 2 snapshot. Note there is no throughput here: one reading of a counter
 * cannot produce a rate. Throughput comes from [Tier2Repository.runBenchmark].
 */
sealed interface Tier2State {

    /** `dumpsys` could not be read — almost always DUMP not granted. Tier 1 only. */
    data object Unavailable : Tier2State

    /** Readable, but no active A2DP codec to report (nothing connected). */
    data object NoActiveCodec : Tier2State

    data class Available(val status: A2dpCodecStatus) : Tier2State
}

/**
 * Result of a timed measurement. Everything here is a delta across the window rather
 * than an instantaneous reading, which is both cheaper and more accurate — see
 * [Tier2Repository.runBenchmark].
 */
data class BenchmarkResult(
    val durationMillis: Long,
    val bytesTransferred: Long,
    val averageBytesPerSecond: Long,
    val codecName: String,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val packetsExpected: Long?,
    val packetsDropped: Long?,
    val ldacBitrateStartKbps: Int?,
    val ldacBitrateEndKbps: Int?,
    /** False if playback stopped at some point, which makes the average an underestimate. */
    val streamingThroughout: Boolean,
)

sealed interface BenchmarkState {
    data object Idle : BenchmarkState
    data class Running(val elapsedMillis: Long, val totalMillis: Long) : BenchmarkState
    data class Complete(val result: BenchmarkResult) : BenchmarkState
    data class Failed(val reason: String) : BenchmarkState
}

/** Tier 2 lives behind its own interface so tier 1 has no reference to it at all. */
interface Tier2Repository {

    /** One snapshot. Each call costs a full `dumpsys`, so call it sparingly. */
    suspend fun read(): Tier2State

    /**
     * Measures average throughput over [durationMillis].
     *
     * Emits [BenchmarkState.Running] progress ticks driven purely by the clock, so the
     * only `dumpsys` calls are one at the start and one at the end — two for the whole
     * run, versus sixty for a minute of 1 Hz polling.
     */
    fun runBenchmark(durationMillis: Long): Flow<BenchmarkState>
}

/**
 * Reads `dumpsys bluetooth_manager` on demand.
 *
 * **Do not poll this continuously.** The dump is ~2.5 MB, takes 500-900 ms, and cannot
 * be narrowed — every documented argument is ignored and returns the whole thing.
 * Calling it once a second was confirmed on-device to make Bluetooth audio audibly
 * stutter; the same test with tier 2 disabled played cleanly. Nothing in the A2DP
 * TxQueue counters or logcat registered the glitch, so do not expect a metric to warn
 * you if this regresses — verify by listening.
 *
 * The design consequence: the codec/format fields barely change (only on
 * renegotiation), so they are fetched on demand; throughput is measured over a long
 * window instead of sampled continuously, which is both cheaper and more accurate.
 */
class Tier2RepositoryImpl(
    private val source: DumpsysSource = AppProcessDumpsysSource(),
) : Tier2Repository {

    override suspend fun read(): Tier2State {
        val dump = source.readBluetoothManagerDump() ?: return Tier2State.Unavailable
        val status = A2dpDumpsysParser.parse(dump) ?: return Tier2State.NoActiveCodec
        return Tier2State.Available(status)
    }

    override fun runBenchmark(durationMillis: Long): Flow<BenchmarkState> = flow {
        emit(BenchmarkState.Running(elapsedMillis = 0, totalMillis = durationMillis))

        val startDump = source.readBluetoothManagerDump()
            ?: return@flow emit(BenchmarkState.Failed("Cannot read dumpsys — is DUMP granted?"))
        val startedAt = System.currentTimeMillis()
        val start = A2dpDumpsysParser.parse(startDump)
            ?: return@flow emit(BenchmarkState.Failed("No active Bluetooth audio codec."))
        val startBytes = start.pcmReadBytes
            ?: return@flow emit(BenchmarkState.Failed("This device exposes no byte counter to measure."))

        // Progress ticks come from the clock alone. Touching dumpsys here would both
        // disturb the audio and distort what we are trying to measure.
        while (true) {
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= durationMillis) break
            emit(BenchmarkState.Running(elapsed, durationMillis))
            delay(PROGRESS_TICK_MS.coerceAtMost(durationMillis - elapsed))
        }

        val endDump = source.readBluetoothManagerDump()
            ?: return@flow emit(BenchmarkState.Failed("Lost access to dumpsys during the run."))
        val endedAt = System.currentTimeMillis()
        val end = A2dpDumpsysParser.parse(endDump)
            ?: return@flow emit(BenchmarkState.Failed("Bluetooth audio disconnected during the run."))
        val endBytes = end.pcmReadBytes
            ?: return@flow emit(BenchmarkState.Failed("Byte counter disappeared during the run."))

        if (end.codecName != start.codecName) {
            return@flow emit(
                BenchmarkState.Failed(
                    "Codec changed mid-run (${start.codecName} to ${end.codecName}); " +
                        "the counters restarted, so no valid average exists.",
                ),
            )
        }
        // Renegotiation zeroes the counters even when the codec name is unchanged.
        if (endBytes < startBytes) {
            return@flow emit(
                BenchmarkState.Failed("The encoder counter reset mid-run — measurement discarded."),
            )
        }

        val actualDuration = endedAt - startedAt
        if (actualDuration <= 0) {
            return@flow emit(BenchmarkState.Failed("Clock did not advance."))
        }

        val transferred = endBytes - startBytes
        emit(
            BenchmarkState.Complete(
                BenchmarkResult(
                    durationMillis = actualDuration,
                    bytesTransferred = transferred,
                    averageBytesPerSecond = transferred * 1000 / actualDuration,
                    codecName = end.codecName,
                    sampleRateHz = end.sampleRateHz,
                    bitsPerSample = end.bitsPerSample,
                    // Deltas, so they describe this run rather than all time.
                    packetsExpected = diffOrNull(start.packetsExpected, end.packetsExpected),
                    packetsDropped = diffOrNull(start.packetsDropped, end.packetsDropped),
                    ldacBitrateStartKbps = start.ldacBitrateKbps,
                    ldacBitrateEndKbps = end.ldacBitrateKbps,
                    streamingThroughout = start.isPlaying == true && end.isPlaying == true,
                ),
            ),
        )
    }

    private fun diffOrNull(start: Long?, end: Long?): Long? =
        if (start != null && end != null && end >= start) end - start else null

    private companion object {
        const val PROGRESS_TICK_MS = 250L
    }
}

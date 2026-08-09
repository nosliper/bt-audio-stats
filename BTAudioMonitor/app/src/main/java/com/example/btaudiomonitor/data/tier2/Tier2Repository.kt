package com.example.btaudiomonitor.data.tier2

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * What tier 2 can currently tell us. Modelled as three distinct states rather than a
 * nullable status because the UI must say different things for each: an ungranted
 * permission is a setup step the user can act on, whereas a granted permission with
 * nothing connected is simply idle.
 */
sealed interface Tier2State {

    /** `dumpsys` could not be read — almost always DUMP not granted. Tier 1 only. */
    data object Unavailable : Tier2State

    /** Readable, but no active A2DP codec to report (nothing connected). */
    data object NoActiveCodec : Tier2State

    /**
     * @param measuredBytesPerSecond genuinely measured PCM throughput from the byte
     *   counter delta, or null before a baseline exists (first poll, or just after a
     *   codec change reset the counter).
     */
    data class Available(
        val status: A2dpCodecStatus,
        val measuredBytesPerSecond: Long?,
    ) : Tier2State
}

/** Tier 2 lives behind its own interface so tier 1 has no reference to it at all. */
interface Tier2Repository {
    fun codecStatus(pollIntervalMs: Flow<Long>): Flow<Tier2State>
}

class Tier2RepositoryImpl(
    private val source: DumpsysSource = AppProcessDumpsysSource(),
) : Tier2Repository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun codecStatus(pollIntervalMs: Flow<Long>): Flow<Tier2State> =
        pollIntervalMs.flatMapLatest { interval ->
            flow {
                // Per-collection state: a restart (interval change, resubscribe) must
                // not difference a fresh counter against a stale baseline.
                val meter = ThroughputMeter()
                var lastCodec: String? = null

                while (true) {
                    val dump = source.readBluetoothManagerDump()
                    // Timestamp as close to the read as possible. The counter's value
                    // is fixed when dumpsys samples it, so any work done afterwards --
                    // parsing several MB of text -- is latency that would otherwise be
                    // charged to the interval. At a 1 s window even ~60 ms of drift is
                    // a 6% error in the reported rate.
                    val readAtMillis = System.currentTimeMillis()
                    val status = dump?.let(A2dpDumpsysParser::parse)

                    when {
                        dump == null -> {
                            meter.reset()
                            lastCodec = null
                            emit(Tier2State.Unavailable)
                        }

                        status == null -> {
                            meter.reset()
                            lastCodec = null
                            emit(Tier2State.NoActiveCodec)
                        }

                        else -> {
                            // Renegotiation zeroes the encoder counters, so a codec
                            // change invalidates the baseline.
                            if (status.codecName != lastCodec) {
                                meter.reset()
                                lastCodec = status.codecName
                            }
                            val measured = status.pcmReadBytes
                                ?.let { meter.sample(it, readAtMillis) }
                            emit(Tier2State.Available(status, measured))
                        }
                    }

                    delay(interval)
                }
            }
        }.distinctUntilChanged()
}

package com.rmpsdroid.battinsight.series

import com.rmpsdroid.battinsight.batterystats.CounterDeltaEngine
import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.batterystats.CounterDeltaResult
import com.rmpsdroid.battinsight.batterystats.KernelWakelockDelta
import com.rmpsdroid.battinsight.batterystats.PartialWakelockDelta
import com.rmpsdroid.battinsight.batterystats.SessionCounterState
import com.rmpsdroid.battinsight.batterystats.StoredCounterCapture

/**
 * The counter series: what accumulated between each pair of adjacent captures.
 *
 * ## This is not the whole-session summary, and the difference matters
 *
 * Two questions, two calculations, deliberately kept apart:
 *
 * | Question | Computed from | Used by |
 * |---|---|---|
 * | "What accumulated during this session?" | **baseline → latest** | Phase 8 detail screen |
 * | "When did activity increase?" | **capture N → N+1** | this, for Phase 9C |
 *
 * Deriving the second from the first is the mistake this type exists to prevent. Every
 * baseline→N interval includes everything before it, so a chart of them is monotonically
 * non-decreasing *by construction* -- it would look like a trend and be an artefact of the
 * arithmetic. Phase 7B's baseline semantics stay exactly as they are; this adds a second,
 * separate reading of the same stored captures.
 */
data class CounterSeries(
    val sessionId: String,
    /** One entry per adjacent pair of retained captures, in elapsed order. */
    val intervals: List<CounterInterval>,
) {
    val comparableIntervals: List<CounterInterval.Comparable>
        get() = intervals.filterIsInstance<CounterInterval.Comparable>()

    val refusedIntervals: List<CounterInterval.Refused>
        get() = intervals.filterIsInstance<CounterInterval.Refused>()
}

/** One adjacent pair: either a measurement, or a refusal with a reason. */
sealed interface CounterInterval {
    val fromCaptureId: String
    val toCaptureId: String
    val fromElapsedMillis: Long
    val toElapsedMillis: Long

    /**
     * A pair that may be subtracted.
     *
     * A delta of zero here is a **measurement** -- "nothing accumulated" -- and must never be
     * presented as missing data. An identity absent from either side simply does not appear;
     * its series starts or ends, which is not the same as falling to zero.
     */
    data class Comparable(
        override val fromCaptureId: String,
        override val toCaptureId: String,
        override val fromElapsedMillis: Long,
        override val toElapsedMillis: Long,
        val kernelDeltas: List<KernelWakelockDelta>,
        val partialDeltas: List<PartialWakelockDelta>,
    ) : CounterInterval {
        val durationMillis: Long get() = toElapsedMillis - fromElapsedMillis
    }

    /**
     * A pair that must not be subtracted, and why.
     *
     * Rendered as a gap, never as zero and never as a negative. Phase 7B.1 established that
     * the refusal is **pair-level**: one decreased counter makes every counter in the pair
     * untrustworthy, because the accounting origin moved underneath all of them.
     */
    data class Refused(
        override val fromCaptureId: String,
        override val toCaptureId: String,
        override val fromElapsedMillis: Long,
        override val toElapsedMillis: Long,
        val reason: CounterDeltaReason,
        val explanation: String,
    ) : CounterInterval
}

/**
 * Builds the adjacent-interval series from ordered retained captures.
 *
 * Every interval is evaluated **independently** through the existing [CounterDeltaEngine].
 * There is no second comparison engine here and no cached verdict: a refusal at N → N+1 says
 * nothing about N+1 → N+2, which is evaluated on its own merits and may well be comparable.
 *
 * A refusal also does not advance [com.rmpsdroid.battinsight.session.CounterGeneration] and
 * does not claim the system reset its counters. Phase 7B left generation advancement to
 * explicit detection, and a chart is not evidence.
 */
object CounterSeriesBuilder {

    fun build(sessionId: String, captures: List<StoredCounterCapture>): CounterSeries {
        val ordered = captures.sortedWith(
            compareBy({ it.captureElapsedRealtimeMillis }, { it.captureId }),
        )
        if (ordered.size < 2) return CounterSeries(sessionId, emptyList())

        val intervals = (1 until ordered.size).map { i ->
            interval(sessionId, ordered[i - 1], ordered[i])
        }
        return CounterSeries(sessionId, intervals)
    }

    private fun interval(
        sessionId: String,
        from: StoredCounterCapture,
        to: StoredCounterCapture,
    ): CounterInterval {
        // The engine takes a baseline/latest pair, so an adjacent pair is expressed as one.
        // Reusing it rather than reimplementing subtraction is the point: the refusal order,
        // the pair-level continuity break and the missing-counter rules all come for free and
        // stay in one place.
        val pair = SessionCounterState(sessionId, from, to)

        val kernel = CounterDeltaEngine.kernelWakelockDeltas(pair)
        if (kernel is CounterDeltaResult.NotComparable) {
            return CounterInterval.Refused(
                fromCaptureId = from.captureId,
                toCaptureId = to.captureId,
                fromElapsedMillis = from.captureElapsedRealtimeMillis,
                toElapsedMillis = to.captureElapsedRealtimeMillis,
                reason = kernel.reason,
                explanation = kernel.detail,
            )
        }
        val partial = CounterDeltaEngine.partialWakelockDeltas(pair)
        if (partial is CounterDeltaResult.NotComparable) {
            return CounterInterval.Refused(
                fromCaptureId = from.captureId,
                toCaptureId = to.captureId,
                fromElapsedMillis = from.captureElapsedRealtimeMillis,
                toElapsedMillis = to.captureElapsedRealtimeMillis,
                reason = partial.reason,
                explanation = partial.detail,
            )
        }

        return CounterInterval.Comparable(
            fromCaptureId = from.captureId,
            toCaptureId = to.captureId,
            fromElapsedMillis = from.captureElapsedRealtimeMillis,
            toElapsedMillis = to.captureElapsedRealtimeMillis,
            kernelDeltas = (kernel as CounterDeltaResult.Success).value,
            partialDeltas = (partial as CounterDeltaResult.Success).value,
        )
    }
}

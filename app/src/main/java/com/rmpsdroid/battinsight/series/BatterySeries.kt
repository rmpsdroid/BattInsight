package com.rmpsdroid.battinsight.series

import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.BootRelation
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.relationTo

/**
 * The sampled battery series, already divided into what may be drawn as a line and what may
 * not.
 *
 * ## Why connectivity is decided here and not by the chart
 *
 * Every line-chart renderer connects consecutive points by default, and in this data every
 * gap means something: a reboot restarted the clock, the process died, retention removed
 * data, or nobody was watching. A renderer given a flat list of points would join all of them
 * and assert continuity that was never observed -- and question B of Phase 9A is literally
 * "when was drain fastest", so an interpolated segment would answer the user's question with
 * a fabrication.
 *
 * So the read model hands Phase 9C **segments and gaps**, not points. The chart's only job is
 * to render what it is given; it has no way to express "connect these two", because two
 * segments are never adjacent without a gap between them.
 *
 * Pure domain: no Room, no Compose, no Android.
 */
data class BatterySeries(
    val sessionId: String,
    /** Segments and gaps in elapsed order, covering the session's sampled span. */
    val elements: List<BatterySeriesElement>,
) {
    val segments: List<BatterySegment> get() = elements.filterIsInstance<BatterySegment>()
    val gaps: List<SeriesGap> get() = elements.filterIsInstance<SeriesGap>()

    /** True when nothing was ever sampled. Not the same as a series full of gaps. */
    val isEmpty: Boolean get() = elements.isEmpty()
}

/** Either a run of connected points, or an interval where connection is not permitted. */
sealed interface BatterySeriesElement

/**
 * A run of samples that may be drawn as one connected line.
 *
 * A single-point segment is legal and must render as a point. It says "one reading here",
 * which is true and useful; drawing a line to nowhere would not be.
 */
data class BatterySegment(val points: List<BatterySeriesPoint>) : BatterySeriesElement {
    init {
        require(points.isNotEmpty()) { "a segment with no points is a gap, not a segment" }
    }

    val startElapsedMillis: Long get() = points.first().elapsedRealtimeMillis
    val endElapsedMillis: Long get() = points.last().elapsedRealtimeMillis
}

/**
 * An interval that must not be drawn as a connected line, and why.
 *
 * Endpoints are nullable because a leading [SeriesGapReason.NOT_RETAINED] has no observed
 * start -- the samples that would have defined it were deleted.
 */
data class SeriesGap(
    val reason: SeriesGapReason,
    val fromElapsedMillis: Long?,
    val toElapsedMillis: Long?,
) : BatterySeriesElement

/**
 * Why two samples may not be connected.
 *
 * Each value is a distinct thing to tell a person, which is why they are not collapsed into
 * one "no data" case. "Your device restarted", "BattInsight was not running", and "we did not
 * keep that far back" are different facts, and only the first two are about the device.
 */
enum class SeriesGapReason {
    /** Nobody was sampling. The app was not visible, or the cadence did not fire. */
    NOT_OBSERVED,

    /** The process died and was restarted; the next sample announced itself as a fresh start. */
    PROCESS_RESTART,

    /** The device rebooted. Elapsed realtime restarted, so there is no shared axis at all. */
    DIFFERENT_BOOT,

    /**
     * Continuity could not be proven either way.
     *
     * Distinct from [DIFFERENT_BOOT]: this is the absence of evidence, not evidence of a
     * restart. Reached whenever boot identity is derived or unknown on either side -- see
     * [BootIdentity.relationTo], where two *equal* derived values are still `UNKNOWN`.
     */
    CONTINUITY_UNPROVEN,

    /** Samples existed here and retention deleted them. Ours, not the device's. */
    NOT_RETAINED,

    /** Stored state contradicts itself -- elapsed realtime ran backwards within one boot. */
    MALFORMED,
}

/** One sampled reading, as the read model exposes it. */
data class BatterySeriesPoint(
    val elapsedRealtimeMillis: Long,
    val wallClockMillis: Long,
    val utcOffsetMinutes: Int,
    val bootIdentity: BootIdentity,
    /** Null when the platform did not report it. Never zero as a stand-in. */
    val level: Int?,
    val scale: Int?,
    val status: BatteryStatus,
    val plug: PlugSource,
    val temperatureDeciCelsius: Int?,
    val voltageMilliVolts: Int?,
    val chargeCounterMicroAmpHours: Long?,
    val trigger: SessionTrigger,
) {
    /**
     * Percentage, when both parts were reported.
     *
     * Null rather than zero when either is missing, and null rather than a guess when the
     * scale is zero: dividing by it would produce infinity, and defaulting the scale to 100
     * would invent a denominator the device never gave.
     */
    val percent: Int?
        get() = if (level != null && scale != null && scale > 0) level * 100 / scale else null
}

/**
 * Builds a [BatterySeries] from ordered samples.
 *
 * Pure and total: no I/O, no clock, no Android. Every decision is made from what the samples
 * themselves carry, which is what lets the rules be tested exhaustively on the JVM.
 */
object BatterySeriesBuilder {

    /**
     * How much later than the cadence a sample may arrive and still count as adjacent.
     *
     * The cadence is a coroutine `delay`, not a real-time guarantee, and the sample that lands
     * when the UI becomes visible again is deliberately immediate rather than aligned to a
     * grid. A tight tolerance would report routine scheduling jitter as an unobserved gap,
     * which is a lie in the other direction. One named constant so the policy is in one place.
     */
    const val CADENCE_TOLERANCE_FACTOR = 2

    /**
     * @param samples **in elapsed order**, as the DAO already returns them.
     *
     *   Deliberately not re-sorted here. Sorting would be defensive, and it would also make
     *   [SeriesGapReason.MALFORMED] unreachable -- a pair whose elapsed realtime runs backwards
     *   within one boot would be silently reordered into a well-formed one. Elapsed realtime
     *   is monotonic within a boot, so a decrease means stored state contradicts itself, and
     *   that is worth reporting rather than tidying away.
     * @param evictedThroughElapsedMillis the session's retention watermark: the greatest
     *   elapsed time actually deleted, or null if nothing ever was. Non-null produces the
     *   leading [SeriesGapReason.NOT_RETAINED] gap, because the first retained sample must
     *   never be presented as though it directly followed the session's start.
     */
    fun build(
        sessionId: String,
        samples: List<BatterySeriesPoint>,
        cadenceMillis: Long,
        evictedThroughElapsedMillis: Long? = null,
    ): BatterySeries {
        if (samples.isEmpty()) {
            // No samples and no watermark: nothing was ever sampled. That is not a gap in a
            // series -- there is no series -- and fabricating one would invent an interval.
            if (evictedThroughElapsedMillis == null) return BatterySeries(sessionId, emptyList())
            // Watermark but no samples is structurally impossible: eviction stops at the cap
            // and can never empty a session. Reaching it means stored state disagrees with
            // itself, so say so rather than render nothing.
            return BatterySeries(
                sessionId,
                listOf(SeriesGap(SeriesGapReason.MALFORMED, null, evictedThroughElapsedMillis)),
            )
        }

        val ordered = samples
        val elements = mutableListOf<BatterySeriesElement>()

        if (evictedThroughElapsedMillis != null) {
            elements += SeriesGap(
                SeriesGapReason.NOT_RETAINED,
                fromElapsedMillis = null,
                toElapsedMillis = ordered.first().elapsedRealtimeMillis,
            )
        }

        val tolerance = cadenceMillis * CADENCE_TOLERANCE_FACTOR
        var current = mutableListOf(ordered.first())

        for (i in 1 until ordered.size) {
            val previous = ordered[i - 1]
            val next = ordered[i]
            val reason = breakBetween(previous, next, tolerance)
            if (reason == null) {
                current += next
            } else {
                elements += BatterySegment(current.toList())
                elements += SeriesGap(
                    reason,
                    previous.elapsedRealtimeMillis,
                    next.elapsedRealtimeMillis,
                )
                current = mutableListOf(next)
            }
        }
        elements += BatterySegment(current.toList())
        return BatterySeries(sessionId, elements)
    }

    /**
     * Why these two samples may not be joined, or null if they may.
     *
     * Boot identity is asked **first**, through [BootIdentity.relationTo] and nothing else.
     * There is exactly one boot-comparison implementation in this codebase; a second one that
     * compared the stored fields directly would be one equal-derived-value away from claiming
     * continuity across a reboot, because two identical `Derived` estimates are still
     * `UNKNOWN`.
     */
    private fun breakBetween(
        previous: BatterySeriesPoint,
        next: BatterySeriesPoint,
        toleranceMillis: Long,
    ): SeriesGapReason? {
        when (previous.bootIdentity.relationTo(next.bootIdentity)) {
            BootRelation.DIFFERENT -> return SeriesGapReason.DIFFERENT_BOOT
            BootRelation.UNKNOWN -> return SeriesGapReason.CONTINUITY_UNPROVEN
            BootRelation.SAME -> Unit
        }

        // Within one boot the monotonic clock cannot run backwards. If it did, the stored
        // state contradicts itself and no interval between these two means anything.
        if (next.elapsedRealtimeMillis < previous.elapsedRealtimeMillis) {
            return SeriesGapReason.MALFORMED
        }

        // A sample that announces itself as a fresh start is evidence the process died. Asked
        // before the spacing test so the more specific reason wins: "BattInsight restarted" is
        // a better answer than "nobody was sampling", and both are true.
        if (next.trigger == SessionTrigger.APP_START) return SeriesGapReason.PROCESS_RESTART

        if (next.elapsedRealtimeMillis - previous.elapsedRealtimeMillis > toleranceMillis) {
            return SeriesGapReason.NOT_OBSERVED
        }

        // Deliberately not a break: a wall-clock jump with no elapsed jump is a clock
        // correction -- a timezone change, an NTP step. It changes the labels on the axis and
        // nothing about whether time passed.
        return null
    }
}

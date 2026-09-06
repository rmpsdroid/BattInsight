package com.rmpsdroid.battinsight.chart

import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.series.SeriesGapReason

/**
 * What the renderer is told to draw, in normalised coordinates.
 *
 * ## Why this layer exists
 *
 * "No line crosses a gap" has to be provable, and a Canvas is a terrible place to prove
 * anything: assertions become pixel comparisons that break on anti-aliasing and tell you
 * nothing about intent. So the mapping from chart model to drawing instructions stops here, in
 * plain data, where a test can simply ask *how many* line strips there are and *which* points
 * each one spans.
 *
 * Coordinates are normalised to 0..1 with x running left to right and **y running bottom to
 * top** — 0 is 0%, 1 is 100%. The Compose layer flips y for screen space, which is the one
 * transformation it is trusted with.
 *
 * The renderer that consumes these may scale, stroke and label. It may not decide whether two
 * observations connect: that decision arrived here already made, and there is no primitive
 * capable of expressing it differently.
 */
sealed interface RenderPrimitive

/** A run of connected observations. One per chart segment, never one per chart. */
data class LineStrip(val points: List<NormalisedPoint>) : RenderPrimitive {
    init { require(points.size >= 2) { "a strip of one point is a marker, not a line" } }
}

/** A lone observation, or an endpoint worth marking. */
data class PointMarker(val point: NormalisedPoint) : RenderPrimitive

/**
 * An interval that must not be crossed.
 *
 * Carries a label because a gap that is only a visual absence is invisible to a screen reader
 * and ambiguous to everyone else.
 */
data class GapMarker(
    val startX: Float,
    val endX: Float,
    val reason: SeriesGapReason,
    val label: String,
) : RenderPrimitive

/** A measured counter interval. [height] is 0f for a measured zero, which is a real answer. */
data class IntervalBar(
    val startX: Float,
    val endX: Float,
    val height: Float,
    val isMeasuredZero: Boolean,
) : RenderPrimitive

/**
 * A counter interval that could not be measured.
 *
 * A separate type from [IntervalBar] rather than a bar with `height = 0f`, so that "refused"
 * and "measured zero" cannot be confused by the renderer, by a test, or by a future edit.
 */
data class RefusedInterval(
    val startX: Float,
    val endX: Float,
    val reason: CounterDeltaReason,
    val label: String,
) : RenderPrimitive

/** A point in normalised chart space. */
data class NormalisedPoint(
    val x: Float,
    val y: Float,
    /** Kept so a renderer can label or hit-test without recomputing from pixels. */
    val elapsedMillis: Long,
    val percent: Int,
)

/**
 * Turns chart models into drawing instructions.
 *
 * Pure and deterministic: same model in, same primitives out, with no clock, no theme and no
 * Compose. That is what lets the interesting properties be asserted on the JVM.
 */
object RenderPlanner {

    /**
     * Battery primitives.
     *
     * Three rules do all the work:
     *
     *  - one [LineStrip] per segment with two or more plottable points, so geometry can never
     *    span a gap;
     *  - a [PointMarker] where a segment has a single plottable point, because a line would
     *    have to lead somewhere untrue;
     *  - a [GapMarker] for every gap, so the break is stated rather than merely left blank.
     *
     * Observations with no percentage are skipped for geometry — they have no y — but they are
     * *not* dropped from the model, and they never become zero.
     */
    fun battery(model: BatteryChartModel): List<RenderPrimitive> {
        if (model.isEmpty) return emptyList()
        val primitives = mutableListOf<RenderPrimitive>()

        for (element in model.elements) {
            when (element) {
                is BatteryChartSegment -> {
                    val plottable = element.plottablePoints.map { model.normalise(it) }
                    when {
                        plottable.isEmpty() -> Unit
                        plottable.size == 1 -> primitives += PointMarker(plottable.single())
                        else -> primitives += LineStrip(plottable)
                    }
                }
                is BatteryChartGap -> primitives += GapMarker(
                    // A leading retention gap has no observed start, so it is anchored at the
                    // left edge: the interval it describes really does reach back past
                    // everything retained.
                    startX = element.fromElapsedMillis?.let { model.xOf(it) } ?: 0f,
                    endX = element.toElapsedMillis?.let { model.xOf(it) } ?: 1f,
                    reason = element.reason,
                    label = element.label,
                )
            }
        }
        return primitives
    }

    /**
     * Counter primitives.
     *
     * Bars are scaled against the tallest measured magnitude in the same family, so the two
     * families are never compared against a shared scale they do not share units with.
     *
     * Linear, not logarithmic. Wakelock durations are skewed and a log scale would make small
     * values look substantial; if one is ever offered it has to be labelled, not slipped in.
     */
    fun counters(model: CounterChartModel): List<RenderPrimitive> {
        if (model.isEmpty) return emptyList()
        val max = model.maxMagnitudeMillis ?: 0L

        return model.intervals.map { interval ->
            when (interval) {
                is CounterChartInterval.Comparable -> IntervalBar(
                    startX = model.xOf(interval.fromElapsedMillis),
                    endX = model.xOf(interval.toElapsedMillis),
                    // A measured zero is a zero-height bar and says so. When every interval is
                    // zero there is no scale, and inventing one would magnify nothing into
                    // something.
                    height = if (max > 0L) {
                        interval.totalDurationMillis.toFloat() / max.toFloat()
                    } else {
                        0f
                    },
                    isMeasuredZero = interval.isMeasuredZero,
                )
                is CounterChartInterval.Refused -> RefusedInterval(
                    startX = model.xOf(interval.fromElapsedMillis),
                    endX = model.xOf(interval.toElapsedMillis),
                    reason = interval.reason,
                    label = interval.label,
                )
            }
        }
    }

    private fun BatteryChartModel.normalise(point: BatteryChartPoint) = NormalisedPoint(
        x = xOf(point.elapsedRealtimeMillis),
        // Fixed 0-100 domain, not fitted to the data: a five-point drift must look like a
        // five-point drift.
        y = ((point.percent!! - viewport.yMin).toFloat() / viewport.ySpan.toFloat())
            .coerceIn(0f, 1f),
        elapsedMillis = point.elapsedRealtimeMillis,
        percent = point.percent,
    )

    /**
     * Elapsed realtime, never wall clock, and never an equal share per point.
     *
     * Observations at 10, 15 and 60 minutes land at their real distances, so the picture shows
     * where the evidence actually is. Wall clock is display only -- a clock correction must not
     * move a point.
     */
    private fun BatteryChartModel.xOf(elapsed: Long): Float = viewport.xOf(elapsed)

    private fun CounterChartModel.xOf(elapsed: Long): Float = viewport.xOf(elapsed)
}

/** Position of an elapsed time within the viewport, 0f..1f. */
fun ChartViewport.xOf(elapsedMillis: Long): Float =
    ((elapsedMillis - xMinMillis).toFloat() / xSpanMillis.toFloat()).coerceIn(0f, 1f)

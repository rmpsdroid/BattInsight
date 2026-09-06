package com.rmpsdroid.battinsight.chart

import com.rmpsdroid.battinsight.series.BatterySegment
import com.rmpsdroid.battinsight.series.BatterySeries
import com.rmpsdroid.battinsight.series.BatterySeriesPoint
import com.rmpsdroid.battinsight.series.SeriesGap
import com.rmpsdroid.battinsight.series.SeriesGapReason
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.PlugSource

/**
 * What a battery chart is allowed to draw, decided before anything touches a Canvas.
 *
 * ## The renderer does not decide connectivity
 *
 * `BatterySeriesBuilder` already worked out which observations may be joined and which may
 * not, using boot identity, elapsed spacing and the retention watermark. This model carries
 * that decision forward unchanged: one [BatteryChartSegment] per run of connected evidence,
 * one [BatteryChartGap] per interval that must not be crossed.
 *
 * Nothing downstream is given the raw point list, because a renderer holding a flat list of
 * points has no way *not* to connect them -- every line-chart API joins consecutive points by
 * default. Handing it segments makes the truthful rendering the only one expressible.
 *
 * Pure: no Compose, no Room, no Android. The geometry is semantic (elapsed milliseconds and
 * percent), never pixels.
 */
data class BatteryChartModel(
    val sessionId: String,
    val segments: List<BatteryChartSegment>,
    val gaps: List<BatteryChartGap>,
    /** Segments and gaps interleaved in elapsed order, as the series produced them. */
    val elements: List<BatteryChartElement>,
    val viewport: ChartViewport,
    val summary: BatteryChartSummary,
) {
    /** True when the session has no sampled observations at all. */
    val isEmpty: Boolean get() = elements.isEmpty()

    /**
     * True when observations exist but none carries a usable percentage.
     *
     * Distinct from [isEmpty], and the distinction is the point: "nothing was sampled" and
     * "samples exist but the platform never reported a level" are different facts, and neither
     * of them is 0%.
     */
    val hasNoUsablePercentage: Boolean
        get() = !isEmpty && segments.all { seg -> seg.points.none { it.percent != null } }

    /** True when there is not enough connected evidence to draw a trend. */
    val hasNoConnectedEvidence: Boolean
        get() = segments.none { seg -> seg.drawableRuns.any { it.size >= 2 } }
}

sealed interface BatteryChartElement

/**
 * A run of observations that may be drawn as one connected path.
 *
 * A single-point segment is legal and means "one reading here". It renders as a marker; a line
 * would have to go somewhere, and there is nowhere true for it to go.
 */
data class BatteryChartSegment(val points: List<BatteryChartPoint>) : BatteryChartElement {
    init { require(points.isNotEmpty()) { "a segment with no points is a gap" } }

    /** Points that can actually be plotted. A point with no percentage has no y position. */
    val plottablePoints: List<BatteryChartPoint> get() = points.filter { it.percent != null }

    /**
     * The segment split into maximal runs of consecutive **plottable** points.
     *
     * This is the correction Phase 9C.1 exists for. Phase 9B decides whether two observations
     * may be *joined in time* -- same boot, close enough in elapsed realtime, no process death
     * between them -- and for `80 / unavailable / 78` the answer is legitimately yes: all three
     * readings were taken, and nothing about the timeline is broken.
     *
     * But a battery *line* asserts something narrower than temporal continuity. It asserts that
     * the level went from one value to the other. Filtering the unavailable reading out and
     * drawing 80 → 78 through its position would be exactly that claim, made about a moment
     * where the platform reported no level at all. Measured before the fix, that is precisely
     * what happened: one strip, `[80, 78]`, spanning x 0.0 to 1.0.
     *
     * So an unavailable value **terminates the drawable run** without being a temporal gap. The
     * observation is not missing -- only its level is, and those are different facts with
     * different copy.
     */
    val drawableRuns: List<List<BatteryChartPoint>>
        get() {
            val runs = mutableListOf<List<BatteryChartPoint>>()
            var current = mutableListOf<BatteryChartPoint>()
            for (point in points) {
                if (point.percent != null) {
                    current += point
                } else if (current.isNotEmpty()) {
                    runs += current.toList()
                    current = mutableListOf()
                }
            }
            if (current.isNotEmpty()) runs += current.toList()
            return runs
        }

    /**
     * Runs of consecutive observations whose level was unavailable.
     *
     * Consecutive nulls collapse into one run so the marker is deterministic: `80, null, null,
     * 77` produces one unavailable region rather than two abutting ones.
     */
    val unavailableRuns: List<List<BatteryChartPoint>>
        get() {
            val runs = mutableListOf<List<BatteryChartPoint>>()
            var current = mutableListOf<BatteryChartPoint>()
            for (point in points) {
                if (point.percent == null) {
                    current += point
                } else if (current.isNotEmpty()) {
                    runs += current.toList()
                    current = mutableListOf()
                }
            }
            if (current.isNotEmpty()) runs += current.toList()
            return runs
        }
}

/**
 * An interval the chart must not draw across, carrying copy rather than an enum name.
 *
 * `fromElapsedMillis` is null for a leading retention gap: the observations that would have
 * defined its start are the ones retention deleted.
 */
data class BatteryChartGap(
    val reason: SeriesGapReason,
    val fromElapsedMillis: Long?,
    val toElapsedMillis: Long?,
    /** Short label for a marker. */
    val label: String,
    /** Full sentence for screen readers and the text fallback. */
    val description: String,
) : BatteryChartElement

/** One observation, with its display metadata already resolved. */
data class BatteryChartPoint(
    val elapsedRealtimeMillis: Long,
    /**
     * Percentage, or null when the platform did not report enough to compute one.
     *
     * Null is carried all the way to the renderer rather than being defaulted anywhere. On a
     * battery chart a zero reads as "empty", which is the single most damaging thing this
     * model could get wrong.
     */
    val percent: Int?,
    val wallClockMillis: Long,
    val utcOffsetMinutes: Int,
    val status: BatteryStatus,
    val plug: PlugSource,
)

/**
 * The chart's coordinate space, in domain units.
 *
 * The y range is **fixed at 0–100** rather than fitted to the data. Battery percentage has a
 * real semantic scale, and autoscaling 47%–52% to the full height would turn a five-point
 * drift into a cliff. The x range is the retained elapsed extent, so real spacing survives:
 * three observations at 10, 15 and 60 minutes are not drawn evenly.
 */
data class ChartViewport(
    val xMinMillis: Long,
    val xMaxMillis: Long,
    val yMin: Int = 0,
    val yMax: Int = 100,
) {
    val xSpanMillis: Long get() = (xMaxMillis - xMinMillis).coerceAtLeast(1L)
    val ySpan: Int get() = (yMax - yMin).coerceAtLeast(1)
}

/**
 * The chart in words.
 *
 * Computed from the same model the drawing uses, so the text and the picture cannot disagree.
 * A Canvas is invisible to a screen reader, so this is not a nicety -- it is the accessible
 * rendering of the same facts.
 */
data class BatteryChartSummary(
    val observationCount: Int,
    val connectedSegmentCount: Int,
    val gapCount: Int,
    val observedSpanMillis: Long,
    val firstPercent: Int?,
    val lastPercent: Int?,
    val gapDescriptions: List<String>,
    /**
     * Readings that were taken but carried no battery level.
     *
     * Counted separately from [gapCount] on purpose. A gap means nobody was watching; this
     * means somebody was, and the platform did not report a level. Folding these into the gap
     * count would tell the user their device went unobserved when it did not.
     */
    val unavailableValueCount: Int = 0,
)

/** Builds a [BatteryChartModel] from the domain series. Pure. */
object BatteryChartMapper {

    fun map(series: BatterySeries): BatteryChartModel {
        val elements = series.elements.map { element ->
            when (element) {
                is BatterySegment -> BatteryChartSegment(element.points.map { it.toChartPoint() })
                is SeriesGap -> element.toChartGap()
            }
        }
        val segments = elements.filterIsInstance<BatteryChartSegment>()
        val gaps = elements.filterIsInstance<BatteryChartGap>()

        val allPoints = segments.flatMap { it.points }
        val plottable = allPoints.filter { it.percent != null }

        val viewport = ChartViewport(
            xMinMillis = allPoints.minOfOrNull { it.elapsedRealtimeMillis } ?: 0L,
            xMaxMillis = allPoints.maxOfOrNull { it.elapsedRealtimeMillis } ?: 0L,
        )

        return BatteryChartModel(
            sessionId = series.sessionId,
            segments = segments,
            gaps = gaps,
            elements = elements,
            viewport = viewport,
            summary = BatteryChartSummary(
                observationCount = allPoints.size,
                // Only runs of two or more plottable points are a *trend*; a lone marker is an
                // observation, and counting it as a segment would overstate the evidence.
                connectedSegmentCount = segments.sumOf { seg ->
                    seg.drawableRuns.count { it.size >= 2 }
                },
                gapCount = gaps.size,
                observedSpanMillis = viewport.xMaxMillis - viewport.xMinMillis,
                firstPercent = plottable.firstOrNull()?.percent,
                lastPercent = plottable.lastOrNull()?.percent,
                gapDescriptions = gaps.map { it.description },
                unavailableValueCount = allPoints.count { it.percent == null },
            ),
        )
    }

    private fun BatterySeriesPoint.toChartPoint() = BatteryChartPoint(
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        // The domain already refuses to compute a percentage from a missing level or an
        // unusable scale. Nothing here second-guesses it.
        percent = percent,
        wallClockMillis = wallClockMillis,
        utcOffsetMinutes = utcOffsetMinutes,
        status = status,
        plug = plug,
    )

    private fun SeriesGap.toChartGap() = BatteryChartGap(
        reason = reason,
        fromElapsedMillis = fromElapsedMillis,
        toElapsedMillis = toElapsedMillis,
        label = GapCopy.label(reason),
        description = GapCopy.description(reason),
    )
}

/**
 * Plain language for every gap reason.
 *
 * Exhaustive `when` with no `else`, following the pattern Phase 8 established: a reason added
 * later fails to compile rather than reaching a user as `NOT_OBSERVED`.
 *
 * The six reasons say genuinely different things and are not collapsed. "Your device
 * restarted", "BattInsight restarted", and "we did not keep that far back" are three different
 * explanations, and only the first two are about the device at all.
 */
object GapCopy {

    fun label(reason: SeriesGapReason): String = when (reason) {
        SeriesGapReason.NOT_OBSERVED -> "Not observed"
        SeriesGapReason.PROCESS_RESTART -> "App restarted"
        SeriesGapReason.DIFFERENT_BOOT -> "Device restarted"
        SeriesGapReason.CONTINUITY_UNPROVEN -> "Unverified"
        SeriesGapReason.NOT_RETAINED -> "Not kept"
        SeriesGapReason.MALFORMED -> "Inconsistent"
    }

    fun description(reason: SeriesGapReason): String = when (reason) {
        SeriesGapReason.NOT_OBSERVED ->
            "No readings were taken during this period, so nothing is known about it. " +
                "BattInsight samples only while it is open and on screen."
        SeriesGapReason.PROCESS_RESTART ->
            "BattInsight stopped running and started again. Nothing was recorded in between."
        SeriesGapReason.DIFFERENT_BOOT ->
            "The device restarted here. Readings from before and after a restart cannot be " +
                "placed on the same timeline."
        SeriesGapReason.CONTINUITY_UNPROVEN ->
            "It could not be confirmed whether the device restarted during this period, so " +
                "these readings are not joined."
        SeriesGapReason.NOT_RETAINED ->
            "Earlier readings from this period were removed to limit how much is stored. " +
                "They were taken, but they are no longer kept."
        SeriesGapReason.MALFORMED ->
            "The stored readings for this period disagree about their own order, so they " +
                "cannot be shown as a sequence."
    }
}

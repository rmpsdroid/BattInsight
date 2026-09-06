package com.rmpsdroid.battinsight.chart

import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.series.CounterInterval
import com.rmpsdroid.battinsight.series.CounterSeries

/**
 * What a counter chart is allowed to draw.
 *
 * ## Why intervals and not a line
 *
 * The underlying evidence is the difference between two cumulative counters read at two
 * moments the user chose. It says "this much accumulated somewhere between these two
 * captures". It does **not** say when within that window, and it says nothing at all about the
 * shape of activity inside it.
 *
 * A line through one point per capture would claim continuous sampling that never happened, so
 * this model produces **interval blocks**: each one spans the real window it measured, and its
 * width is that window rather than an equal share of the chart.
 *
 * ## Refused is not zero
 *
 * A [CounterChartInterval.Refused] carries no magnitude at all -- not zero, not negative. A
 * zero-height bar would be indistinguishable from a measured zero, and those are opposite
 * facts: one says "nothing accumulated", the other says "we cannot tell you what accumulated".
 */
data class CounterChartModel(
    val sessionId: String,
    val family: CounterFamily,
    val intervals: List<CounterChartInterval>,
    val viewport: ChartViewport,
    val summary: CounterChartSummary,
) {
    val comparable: List<CounterChartInterval.Comparable>
        get() = intervals.filterIsInstance<CounterChartInterval.Comparable>()

    val refused: List<CounterChartInterval.Refused>
        get() = intervals.filterIsInstance<CounterChartInterval.Refused>()

    val isEmpty: Boolean get() = intervals.isEmpty()

    /** The tallest measured magnitude, used to scale bars. Null when nothing is measurable. */
    val maxMagnitudeMillis: Long? get() = comparable.maxOfOrNull { it.totalDurationMillis }
}

/**
 * Kernel and application wakelocks are never mixed.
 *
 * They mean different things -- one is the kernel's own accounting, the other is attributed to
 * a UID -- and adding their durations together would produce a number with no referent.
 */
enum class CounterFamily { KERNEL, APPLICATION }

sealed interface CounterChartInterval {
    val fromElapsedMillis: Long
    val toElapsedMillis: Long

    val spanMillis: Long get() = toElapsedMillis - fromElapsedMillis

    /**
     * A window whose counters could be subtracted.
     *
     * [totalDurationMillis] may legitimately be zero. That is a **measurement**, and
     * [isMeasuredZero] exists so the renderer and the accessible text can say so rather than
     * letting an absent bar imply absent data.
     */
    data class Comparable(
        override val fromElapsedMillis: Long,
        override val toElapsedMillis: Long,
        val totalDurationMillis: Long,
        val totalCount: Long,
        val contributors: List<CounterContributor>,
    ) : CounterChartInterval {
        val isMeasuredZero: Boolean get() = totalDurationMillis == 0L && totalCount == 0L
    }

    /**
     * A window whose counters must not be subtracted, and why.
     *
     * Deliberately carries no magnitude field at all. There is no number to draw, and a type
     * that cannot express one cannot accidentally draw a zero.
     */
    data class Refused(
        override val fromElapsedMillis: Long,
        override val toElapsedMillis: Long,
        val reason: CounterDeltaReason,
        val label: String,
        val description: String,
    ) : CounterChartInterval
}

/**
 * One wakelock's contribution to a measured interval.
 *
 * The UID is authoritative. A package name resolved today says what runs under that UID *now*,
 * which is not evidence about what ran under it when the capture was taken -- Phase 8 settled
 * that, and this carries no package name at all.
 */
data class CounterContributor(
    val uid: Int?,
    val name: String,
    val durationDeltaMillis: Long,
    val countDelta: Long,
)

data class CounterChartSummary(
    val intervalCount: Int,
    val comparableCount: Int,
    val refusedCount: Int,
    val measuredZeroCount: Int,
    val totalMeasuredDurationMillis: Long,
    val refusedDescriptions: List<String>,
)

/** Builds a [CounterChartModel] from the domain series. Pure. */
object CounterChartMapper {

    /** How many contributors a single interval lists. Same top-five convention as Phase 8. */
    const val TOP_CONTRIBUTORS = 5

    fun map(
        series: CounterSeries,
        family: CounterFamily,
        refusalCopy: (CounterDeltaReason) -> String,
    ): CounterChartModel {
        val intervals = series.intervals.map { interval ->
            when (interval) {
                is CounterInterval.Comparable -> interval.toChartInterval(family)
                is CounterInterval.Refused -> CounterChartInterval.Refused(
                    fromElapsedMillis = interval.fromElapsedMillis,
                    toElapsedMillis = interval.toElapsedMillis,
                    reason = interval.reason,
                    label = "Not comparable",
                    // The engine's own explanation, not a second one invented here.
                    description = refusalCopy(interval.reason),
                )
            }
        }

        val comparable = intervals.filterIsInstance<CounterChartInterval.Comparable>()
        return CounterChartModel(
            sessionId = series.sessionId,
            family = family,
            intervals = intervals,
            viewport = ChartViewport(
                xMinMillis = intervals.minOfOrNull { it.fromElapsedMillis } ?: 0L,
                xMaxMillis = intervals.maxOfOrNull { it.toElapsedMillis } ?: 0L,
                yMin = 0,
                yMax = 100,
            ),
            summary = CounterChartSummary(
                intervalCount = intervals.size,
                comparableCount = comparable.size,
                refusedCount = intervals.count { it is CounterChartInterval.Refused },
                measuredZeroCount = comparable.count { it.isMeasuredZero },
                totalMeasuredDurationMillis = comparable.sumOf { it.totalDurationMillis },
                refusedDescriptions = intervals
                    .filterIsInstance<CounterChartInterval.Refused>()
                    .map { it.description },
            ),
        )
    }

    private fun CounterInterval.Comparable.toChartInterval(
        family: CounterFamily,
    ): CounterChartInterval.Comparable {
        val contributors = when (family) {
            CounterFamily.KERNEL -> kernelDeltas.map {
                CounterContributor(null, it.name, it.durationDeltaMillis, it.countDelta)
            }
            CounterFamily.APPLICATION -> partialDeltas.map {
                CounterContributor(it.uid, it.name, it.durationDeltaMillis, it.countDelta)
            }
        }
        return CounterChartInterval.Comparable(
            fromElapsedMillis = fromElapsedMillis,
            toElapsedMillis = toElapsedMillis,
            totalDurationMillis = contributors.sumOf { it.durationDeltaMillis },
            totalCount = contributors.sumOf { it.countDelta },
            contributors = contributors.rankedTop(),
        )
    }

    /**
     * Deterministic ranking: duration, then count, then identity.
     *
     * The name tiebreak is the same one Phase 8 uses, and for the same reason -- without it two
     * contributors with identical figures could swap between recompositions and look like data
     * changing when nothing did.
     */
    private fun List<CounterContributor>.rankedTop(): List<CounterContributor> =
        sortedWith(
            compareByDescending<CounterContributor> { it.durationDeltaMillis }
                .thenByDescending { it.countDelta }
                .thenBy { it.uid ?: -1 }
                .thenBy { it.name },
        ).take(TOP_CONTRIBUTORS)
}

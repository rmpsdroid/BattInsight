package com.rmpsdroid.battinsight.chart

import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.series.BatterySampleStore
import com.rmpsdroid.battinsight.series.CounterSeriesBuilder

/**
 * Reads a session's stored series and turns it into chart models.
 *
 * ## The boundary this defends
 *
 * Composables get [SessionCharts] and nothing else. They never see a DAO, an entity, or an
 * unordered list of samples — because a renderer holding raw points has no way *not* to connect
 * them, and the whole phase rests on some points not being connected.
 *
 * ## No privilege required
 *
 * Everything here reads BattInsight's own database. A stored chart opens with Shizuku absent,
 * with no permission ever granted, and with the chosen access route broken — the same guarantee
 * Phase 8 made for the history list. Only a live capture needs a backend, and nothing on this
 * path attempts one.
 */
class SessionChartLoader(
    private val sampleStore: BatterySampleStore,
    private val counterCaptures: suspend (String) -> List<com.rmpsdroid.battinsight.batterystats.StoredCounterCapture>,
    private val refusalCopy: (CounterDeltaReason) -> String,
) {

    suspend fun load(sessionId: String): SessionCharts {
        val batterySeries = sampleStore.seriesFor(sessionId)
        val captures = counterCaptures(sessionId)
        val counterSeries = CounterSeriesBuilder.build(sessionId, captures)

        return SessionCharts(
            battery = BatteryChartMapper.map(batterySeries),
            // Both families are mapped from the same intervals; only the contributor list and
            // the totals differ, because kernel and application wakelock time are different
            // measurements and must never be summed together.
            kernel = CounterChartMapper.map(counterSeries, CounterFamily.KERNEL, refusalCopy),
            application = CounterChartMapper.map(counterSeries, CounterFamily.APPLICATION, refusalCopy),
        )
    }
}

/** Everything the detail screen draws, resolved in one read. */
data class SessionCharts(
    val battery: BatteryChartModel,
    val kernel: CounterChartModel,
    val application: CounterChartModel,
)

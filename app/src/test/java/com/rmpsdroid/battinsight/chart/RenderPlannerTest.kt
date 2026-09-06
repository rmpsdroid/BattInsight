package com.rmpsdroid.battinsight.chart

import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.series.BatterySeriesBuilder
import com.rmpsdroid.battinsight.series.BatterySeriesPoint
import com.rmpsdroid.battinsight.series.SeriesGapReason
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawing instructions themselves.
 *
 * This is where "no line crosses a gap" stops being a claim and becomes a property that can be
 * checked: the planner emits plain data, so a test can count the strips and inspect exactly
 * which observations each one spans. Asserting the same thing against a Canvas would mean
 * comparing anti-aliased pixels, which is fragile and proves nothing about intent.
 */
class RenderPlannerTest {

    private val cadence = 5L * 60_000L

    // ------------------------------------------------------- geometry never crosses a gap

    @Test
    fun `separate segments become separate line strips`() {
        val primitives = plan(
            point(0, 80), point(cadence, 79),
            point(cadence * 12, 60), point(cadence * 13, 59),
        )

        val strips = primitives.filterIsInstance<LineStrip>()
        assertEquals("one strip per connected run", 2, strips.size)
        assertEquals(2, strips[0].points.size)
        assertEquals(2, strips[1].points.size)
    }

    @Test
    fun `no primitive spans the interval a gap covers`() {
        // The load-bearing assertion of the whole phase. Every strip must lie entirely on one
        // side of every gap: if any strip straddled one, the chart would be drawing a
        // trajectory across an interval nobody observed.
        val primitives = plan(
            point(0, 80), point(cadence, 79),
            point(cadence * 12, 60), point(cadence * 13, 59),
        )
        val gap = primitives.filterIsInstance<GapMarker>().single()

        for (strip in primitives.filterIsInstance<LineStrip>()) {
            val first = strip.points.first().x
            val last = strip.points.last().x
            assertTrue(
                "a strip spans the gap ${gap.startX}..${gap.endX}: $first..$last",
                last <= gap.startX + TOLERANCE || first >= gap.endX - TOLERANCE,
            )
        }
    }

    @Test
    fun `a process restart leaves two strips with a marker between them`() {
        val primitives = plan(
            point(0, 80), point(cadence, 79),
            point(cadence * 12, 60, trigger = SessionTrigger.APP_START), point(cadence * 13, 59),
        )

        assertEquals(2, primitives.filterIsInstance<LineStrip>().size)
        assertEquals(
            SeriesGapReason.PROCESS_RESTART,
            primitives.filterIsInstance<GapMarker>().single().reason,
        )
    }

    @Test
    fun `a boot boundary produces no connecting geometry`() {
        val primitives = plan(
            point(0, 80), point(cadence, 79),
            point(cadence * 2, 78, boot = BootIdentity.Kernel("b")),
            point(cadence * 3, 77, boot = BootIdentity.Kernel("b")),
        )

        assertEquals(2, primitives.filterIsInstance<LineStrip>().size)
        assertEquals(
            SeriesGapReason.DIFFERENT_BOOT,
            primitives.filterIsInstance<GapMarker>().single().reason,
        )
    }

    @Test
    fun `equal derived boots produce no connecting geometry either`() {
        val derived = BootIdentity.Derived(1_700_000_000_000L)
        val primitives = plan(
            point(0, 80, boot = derived), point(cadence, 79, boot = derived),
        )

        assertTrue("two lone points, not a line", primitives.filterIsInstance<LineStrip>().isEmpty())
        assertEquals(2, primitives.filterIsInstance<PointMarker>().size)
        assertEquals(
            SeriesGapReason.CONTINUITY_UNPROVEN,
            primitives.filterIsInstance<GapMarker>().single().reason,
        )
    }

    // --------------------------------------------------------------------- lone points

    @Test
    fun `a one-point segment becomes a marker, never a line`() {
        val primitives = plan(point(0, 80))

        assertEquals(1, primitives.filterIsInstance<PointMarker>().size)
        assertTrue(primitives.filterIsInstance<LineStrip>().isEmpty())
    }

    @Test
    fun `a segment whose only plottable point is one becomes a marker`() {
        // Two observations, one with no reported level: there is a single plottable point, so
        // there is nothing to draw a line between.
        val primitives = plan(point(0, 80), point(cadence, null))

        assertEquals(1, primitives.filterIsInstance<PointMarker>().size)
        assertTrue(primitives.filterIsInstance<LineStrip>().isEmpty())
    }

    @Test
    fun `a segment with no plottable point produces no geometry at all`() {
        val primitives = plan(point(0, null), point(cadence, null))

        assertTrue(primitives.filterIsInstance<LineStrip>().isEmpty())
        assertTrue("a missing level is not a point at zero", primitives.filterIsInstance<PointMarker>().isEmpty())
    }

    // ------------------------------------------------------------------------ positions

    @Test
    fun `y follows the fixed percentage scale`() {
        val primitives = plan(point(0, 100), point(cadence, 0), point(cadence * 2, 50))
        val strip = primitives.filterIsInstance<LineStrip>().single()

        assertEquals(1f, strip.points[0].y, TOLERANCE)
        assertEquals(0f, strip.points[1].y, TOLERANCE)
        assertEquals(0.5f, strip.points[2].y, TOLERANCE)
    }

    @Test
    fun `x follows elapsed time, not point index`() {
        // Equal spacing would put the middle point at 0.5. Real elapsed spacing puts it much
        // earlier, because it happened much earlier.
        //
        // All three stay inside the cadence tolerance on purpose: the point under test is
        // where x lands, so they must remain one connected strip. Spread them further and the
        // series correctly splits into segments, which is a different test.
        val primitives = plan(
            point(0, 80),
            point(60_000L, 79),
            point(10 * 60_000L, 60),
        )
        val strip = primitives.filterIsInstance<LineStrip>().single()

        assertEquals(0f, strip.points[0].x, TOLERANCE)
        assertEquals(0.1f, strip.points[1].x, TOLERANCE)
        assertEquals(1f, strip.points[2].x, TOLERANCE)
        assertTrue("equal spacing would have placed it at 0.5", strip.points[1].x < 0.2f)
    }

    @Test
    fun `a leading retention gap is anchored at the left edge`() {
        val model = BatteryChartMapper.map(
            BatterySeriesBuilder.build(
                SESSION,
                listOf(point(cadence * 20, 60), point(cadence * 21, 59)),
                cadence,
                evictedThroughElapsedMillis = cadence * 19,
            ),
        )
        val gap = RenderPlanner.battery(model).filterIsInstance<GapMarker>().single()

        assertEquals(SeriesGapReason.NOT_RETAINED, gap.reason)
        assertEquals("it reaches back past everything retained", 0f, gap.startX, TOLERANCE)
    }

    @Test
    fun `an empty model produces nothing to draw`() {
        assertTrue(RenderPlanner.battery(BatteryChartMapper.map(
            BatterySeriesBuilder.build(SESSION, emptyList(), cadence),
        )).isEmpty())
    }

    @Test
    fun `the planner is deterministic`() {
        val pts = listOf(point(0, 80), point(cadence, 79), point(cadence * 12, 60))
        val a = RenderPlanner.battery(BatteryChartMapper.map(BatterySeriesBuilder.build(SESSION, pts, cadence)))
        val b = RenderPlanner.battery(BatteryChartMapper.map(BatterySeriesBuilder.build(SESSION, pts, cadence)))

        assertEquals(a, b)
    }

    @Test
    fun `three hundred points produce one strip`() {
        val pts = (0 until 300).map { point(it * cadence, 100 - it / 3) }
        val primitives = RenderPlanner.battery(
            BatteryChartMapper.map(BatterySeriesBuilder.build(SESSION, pts, cadence)),
        )

        assertEquals(1, primitives.filterIsInstance<LineStrip>().size)
        assertEquals(300, primitives.filterIsInstance<LineStrip>().single().points.size)
    }

    // -------------------------------------------------------------------- counter bars

    @Test
    fun `a measured interval becomes a bar spanning its real window`() {
        val model = counterModel(
            comparable(0, 60_000, durationMillis = 1_000),
        )
        val bar = RenderPlanner.counters(model).filterIsInstance<IntervalBar>().single()

        assertEquals(0f, bar.startX, TOLERANCE)
        assertEquals(1f, bar.endX, TOLERANCE)
        assertEquals(1f, bar.height, TOLERANCE)
        assertTrue(!bar.isMeasuredZero)
    }

    @Test
    fun `a measured zero is a zero-height bar that says it is a measurement`() {
        val model = counterModel(
            comparable(0, 60_000, durationMillis = 1_000),
            comparable(60_000, 120_000, durationMillis = 0, count = 0),
        )
        val bars = RenderPlanner.counters(model).filterIsInstance<IntervalBar>()

        assertEquals(2, bars.size)
        assertEquals(0f, bars[1].height, TOLERANCE)
        assertTrue("and it is flagged as measured, not absent", bars[1].isMeasuredZero)
    }

    @Test
    fun `a refused interval produces no bar at all`() {
        val model = counterModel(
            comparable(0, 60_000, durationMillis = 1_000),
            refused(60_000, 120_000),
        )
        val primitives = RenderPlanner.counters(model)

        assertEquals("only the comparable interval has a bar", 1, primitives.filterIsInstance<IntervalBar>().size)
        val refusedPrimitive = primitives.filterIsInstance<RefusedInterval>().single()
        assertEquals(CounterDeltaReason.COUNTER_DECREASED, refusedPrimitive.reason)
        // The type carries no height field, so a refusal cannot be drawn as a zero even by
        // accident -- which is the difference between "nothing accumulated" and "we cannot say".
        assertTrue(
            "a refusal must not be a zero bar",
            primitives.filterIsInstance<IntervalBar>().none { it.height == 0f },
        )
    }

    @Test
    fun `bars scale against the tallest measured magnitude`() {
        val model = counterModel(
            comparable(0, 60_000, durationMillis = 500),
            comparable(60_000, 120_000, durationMillis = 1_000),
        )
        val bars = RenderPlanner.counters(model).filterIsInstance<IntervalBar>()

        assertEquals(0.5f, bars[0].height, TOLERANCE)
        assertEquals(1f, bars[1].height, TOLERANCE)
    }

    @Test
    fun `when every interval is a measured zero no scale is invented`() {
        val model = counterModel(
            comparable(0, 60_000, durationMillis = 0, count = 0),
            comparable(60_000, 120_000, durationMillis = 0, count = 0),
        )
        val bars = RenderPlanner.counters(model).filterIsInstance<IntervalBar>()

        assertTrue("no bar is magnified out of nothing", bars.all { it.height == 0f })
        assertTrue(bars.all { it.isMeasuredZero })
    }

    @Test
    fun `interval width follows the real window, not an equal share`() {
        val model = counterModel(
            comparable(0, 10_000, durationMillis = 100),
            comparable(10_000, 100_000, durationMillis = 100),
        )
        val bars = RenderPlanner.counters(model).filterIsInstance<IntervalBar>()

        val first = bars[0].endX - bars[0].startX
        val second = bars[1].endX - bars[1].startX
        assertTrue("the nine-times-longer window is drawn wider", second > first * 5)
    }

    // --------------------------------------------------------------------------- helpers

    private fun plan(vararg points: BatterySeriesPoint) = RenderPlanner.battery(
        BatteryChartMapper.map(BatterySeriesBuilder.build(SESSION, points.toList(), cadence)),
    )

    private fun comparable(from: Long, to: Long, durationMillis: Long, count: Long = 1) =
        CounterChartInterval.Comparable(
            fromElapsedMillis = from,
            toElapsedMillis = to,
            totalDurationMillis = durationMillis,
            totalCount = count,
            contributors = emptyList(),
        )

    private fun refused(from: Long, to: Long) = CounterChartInterval.Refused(
        fromElapsedMillis = from,
        toElapsedMillis = to,
        reason = CounterDeltaReason.COUNTER_DECREASED,
        label = "Not comparable",
        description = "Android's counters restarted between these readings.",
    )

    private fun counterModel(vararg intervals: CounterChartInterval): CounterChartModel {
        val comparable = intervals.filterIsInstance<CounterChartInterval.Comparable>()
        return CounterChartModel(
            sessionId = SESSION,
            family = CounterFamily.KERNEL,
            intervals = intervals.toList(),
            viewport = ChartViewport(
                xMinMillis = intervals.minOf { it.fromElapsedMillis },
                xMaxMillis = intervals.maxOf { it.toElapsedMillis },
            ),
            summary = CounterChartSummary(
                intervalCount = intervals.size,
                comparableCount = comparable.size,
                refusedCount = intervals.size - comparable.size,
                measuredZeroCount = comparable.count { it.isMeasuredZero },
                totalMeasuredDurationMillis = comparable.sumOf { it.totalDurationMillis },
                refusedDescriptions = emptyList(),
            ),
        )
    }

    private fun point(
        elapsed: Long,
        percent: Int? = 80,
        boot: BootIdentity = BootIdentity.Kernel("boot-a"),
        trigger: SessionTrigger = SessionTrigger.PERIODIC,
    ) = BatterySeriesPoint(
        elapsedRealtimeMillis = elapsed,
        wallClockMillis = 1_700_000_000_000L + elapsed,
        utcOffsetMinutes = 330,
        bootIdentity = boot,
        level = percent,
        scale = 100,
        status = BatteryStatus.DISCHARGING,
        plug = PlugSource.NONE,
        temperatureDeciCelsius = 251,
        voltageMilliVolts = 4123,
        chargeCounterMicroAmpHours = 3_210_000L,
        trigger = trigger,
    )

    private companion object {
        const val SESSION = "00000000-0000-0000-0000-0000000000aa"
        const val TOLERANCE = 0.001f
    }
}

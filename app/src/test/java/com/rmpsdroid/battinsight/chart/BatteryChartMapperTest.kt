package com.rmpsdroid.battinsight.chart

import com.rmpsdroid.battinsight.series.BatterySeriesBuilder
import com.rmpsdroid.battinsight.series.BatterySeriesPoint
import com.rmpsdroid.battinsight.series.SeriesGapReason
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the chart is allowed to draw, and what it must refuse to.
 *
 * These run against the **real** `BatterySeriesBuilder`, not a hand-built model, so they prove
 * the whole path from stored observations to drawing instructions. A mapper tested against a
 * model someone typed by hand would only prove the mapper agrees with the test author.
 */
class BatteryChartMapperTest {

    private val cadence = 5L * 60_000L

    // ------------------------------------------------------------------ shape of the model

    @Test
    fun `an empty series produces an empty model, not a zeroed one`() {
        val model = map()

        assertTrue(model.isEmpty)
        assertEquals(0, model.summary.observationCount)
        assertEquals(0, model.summary.gapCount)
        assertNull("no observations means no starting percentage", model.summary.firstPercent)
    }

    @Test
    fun `a single observation is one point and no line`() {
        val model = map(point(0, 80))

        assertEquals(1, model.segments.size)
        assertEquals(1, model.segments.single().points.size)
        assertEquals("one point is not a trend", 0, model.summary.connectedSegmentCount)
        assertTrue(model.hasNoConnectedEvidence)
    }

    @Test
    fun `a connected run becomes one segment`() {
        val model = map(point(0, 80), point(cadence, 79), point(cadence * 2, 78))

        assertEquals(1, model.segments.size)
        assertEquals(3, model.segments.single().points.size)
        assertEquals(0, model.gaps.size)
        assertEquals(1, model.summary.connectedSegmentCount)
        assertEquals(80, model.summary.firstPercent)
        assertEquals(78, model.summary.lastPercent)
    }

    @Test
    fun `an interrupted run becomes two segments and a gap`() {
        val model = map(point(0, 80), point(cadence, 79), point(cadence * 12, 60))

        assertEquals(2, model.segments.size)
        assertEquals(1, model.gaps.size)
        assertEquals(SeriesGapReason.NOT_OBSERVED, model.gaps.single().reason)
    }

    // ---------------------------------------------------- every gap reason survives mapping

    @Test
    fun `a process restart is preserved`() {
        val model = map(point(0, 80), point(cadence * 12, 60, trigger = SessionTrigger.APP_START))

        assertEquals(SeriesGapReason.PROCESS_RESTART, model.gaps.single().reason)
        assertEquals("App restarted", model.gaps.single().label)
    }

    @Test
    fun `a different boot is preserved`() {
        val model = map(
            point(0, 80, boot = BootIdentity.Kernel("a")),
            point(cadence, 79, boot = BootIdentity.Kernel("b")),
        )

        assertEquals(SeriesGapReason.DIFFERENT_BOOT, model.gaps.single().reason)
        assertEquals(2, model.segments.size)
    }

    @Test
    fun `two equal derived boots stay unproven, not connected`() {
        // The case a hand-written boot comparison gets wrong. If presentation ever "helpfully"
        // joined these, it would undo the boot safety contract at the last possible moment.
        val derived = BootIdentity.Derived(1_700_000_000_000L)
        val model = map(point(0, 80, boot = derived), point(cadence, 79, boot = derived))

        assertEquals(SeriesGapReason.CONTINUITY_UNPROVEN, model.gaps.single().reason)
        assertEquals(2, model.segments.size)
    }

    @Test
    fun `a retention gap is preserved and leads the series`() {
        val model = BatteryChartMapper.map(
            BatterySeriesBuilder.build(
                SESSION,
                listOf(point(cadence * 20, 60), point(cadence * 21, 59)),
                cadence,
                evictedThroughElapsedMillis = cadence * 19,
            ),
        )

        assertEquals(SeriesGapReason.NOT_RETAINED, model.gaps.single().reason)
        assertTrue("the gap comes first", model.elements.first() is BatteryChartGap)
        assertNull("it has no observed start", (model.elements.first() as BatteryChartGap).fromElapsedMillis)
    }

    @Test
    fun `a malformed ordering is preserved rather than tidied away`() {
        // The mapper must not sort. Reordering would hide a contradiction in stored state
        // behind a plausible-looking chart.
        val model = map(point(5_000, 80), point(1_000, 79))

        assertEquals(SeriesGapReason.MALFORMED, model.gaps.single().reason)
        assertEquals(2, model.segments.size)
    }

    @Test
    fun `a wall-clock correction does not break the line`() {
        val model = map(
            point(0, 80, wall = 1_700_000_000_000L),
            point(cadence, 79, wall = 1_700_000_000_000L - 3_600_000L),
        )

        assertEquals("geometry follows elapsed time, not the wall clock", 1, model.segments.size)
        assertTrue(model.gaps.isEmpty())
    }

    // -------------------------------------------------------------------- missing != zero

    @Test
    fun `a missing percentage stays missing and never becomes zero`() {
        val model = map(point(0, 80), point(cadence, null), point(cadence * 2, 78))

        val percents = model.segments.single().points.map { it.percent }
        assertEquals(listOf(80, null, 78), percents)
        assertTrue("null is never rendered as 0", percents.none { it == 0 })
    }

    @Test
    fun `a series with no usable percentage is distinguishable from an empty one`() {
        val model = map(point(0, null), point(cadence, null))

        assertTrue("observations exist", !model.isEmpty)
        assertTrue("but none can be plotted", model.hasNoUsablePercentage)
        assertEquals(2, model.summary.observationCount)
        assertNull(model.summary.firstPercent)
    }

    @Test
    fun `an unusable scale produces no percentage rather than a guess`() {
        val model = map(point(0, level = 50, scale = 0))

        assertNull(model.segments.single().points.single().percent)
    }

    // ------------------------------------------------------------------------- geometry

    @Test
    fun `the y domain is fixed at 0 to 100, not fitted to the data`() {
        // 47-52 must look like a small drift. Autoscaling would turn it into a cliff.
        val model = map(point(0, 47), point(cadence, 52))

        assertEquals(0, model.viewport.yMin)
        assertEquals(100, model.viewport.yMax)
    }

    @Test
    fun `the x domain is the elapsed extent`() {
        val model = map(point(1_000, 80), point(9_000, 70))

        assertEquals(1_000L, model.viewport.xMinMillis)
        assertEquals(9_000L, model.viewport.xMaxMillis)
    }

    @Test
    fun `uneven spacing is preserved, not equalised`() {
        // 10, 15 and 60 minutes: B->C must be far wider than A->B.
        val model = map(
            point(10 * 60_000L, 80),
            point(15 * 60_000L, 79),
            point(60 * 60_000L, 60),
        )
        val v = model.viewport
        val a = v.xOf(10 * 60_000L)
        val b = v.xOf(15 * 60_000L)
        val c = v.xOf(60 * 60_000L)

        assertTrue("the long gap is wider", (c - b) > (b - a) * 5)
        assertEquals(0f, a, 0.001f)
        assertEquals(1f, c, 0.001f)
    }

    @Test
    fun `three hundred observations are handled`() {
        val points = (0 until 300).map { point(it * cadence, 100 - it / 3) }
        val model = map(*points.toTypedArray())

        assertEquals(300, model.summary.observationCount)
        assertEquals(1, model.segments.size)
    }

    // --------------------------------------------------------------------------- copy

    @Test
    fun `every gap reason has a label and a description, and neither is an enum name`() {
        val names = SeriesGapReason.entries.map { it.name }
        for (reason in SeriesGapReason.entries) {
            val label = GapCopy.label(reason)
            val description = GapCopy.description(reason)
            assertTrue("$reason needs a label", label.isNotBlank())
            assertTrue("$reason needs a description", description.length > 20)
            assertTrue(
                "$reason leaked its enum name into '$label'",
                names.none { label.contains(it) || description.contains(it) },
            )
        }
    }

    @Test
    fun `the six gap reasons say six different things`() {
        val descriptions = SeriesGapReason.entries.map { GapCopy.description(it) }

        assertEquals(
            "collapsing two reasons into one sentence loses a distinction",
            SeriesGapReason.entries.size,
            descriptions.toSet().size,
        )
    }

    @Test
    fun `the summary counts what the chart shows`() {
        val model = map(
            point(0, 80), point(cadence, 79),
            point(cadence * 12, 60), point(cadence * 13, 59),
            point(cadence * 30, 40),
        )

        assertEquals(5, model.summary.observationCount)
        assertEquals("two runs of two or more", 2, model.summary.connectedSegmentCount)
        assertEquals(2, model.summary.gapCount)
        assertEquals(2, model.summary.gapDescriptions.size)
        assertEquals(80, model.summary.firstPercent)
        assertEquals(40, model.summary.lastPercent)
    }

    // --------------------------------------------------------------------------- helpers

    private fun map(vararg points: BatterySeriesPoint) = BatteryChartMapper.map(
        BatterySeriesBuilder.build(SESSION, points.toList(), cadence),
    )

    private fun point(
        elapsed: Long,
        percent: Int? = 80,
        level: Int? = percent,
        scale: Int? = 100,
        wall: Long = 1_700_000_000_000L + elapsed,
        boot: BootIdentity = BootIdentity.Kernel("boot-a"),
        trigger: SessionTrigger = SessionTrigger.PERIODIC,
    ) = BatterySeriesPoint(
        elapsedRealtimeMillis = elapsed,
        wallClockMillis = wall,
        utcOffsetMinutes = 330,
        bootIdentity = boot,
        level = level,
        scale = scale,
        status = BatteryStatus.DISCHARGING,
        plug = PlugSource.NONE,
        temperatureDeciCelsius = 251,
        voltageMilliVolts = 4123,
        chargeCounterMicroAmpHours = 3_210_000L,
        trigger = trigger,
    )

    private companion object {
        const val SESSION = "00000000-0000-0000-0000-0000000000aa"
    }
}

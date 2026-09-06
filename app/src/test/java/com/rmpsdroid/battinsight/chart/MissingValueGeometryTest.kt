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
 * A reading with no battery level breaks the line, without becoming a gap.
 *
 * ## The bug this pins
 *
 * Phase 9C guaranteed that a missing percentage never becomes zero. It did not guarantee that a
 * missing percentage never becomes *interpolated geometry*, and it did not hold. Measured
 * before the fix:
 *
 * ```
 * 80% / unavailable / 78%   ->  LineStrip(points=[80, 78], x=[0.0, 1.0])
 * ```
 *
 * All three readings legitimately belong to one Phase 9B segment -- same boot, within cadence,
 * no process death -- so the domain was right to join them *in time*. But a battery line asserts
 * something narrower: that the level went from one value to the other. Drawing 80 to 78 straight
 * through the position where the platform reported nothing is exactly that claim, about a moment
 * with no evidence behind it.
 *
 * The fix is in presentation, not in the domain. Phase 9B is untouched: the observation was
 * real, and calling it NOT_OBSERVED would be a different lie.
 */
class MissingValueGeometryTest {

    private val cadence = 5L * 60_000L

    // --------------------------------------------------------- the case that was broken

    @Test
    fun `a reading with no level does not get a line drawn across it`() {
        val primitives = plan(point(0, 80), point(cadence, null), point(cadence * 2, 78))

        assertTrue(
            "no strip may span the unavailable reading",
            primitives.filterIsInstance<LineStrip>().isEmpty(),
        )
        assertEquals("each usable run is a lone reading", 2, primitives.filterIsInstance<PointMarker>().size)
    }

    @Test
    fun `the surrounding readings survive as their own runs`() {
        val primitives = plan(
            point(0, 80), point(cadence, 79),
            point(cadence * 2, null),
            point(cadence * 3, 77), point(cadence * 4, 76),
        )

        val strips = primitives.filterIsInstance<LineStrip>()
        assertEquals("two drawable runs", 2, strips.size)
        assertEquals(listOf(80, 79), strips[0].points.map { it.percent })
        assertEquals(listOf(77, 76), strips[1].points.map { it.percent })
    }

    @Test
    fun `consecutive unavailable readings still connect nothing`() {
        val primitives = plan(
            point(0, 80),
            point(cadence, null), point(cadence * 2, null),
            point(cadence * 3, 77),
        )

        assertTrue(primitives.filterIsInstance<LineStrip>().isEmpty())
        assertEquals(2, primitives.filterIsInstance<PointMarker>().size)
        val marker = primitives.filterIsInstance<ValueUnavailableMarker>().single()
        assertEquals("consecutive readings collapse into one marker", 2, marker.readingCount)
    }

    @Test
    fun `a leading unavailable reading fabricates nothing before the first real value`() {
        val primitives = plan(point(0, null), point(cadence, 80), point(cadence * 2, 79))

        val strip = primitives.filterIsInstance<LineStrip>().single()
        assertEquals(listOf(80, 79), strip.points.map { it.percent })
        assertEquals(1, primitives.filterIsInstance<ValueUnavailableMarker>().size)
    }

    @Test
    fun `a trailing unavailable reading fabricates no continuation`() {
        val primitives = plan(point(0, 80), point(cadence, 79), point(cadence * 2, null))

        val strip = primitives.filterIsInstance<LineStrip>().single()
        assertEquals(listOf(80, 79), strip.points.map { it.percent })
        val marker = primitives.filterIsInstance<ValueUnavailableMarker>().single()
        assertTrue(
            "nothing is drawn past the last real value",
            strip.points.last().x <= marker.startX + TOLERANCE,
        )
    }

    @Test
    fun `readings with no level at all produce no geometry`() {
        val primitives = plan(point(0, null), point(cadence, null))

        assertTrue(primitives.filterIsInstance<LineStrip>().isEmpty())
        assertTrue("and specifically no point at zero", primitives.filterIsInstance<PointMarker>().isEmpty())
    }

    // ------------------------------------------------------------ the strong assertion

    @Test
    fun `no strip ever spans an unavailable reading`() {
        // The general property, checked against a deliberately awkward series rather than one
        // hand-picked case: every strip must lie wholly on one side of every unavailable run.
        val primitives = plan(
            point(0, 80), point(cadence, 79),
            point(cadence * 2, null),
            point(cadence * 3, 77),
            point(cadence * 4, null), point(cadence * 5, null),
            point(cadence * 6, 74), point(cadence * 7, 73),
        )

        val markers = primitives.filterIsInstance<ValueUnavailableMarker>()
        assertEquals(2, markers.size)
        for (strip in primitives.filterIsInstance<LineStrip>()) {
            val first = strip.points.first().x
            val last = strip.points.last().x
            for (marker in markers) {
                assertTrue(
                    "strip $first..$last spans unavailable ${marker.startX}..${marker.endX}",
                    last <= marker.startX + TOLERANCE || first >= marker.endX - TOLERANCE,
                )
            }
        }
    }

    @Test
    fun `unavailable readings do not move the real observations`() {
        // Removing a reading from the geometry must not compress the axis: the surviving
        // observations keep the positions their elapsed times give them.
        val primitives = plan(point(0, 80), point(cadence, null), point(cadence * 2, 78))
        val markers = primitives.filterIsInstance<PointMarker>()

        assertEquals(0f, markers[0].point.x, TOLERANCE)
        assertEquals("the last reading stays at the right edge", 1f, markers[1].point.x, TOLERANCE)
    }

    // ----------------------------------------------------------- not a gap, not zero

    @Test
    fun `an unavailable value is not counted as an unobserved gap`() {
        val model = map(point(0, 80), point(cadence, null), point(cadence * 2, 78))

        assertEquals("nobody stopped watching", 0, model.summary.gapCount)
        assertEquals(1, model.summary.unavailableValueCount)
        assertTrue(model.gaps.isEmpty())
    }

    @Test
    fun `unavailable copy is distinct from the not-observed copy`() {
        val unavailable = ValueUnavailableCopy.description(1)
        val notObserved = GapCopy.description(SeriesGapReason.NOT_OBSERVED)

        assertTrue("they must not be the same sentence", unavailable != notObserved)
        assertTrue("unavailable says a reading happened", unavailable.contains("reading was taken"))
        assertTrue("not-observed says none did", notObserved.contains("No readings were taken"))
        assertTrue(
            "and neither claims the level held steady",
            !unavailable.contains("unchanged") && !notObserved.contains("unchanged"),
        )
    }

    @Test
    fun `an unavailable value stays null rather than becoming zero percent`() {
        val model = map(point(0, 80), point(cadence, null), point(cadence * 2, 78))
        val middle = model.segments.single().points[1]

        assertNull(middle.percent)
        assertTrue(
            "and it never reaches the renderer as a point",
            RenderPlanner.battery(model).filterIsInstance<PointMarker>().none { it.point.percent == 0 },
        )
    }

    @Test
    fun `a series of only unavailable values keeps the existing explanatory state`() {
        val model = map(point(0, null), point(cadence, null))

        assertTrue("observations exist", !model.isEmpty)
        assertTrue(model.hasNoUsablePercentage)
        assertEquals(2, model.summary.unavailableValueCount)
    }

    @Test
    fun `a run broken only by unavailable values reports no connected evidence`() {
        // 80 / null / 78 has two readings but no run of two, so there is no trend to draw and
        // the screen must say so rather than showing a chart with an implied line.
        val model = map(point(0, 80), point(cadence, null), point(cadence * 2, 78))

        assertTrue(model.hasNoConnectedEvidence)
        assertEquals(0, model.summary.connectedSegmentCount)
    }

    @Test
    fun `connected runs either side of an unavailable value both count`() {
        val model = map(
            point(0, 80), point(cadence, 79),
            point(cadence * 2, null),
            point(cadence * 3, 77), point(cadence * 4, 76),
        )

        assertEquals("two runs of two", 2, model.summary.connectedSegmentCount)
        assertTrue(!model.hasNoConnectedEvidence)
    }

    // --------------------------------------------------------------------------- helpers

    private fun plan(vararg points: BatterySeriesPoint) = RenderPlanner.battery(map(*points))

    private fun map(vararg points: BatterySeriesPoint) = BatteryChartMapper.map(
        BatterySeriesBuilder.build(SESSION, points.toList(), cadence),
    )

    private fun point(elapsed: Long, percent: Int?) = BatterySeriesPoint(
        elapsedRealtimeMillis = elapsed,
        wallClockMillis = 1_700_000_000_000L + elapsed,
        utcOffsetMinutes = 330,
        bootIdentity = BootIdentity.Kernel("boot-a"),
        level = percent,
        scale = 100,
        status = BatteryStatus.DISCHARGING,
        plug = PlugSource.NONE,
        temperatureDeciCelsius = 251,
        voltageMilliVolts = 4123,
        chargeCounterMicroAmpHours = 3_210_000L,
        trigger = SessionTrigger.PERIODIC,
    )

    private companion object {
        const val SESSION = "00000000-0000-0000-0000-0000000000aa"
        const val TOLERANCE = 0.001f
    }
}

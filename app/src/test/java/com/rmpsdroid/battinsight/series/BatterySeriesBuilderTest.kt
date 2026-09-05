package com.rmpsdroid.battinsight.series

import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a battery series may be drawn as a line, and where it may not.
 *
 * Pure: no database, no Android, no clock. Every rule here is a decision the chart in Phase 9C
 * is forbidden from making for itself, so each one is pinned individually rather than through
 * a single happy-path example.
 */
class BatterySeriesBuilderTest {

    private val cadence = 5L * 60_000L

    // --------------------------------------------------------------------- connectivity

    @Test
    fun `samples within the cadence form one connected segment`() {
        val series = build(point(0), point(cadence), point(cadence * 2))

        assertEquals(1, series.segments.size)
        assertEquals(0, series.gaps.size)
        assertEquals(3, series.segments.single().points.size)
    }

    @Test
    fun `a lifecycle pause becomes an unobserved gap, not a longer line`() {
        // Twelve cadences apart: the app was closed. Nobody watched that interval, so nothing
        // may be drawn across it -- and question B is "when was drain fastest", so an
        // interpolated line would answer it with a number nobody measured.
        val series = build(point(0), point(cadence * 12))

        assertEquals(2, series.segments.size)
        assertEquals(SeriesGapReason.NOT_OBSERVED, series.gaps.single().reason)
    }

    @Test
    fun `jitter within tolerance is not a gap`() {
        // A coroutine delay is not a real-time guarantee, and the sample taken when the UI
        // becomes visible is deliberately immediate rather than grid-aligned. Reporting that
        // as an unobserved interval would be a lie in the other direction.
        val series = build(point(0), point(cadence + cadence / 2))

        assertEquals(1, series.segments.size)
        assertTrue(series.gaps.isEmpty())
    }

    @Test
    fun `an APP_START sample reports the process restart rather than mere spacing`() {
        // Both facts are true; the more specific one is the better answer.
        val series = build(point(0), point(cadence * 12, trigger = SessionTrigger.APP_START))

        assertEquals(SeriesGapReason.PROCESS_RESTART, series.gaps.single().reason)
    }

    @Test
    fun `a process restart is reported even when the samples are close together`() {
        val series = build(point(0), point(1_000, trigger = SessionTrigger.APP_START))

        assertEquals(SeriesGapReason.PROCESS_RESTART, series.gaps.single().reason)
    }

    // ----------------------------------------------------------------------------- boot

    @Test
    fun `a different boot is a hard break`() {
        val series = build(
            point(0, boot = BootIdentity.Kernel("boot-a")),
            point(cadence, boot = BootIdentity.Kernel("boot-b")),
        )

        assertEquals(SeriesGapReason.DIFFERENT_BOOT, series.gaps.single().reason)
        assertEquals(2, series.segments.size)
    }

    @Test
    fun `an unknown boot relation is unproven continuity, not continuity`() {
        val series = build(
            point(0, boot = BootIdentity.Kernel("boot-a")),
            point(cadence, boot = BootIdentity.Unknown),
        )

        assertEquals(SeriesGapReason.CONTINUITY_UNPROVEN, series.gaps.single().reason)
    }

    @Test
    fun `two equal derived boot identities are still unproven`() {
        // The case that makes a second boot-comparison implementation dangerous. Equal derived
        // values look identical, and BootIdentity.relationTo still answers UNKNOWN, because a
        // derived value is an estimate rather than evidence. A field comparison written by
        // hand would say SAME here and quietly draw a line across a reboot.
        val series = build(
            point(0, boot = BootIdentity.Derived(1_700_000_000_000L)),
            point(cadence, boot = BootIdentity.Derived(1_700_000_000_000L)),
        )

        assertEquals(SeriesGapReason.CONTINUITY_UNPROVEN, series.gaps.single().reason)
    }

    @Test
    fun `boot is asked before spacing`() {
        // Adjacent in time, different boot. If spacing were tested first this would connect.
        val series = build(
            point(0, boot = BootIdentity.Kernel("boot-a")),
            point(1_000, boot = BootIdentity.Kernel("boot-b")),
        )

        assertEquals(SeriesGapReason.DIFFERENT_BOOT, series.gaps.single().reason)
    }

    // ------------------------------------------------------------------------ time rules

    @Test
    fun `elapsed realtime running backwards within one boot is malformed`() {
        // The builder does not re-sort, precisely so this stays reachable. Within one boot the
        // monotonic clock cannot go backwards; if it appears to, stored state contradicts
        // itself and no interval between these two readings means anything.
        val series = build(point(5_000), point(1_000))

        assertEquals(SeriesGapReason.MALFORMED, series.gaps.single().reason)
        assertEquals(2, series.segments.size)
    }

    @Test
    fun `a wall-clock correction is not a break`() {
        // An NTP step or a timezone change moves the wall clock without any elapsed time
        // passing. It changes the labels on the axis and nothing about whether the two
        // readings are connected.
        val series = build(
            point(0, wall = 1_700_000_000_000L),
            point(cadence, wall = 1_700_000_000_000L - 3_600_000L),
        )

        assertEquals(1, series.segments.size)
        assertTrue("a clock correction must not create a gap", series.gaps.isEmpty())
    }

    // ------------------------------------------------------------------------- retention

    @Test
    fun `a retention watermark produces a leading not-retained gap`() {
        val series = BatterySeriesBuilder.build(
            sessionId = SESSION,
            samples = listOf(point(cadence * 20), point(cadence * 21)),
            cadenceMillis = cadence,
            evictedThroughElapsedMillis = cadence * 19,
        )

        val first = series.elements.first()
        assertTrue("the series must open with a gap", first is SeriesGap)
        assertEquals(SeriesGapReason.NOT_RETAINED, (first as SeriesGap).reason)
        // The gap ends where the retained series begins, and has no observed start: the
        // samples that would have defined it are the ones retention deleted.
        assertEquals(cadence * 20, first.toElapsedMillis)
        assertNull(first.fromElapsedMillis)
    }

    @Test
    fun `the first retained sample never simply follows the session start`() {
        val withWatermark = BatterySeriesBuilder.build(
            SESSION, listOf(point(1_000)), cadence, evictedThroughElapsedMillis = 500,
        )
        val without = BatterySeriesBuilder.build(SESSION, listOf(point(1_000)), cadence)

        assertEquals(2, withWatermark.elements.size)
        assertEquals("without a watermark there is nothing to declare", 1, without.elements.size)
    }

    // ------------------------------------------------------------------- degenerate cases

    @Test
    fun `no samples and no watermark is no series, not an empty gap`() {
        val series = BatterySeriesBuilder.build(SESSION, emptyList(), cadence)

        assertTrue(series.isEmpty)
        assertTrue("a session nobody sampled has no gap to describe", series.gaps.isEmpty())
    }

    @Test
    fun `a watermark with no samples is reported as inconsistent state`() {
        // Structurally impossible -- eviction stops at the cap and can never empty a session --
        // so reaching it means stored state disagrees with itself. Saying so beats rendering
        // nothing, which would look identical to a session that was never sampled.
        val series = BatterySeriesBuilder.build(
            SESSION, emptyList(), cadence, evictedThroughElapsedMillis = 9_000,
        )

        assertEquals(SeriesGapReason.MALFORMED, series.gaps.single().reason)
    }

    @Test
    fun `a single sample is a one-point segment`() {
        val series = build(point(0))

        assertEquals(1, series.segments.size)
        assertEquals(1, series.segments.single().points.size)
    }

    @Test
    fun `segments and gaps alternate and never touch`() {
        val series = build(
            point(0), point(cadence),
            point(cadence * 20),
            point(cadence * 40, boot = BootIdentity.Kernel("boot-b")),
        )

        // Two segments may never sit next to each other: if they could, a renderer would have
        // to decide whether to join them, which is the decision this model exists to remove.
        series.elements.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "two segments must never be adjacent",
                !(a is BatterySegment && b is BatterySegment),
            )
        }
        assertEquals(3, series.segments.size)
        assertEquals(2, series.gaps.size)
    }

    // ---------------------------------------------------------------------------- values

    @Test
    fun `a missing level stays missing rather than becoming zero percent`() {
        val p = point(0, level = null)

        assertNull(p.level)
        assertNull("an unreported level is not 0%", p.percent)
    }

    @Test
    fun `a zero scale does not produce an invented percentage`() {
        val p = point(0, level = 50, scale = 0)

        assertNull(p.percent)
    }

    @Test
    fun `a reported level converts against its own scale`() {
        assertEquals(50, point(0, level = 100, scale = 200).percent)
    }

    // --------------------------------------------------------------------------- helpers

    private fun build(vararg points: BatterySeriesPoint) =
        BatterySeriesBuilder.build(SESSION, points.toList(), cadence)

    private fun point(
        elapsed: Long,
        wall: Long = 1_700_000_000_000L + elapsed,
        boot: BootIdentity = BootIdentity.Kernel("boot-a"),
        trigger: SessionTrigger = SessionTrigger.PERIODIC,
        level: Int? = 73,
        scale: Int? = 100,
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

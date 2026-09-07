package com.rmpsdroid.battinsight.series

import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.ProcessStartGate
import com.rmpsdroid.battinsight.session.SessionTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backgrounded application is not a crashed one.
 *
 * ## The defect this pins (Phase 10A.1, P1-1)
 *
 * `sampleOnBecomingVisible()` runs inside `repeatOnLifecycle(STARTED)`, so it runs once when
 * the process starts **and again every time the UI returns to view**. Every one of those
 * readings used to announce [SessionTrigger.APP_START], and [BatterySeriesBuilder] reads that
 * as proof the previous process died. Measured on a Samsung SM-M156B: pid 31963 was continuous
 * for the whole session, the app was merely backgrounded and reopened, and Session Detail told
 * the user:
 *
 * ```
 * "BattInsight stopped running and started again. Nothing was recorded in between."
 * ```
 *
 * The stored sample that caused it:
 *
 * ```
 * elapsed=1953275349  trigger=APP_START  level=67   (+187049 ms after the previous reading)
 * ```
 *
 * ## The contract these tests hold
 *
 * `PROCESS_RESTART` means the process actually ended and a new one started. `NOT_OBSERVED`
 * means nobody was sampling, without any claim about why. A same-process resume must never be
 * the first, and a genuine relaunch must still be.
 */
class LifecycleGapCausalityTest {

    private val cadence = 5L * 60_000L
    private val farApart = cadence * 4

    // ------------------------------------------------- process restart is still detected

    @Test
    fun `a genuine process start still reports a process restart`() {
        val series = build(
            point(0, 80, SessionTrigger.PERIODIC),
            point(farApart, 78, SessionTrigger.APP_START),
        )

        assertEquals(SeriesGapReason.PROCESS_RESTART, series.gaps.single().reason)
    }

    @Test
    fun `process restart wins over mere spacing, as the more specific answer`() {
        // Both facts are true; the more specific one is the better answer.
        val series = build(
            point(0, 80, SessionTrigger.PERIODIC),
            point(farApart, 78, SessionTrigger.APP_START),
        )
        assertEquals(SeriesGapReason.PROCESS_RESTART, series.gaps.single().reason)
    }

    // --------------------------------------------- same-process resume is NOT a restart

    @Test
    fun `a same-process resume after a long hidden interval is not observed, not restarted`() {
        // The exact Samsung shape: a long unobserved stretch ended by the UI returning.
        val series = build(
            point(0, 80, SessionTrigger.PERIODIC),
            point(farApart, 78, SessionTrigger.APP_VISIBLE),
        )

        val gap = series.gaps.single()
        assertEquals(SeriesGapReason.NOT_OBSERVED, gap.reason)
        assertTrue(
            "the copy must not claim the app stopped running",
            !gap.reason.name.contains("PROCESS"),
        )
    }

    @Test
    fun `no visibility resume ever produces a process restart, at any spacing`() {
        // The general property. Whatever the spacing, APP_VISIBLE never asserts process death.
        for (spacing in listOf(1L, cadence / 2, cadence, cadence * 2, cadence * 10, cadence * 100)) {
            val series = build(
                point(0, 80, SessionTrigger.PERIODIC),
                point(spacing, 79, SessionTrigger.APP_VISIBLE),
            )
            assertTrue(
                "spacing $spacing produced a restart claim",
                series.gaps.none { it.reason == SeriesGapReason.PROCESS_RESTART },
            )
        }
    }

    @Test
    fun `a brief hide and return does not fabricate a gap at all`() {
        // Sampling was not meaningfully interrupted, so there is nothing to report.
        val series = build(
            point(0, 80, SessionTrigger.PERIODIC),
            point(cadence, 79, SessionTrigger.APP_VISIBLE),
        )

        assertTrue("no gap should be invented", series.gaps.isEmpty())
        assertEquals(1, series.segments.size)
    }

    @Test
    fun `repeated hide and return cycles never accumulate restart claims`() {
        val series = build(
            point(0, 80, SessionTrigger.PERIODIC),
            point(farApart, 79, SessionTrigger.APP_VISIBLE),
            point(farApart * 2, 78, SessionTrigger.APP_VISIBLE),
            point(farApart * 3, 77, SessionTrigger.APP_VISIBLE),
        )

        assertEquals(3, series.gaps.size)
        assertTrue(
            "every gap is an absence of observation, not a death claim",
            series.gaps.all { it.reason == SeriesGapReason.NOT_OBSERVED },
        )
    }

    // ---------------------------------------------------- startup does not fake a gap

    @Test
    fun `initial process start does not create a leading fake gap`() {
        val series = build(point(0, 80, SessionTrigger.APP_START))

        assertTrue("a first reading has nothing to be separated from", series.gaps.isEmpty())
        assertEquals(1, series.segments.size)
    }

    @Test
    fun `a start immediately followed by a visibility reading produces no gap`() {
        // Guards the Step 5 hazard: APP_START then APP_VISIBLE milliseconds apart must not
        // become a boundary. They are two readings of one moment, not an interruption.
        val series = build(
            point(0, 80, SessionTrigger.APP_START),
            point(120, 80, SessionTrigger.APP_VISIBLE),
        )

        assertTrue(series.gaps.isEmpty())
        assertEquals(1, series.segments.size)
    }

    // ------------------------------------------------------------ the process-scope gate

    @Test
    fun `the gate yields a start exactly once per process`() {
        val gate = ProcessStartGate()

        assertTrue("the first caller in a process is the start", gate.claimStart())
        assertFalse(gate.claimStart())
        assertFalse(gate.claimStart())
        assertFalse(gate.claimStart())
        assertTrue(gate.hasStarted)
    }

    @Test
    fun `a fresh process restores genuine start semantics`() {
        val old = ProcessStartGate()
        old.claimStart()

        // Process death is the reset: new process, new gate, and the claim is available again.
        val new = ProcessStartGate()
        assertTrue(new.claimStart())
    }

    @Test
    fun `repeated visibility transitions in one process never claim a start`() {
        val gate = ProcessStartGate()
        val triggers = (1..6).map { triggerFor(gate) }

        assertEquals(SessionTrigger.APP_START, triggers.first())
        assertTrue(
            "every later transition is a visibility event",
            triggers.drop(1).all { it == SessionTrigger.APP_VISIBLE },
        )
        assertEquals(1, triggers.count { it == SessionTrigger.APP_START })
    }

    @Test
    fun `activity recreation in the same process does not become a process restart`() {
        // Rotation destroys and recreates the Activity, and repeatOnLifecycle runs again. The
        // gate is process-scoped, so the reading is APP_VISIBLE and the series says nothing
        // about process lifetime.
        val gate = ProcessStartGate()
        triggerFor(gate)                       // first launch
        val afterRecreation = triggerFor(gate) // configuration change

        assertEquals(SessionTrigger.APP_VISIBLE, afterRecreation)

        val series = build(
            point(0, 80, SessionTrigger.APP_START),
            point(2_000, 80, afterRecreation),
        )
        assertTrue(series.gaps.none { it.reason == SeriesGapReason.PROCESS_RESTART })
        assertTrue("a rotation is not an interruption", series.gaps.isEmpty())
    }

    // ------------------------------------------------- everything else must be unchanged

    @Test
    fun `different boot is still reported, and wins over the trigger`() {
        val series = build(
            point(0, 80, SessionTrigger.PERIODIC, BootIdentity.Kernel("boot-a")),
            point(farApart, 78, SessionTrigger.APP_VISIBLE, BootIdentity.Kernel("boot-b")),
        )

        assertEquals(SeriesGapReason.DIFFERENT_BOOT, series.gaps.single().reason)
    }

    @Test
    fun `continuity unproven is still reported for derived boot identity`() {
        val series = build(
            point(0, 80, SessionTrigger.PERIODIC, BootIdentity.Derived(1_000L)),
            point(farApart, 78, SessionTrigger.APP_VISIBLE, BootIdentity.Derived(1_000L)),
        )

        assertEquals(SeriesGapReason.CONTINUITY_UNPROVEN, series.gaps.single().reason)
    }

    @Test
    fun `not retained is still reported from the eviction watermark`() {
        val series = BatterySeriesBuilder.build(
            sessionId = SESSION,
            samples = listOf(point(farApart, 80, SessionTrigger.APP_VISIBLE)),
            cadenceMillis = cadence,
            evictedThroughElapsedMillis = 1_000L,
        )

        assertEquals(SeriesGapReason.NOT_RETAINED, series.gaps.first().reason)
    }

    @Test
    fun `malformed ordering is still reported and still wins over the trigger`() {
        val series = build(
            point(farApart, 80, SessionTrigger.PERIODIC),
            point(0, 78, SessionTrigger.APP_VISIBLE),
        )

        assertEquals(SeriesGapReason.MALFORMED, series.gaps.single().reason)
    }

    @Test
    fun `a missing level still breaks the run and is still not a gap`() {
        // Phase 9C.1 must be undisturbed: an unavailable percentage is neither zero nor a gap.
        val series = build(
            point(0, 80, SessionTrigger.PERIODIC),
            point(cadence, null, SessionTrigger.APP_VISIBLE),
            point(cadence * 2, 78, SessionTrigger.PERIODIC),
        )

        assertTrue("an unavailable level is not an unobserved interval", series.gaps.isEmpty())
        assertNull(series.segments.single().points[1].level)
    }

    @Test
    fun `the new trigger counts as an observed reading`() {
        assertTrue(SessionTrigger.APP_VISIBLE.isObserved)
        assertTrue(SessionTrigger.APP_START.isObserved)
        assertFalse("recovery is inferred, not observed", SessionTrigger.RECOVERY.isObserved)
    }

    // --------------------------------------------------------------------------- helpers

    /** The production rule from `sampleOnBecomingVisible`, in one place. */
    private fun triggerFor(gate: ProcessStartGate): SessionTrigger =
        if (gate.claimStart()) SessionTrigger.APP_START else SessionTrigger.APP_VISIBLE

    private fun build(vararg points: BatterySeriesPoint) =
        BatterySeriesBuilder.build(SESSION, points.toList(), cadence)

    private fun point(
        elapsed: Long,
        percent: Int?,
        trigger: SessionTrigger,
        boot: BootIdentity = BootIdentity.Kernel("boot-a"),
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
        const val SESSION = "00000000-0000-0000-0000-0000000000bb"
    }
}

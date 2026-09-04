package com.rmpsdroid.battinsight.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session lifecycle, scenario by scenario.
 *
 * Every case here is one a real device produces. The awkward ones matter more than the
 * clean ones: a tool that only handles plug-in and plug-out is the tool that reported
 * `Period: n/a`, lost history at every reboot, and only noticed a charge if it reached
 * 100%.
 */
class SessionEngineTest {

    private fun engine() = SessionEngine(SequentialIds())

    private fun startedWith(observation: BatteryObservation): SessionTransition =
        engine().reconcile(null, observation)

    // ------------------------------------------------------------------- 1-4. cold start

    @Test
    fun `cold start while unplugged opens a discharge session`() {
        val t = startedWith(discharging(10_000, trigger = SessionTrigger.APP_START))

        assertTrue(t.result is TransitionResult.Started)
        assertEquals(SessionType.DISCHARGE, t.state.session!!.type)
        assertEquals(SessionTrigger.APP_START, (t.result as TransitionResult.Started).trigger)
    }

    @Test
    fun `cold start while charging opens a charge session`() {
        val t = startedWith(charging(10_000, trigger = SessionTrigger.APP_START))
        assertEquals(SessionType.CHARGE, t.state.session!!.type)
    }

    @Test
    fun `cold start at full while plugged is a charge session, not a discharge one`() {
        // The user has not unplugged anything, so no discharge interval has begun.
        val t = startedWith(fullPlugged(10_000))
        assertEquals(SessionType.CHARGE, t.state.session!!.type)
        assertEquals(BatteryStatus.FULL, t.state.session!!.start.battery.status)
    }

    @Test
    fun `cold start with unknown battery state opens an unknown session rather than guessing`() {
        val t = startedWith(unknownState(10_000))
        assertEquals(SessionType.UNKNOWN, t.state.session!!.type)
    }

    // ---------------------------------------------------------------- 5-6. real transitions

    @Test
    fun `discharge followed by power connected ends one interval and starts another`() {
        val e = engine()
        val a = e.reconcile(null, discharging(0, trigger = SessionTrigger.APP_START))
        val b = e.accept(a.state, charging(5 * MINUTE, trigger = SessionTrigger.POWER_CONNECTED))

        val boundary = b.result as TransitionResult.Boundary
        assertEquals(SessionType.DISCHARGE, boundary.ended.type)
        assertEquals(SessionType.CHARGE, boundary.started.type)
        assertEquals(SessionBoundaryReason.POWER_TRANSITION, boundary.reason)
        assertEquals(5 * MINUTE, boundary.ended.elapsedMillis)
        assertNotEquals(boundary.ended.id, boundary.started.id)
    }

    @Test
    fun `charge followed by power disconnected ends one interval and starts another`() {
        val e = engine()
        val a = e.reconcile(null, charging(0, trigger = SessionTrigger.APP_START))
        val b = e.accept(a.state, discharging(90 * MINUTE, trigger = SessionTrigger.POWER_DISCONNECTED))

        val boundary = b.result as TransitionResult.Boundary
        assertEquals(SessionType.CHARGE, boundary.ended.type)
        assertEquals(SessionType.DISCHARGE, boundary.started.type)
        assertEquals(90 * MINUTE, boundary.ended.elapsedMillis)
    }

    // ------------------------------------------------------------------ 7-10. idempotence

    @Test
    fun `a repeated power connected does not create a second charge session`() {
        val e = engine()
        var s = e.reconcile(null, discharging(0)).state
        val first = e.accept(s, charging(MINUTE, trigger = SessionTrigger.POWER_CONNECTED))
        s = first.state
        val chargeId = s.session!!.id

        val second = e.accept(s, charging(MINUTE + 500, trigger = SessionTrigger.POWER_CONNECTED))

        assertTrue("expected no new session, was ${second.result}", second.result !is TransitionResult.Boundary)
        assertEquals(chargeId, second.state.session!!.id)
    }

    @Test
    fun `a repeated power disconnected does not create a second discharge session`() {
        val e = engine()
        var s = e.reconcile(null, charging(0)).state
        s = e.accept(s, discharging(MINUTE, trigger = SessionTrigger.POWER_DISCONNECTED)).state
        val dischargeId = s.session!!.id

        val again = e.accept(s, discharging(MINUTE + 500, trigger = SessionTrigger.POWER_DISCONNECTED))
        assertEquals(dischargeId, again.state.session!!.id)
        assertTrue(again.result !is TransitionResult.Boundary)
    }

    @Test
    fun `repeated identical battery changed events never churn the session`() {
        val e = engine()
        var s = e.reconcile(null, discharging(0)).state
        val id = s.session!!.id

        repeat(20) { i ->
            val t = e.accept(s, discharging(1_000L * (i + 1)))
            s = t.state
            assertTrue("iteration $i produced ${t.result}", t.result !is TransitionResult.Boundary)
        }
        assertEquals("the session identity must survive repetition", id, s.session!!.id)
    }

    @Test
    fun `power connected followed by battery changed is one transition, not two`() {
        // The two broadcasts describe the same physical event and arrive milliseconds apart.
        // Transitions are driven by resulting state, so the second changes nothing.
        val e = engine()
        var s = e.reconcile(null, discharging(0)).state

        val connected = e.accept(s, charging(MINUTE, trigger = SessionTrigger.POWER_CONNECTED))
        s = connected.state
        val chargeId = s.session!!.id

        val changed = e.accept(s, charging(MINUTE + 120, trigger = SessionTrigger.BATTERY_CHANGED))

        assertTrue(connected.result is TransitionResult.Boundary)
        assertTrue("second event must not be a boundary: ${changed.result}", changed.result !is TransitionResult.Boundary)
        assertEquals(chargeId, changed.state.session!!.id)
    }

    // -------------------------------------------------- 11-14, 31-32. process death, reboot

    @Test
    fun `a process restart on the same boot with unchanged state continues the session`() {
        val e = engine()
        val before = e.reconcile(null, discharging(0, trigger = SessionTrigger.APP_START)).state
        val sessionId = before.session!!.id

        // The process died here. State survived; nothing about the device changed.
        val after = e.reconcile(before, discharging(30 * MINUTE, trigger = SessionTrigger.APP_START))

        assertTrue("expected continuation, was ${after.result}", after.result is TransitionResult.Continued)
        assertEquals("process death is not a session boundary", sessionId, after.state.session!!.id)
        assertEquals(30 * MINUTE, after.state.session!!.elapsedMillis)
    }

    @Test
    fun `a transition missed while the process was dead is recovered, and marked as inferred`() {
        val e = engine()
        val before = e.reconcile(null, discharging(0, trigger = SessionTrigger.APP_START)).state

        // The user plugged in while nothing was running. No broadcast was ever received.
        val after = e.reconcile(before, charging(45 * MINUTE, trigger = SessionTrigger.APP_START))

        val boundary = after.result as TransitionResult.Boundary
        assertEquals(SessionBoundaryReason.RECOVERY, boundary.reason)
        assertEquals(
            "an inferred boundary must not claim a broadcast was seen",
            SessionTrigger.RECOVERY,
            boundary.trigger,
        )
        assertTrue("RECOVERY is not an observed trigger", !SessionTrigger.RECOVERY.isObserved)
        assertEquals(SessionType.CHARGE, after.state.session!!.type)
    }

    @Test
    fun `a different boot identity closes the previous interval at a boot boundary`() {
        val e = engine()
        val before = e.reconcile(null, discharging(2 * HOUR, boot = kernelBoot("boot-a"))).state
        val sessionId = before.session!!.id

        val after = e.reconcile(before, discharging(30_000, boot = kernelBoot("boot-b")))

        val boundary = after.result as TransitionResult.Boundary
        assertEquals(SessionBoundaryReason.BOOT_BOUNDARY, boundary.reason)
        assertEquals(SessionTrigger.BOOT_CHANGED, boundary.trigger)
        assertNotEquals(sessionId, after.state.session!!.id)
    }

    @Test
    fun `elapsed realtime restarting across a boot is a boot boundary, not a rewind`() {
        // The monotonic clock going from 2 hours to 30 seconds is normal across a reboot.
        // Refusing it as out-of-order would be wrong; so would subtracting the two.
        val e = engine()
        val before = e.reconcile(null, discharging(2 * HOUR, boot = kernelBoot("boot-a"))).state
        val after = e.accept(before, discharging(30_000, boot = kernelBoot("boot-b")))

        assertTrue("expected a boundary, was ${after.result}", after.result is TransitionResult.Boundary)
        assertTrue(
            "the new interval must not carry a negative duration",
            after.state.session!!.elapsedMillis >= 0,
        )
    }

    // ------------------------------------------------------- 15, 19, 34. out-of-order events

    @Test
    fun `an observation older than the last accepted one is rejected on the same boot`() {
        val e = engine()
        val s = e.reconcile(null, discharging(10 * MINUTE)).state

        val stale = e.accept(s, discharging(5 * MINUTE))

        val rejected = stale.result as TransitionResult.Rejected
        assertEquals(TransitionResult.RejectionReason.OUT_OF_ORDER, rejected.reason)
        assertTrue("the rejection must explain itself", rejected.detail.isNotBlank())
    }

    @Test
    fun `a rejected observation leaves state completely untouched`() {
        val e = engine()
        val s = e.reconcile(null, discharging(10 * MINUTE)).state

        val after = e.accept(s, charging(5 * MINUTE, trigger = SessionTrigger.POWER_CONNECTED))

        assertTrue(after.result is TransitionResult.Rejected)
        assertEquals("state must be identical, not merely equivalent", s, after.state)
        assertEquals(SessionType.DISCHARGE, after.state.session!!.type)
    }

    // ------------------------------------------------------------ 16-19. wall-clock changes

    @Test
    fun `a wall clock jumping forward one hour does not change session duration`() {
        val e = engine()
        val s = e.reconcile(null, discharging(0, wallClockMillis = EPOCH)).state

        // Ten monotonic minutes pass; the clock also gains an hour from an NTP correction.
        val after = e.accept(
            s,
            discharging(10 * MINUTE, wallClockMillis = EPOCH + 10 * MINUTE + HOUR),
        )

        assertEquals(
            "duration comes from the monotonic clock only",
            10 * MINUTE,
            after.state.session!!.elapsedMillis,
        )
    }

    @Test
    fun `a wall clock jumping backward one hour does not change session duration`() {
        val e = engine()
        val s = e.reconcile(null, discharging(0, wallClockMillis = EPOCH)).state

        val after = e.accept(
            s,
            discharging(10 * MINUTE, wallClockMillis = EPOCH + 10 * MINUTE - HOUR),
        )

        assertEquals(10 * MINUTE, after.state.session!!.elapsedMillis)
        assertTrue("and it certainly must not go negative", after.state.session!!.elapsedMillis >= 0)
    }

    @Test
    fun `a wall clock moving backwards is not treated as an out-of-order event`() {
        // Only the monotonic clock orders events. A backwards wall clock is a normal thing
        // for a device to do and must not cause a rejection.
        val e = engine()
        val s = e.reconcile(null, discharging(0, wallClockMillis = EPOCH)).state
        val after = e.accept(s, discharging(MINUTE, wallClockMillis = EPOCH - HOUR))

        assertTrue("must not be rejected: ${after.result}", after.result !is TransitionResult.Rejected)
    }

    @Test
    fun `a timezone change is recorded and does not affect duration`() {
        val e = engine()
        val s = e.reconcile(null, discharging(0, utcOffsetMinutes = 0)).state

        // The device flies to UTC+9. Wall clock and offset both move; monotonic does not.
        val after = e.accept(
            s,
            discharging(
                3 * HOUR,
                wallClockMillis = EPOCH + 3 * HOUR + 9 * HOUR,
                utcOffsetMinutes = 540,
            ),
        )

        assertEquals(3 * HOUR, after.state.session!!.elapsedMillis)
        assertEquals(
            "the offset at capture must be preserved so an export can explain itself",
            540,
            after.state.session!!.latest.time.utcOffsetMinutes,
        )
        assertEquals(0, after.state.session!!.start.time.utcOffsetMinutes)
    }

    @Test
    fun `a DST transition changes the offset without changing duration`() {
        val e = engine()
        val s = e.reconcile(null, discharging(0, utcOffsetMinutes = 60)).state
        val after = e.accept(
            s,
            discharging(2 * HOUR, wallClockMillis = EPOCH + HOUR, utcOffsetMinutes = 0),
        )

        assertEquals(2 * HOUR, after.state.session!!.elapsedMillis)
        assertEquals(0, after.state.session!!.latest.time.utcOffsetMinutes)
    }

    // ------------------------------------------------------------- 26-30. battery semantics

    @Test
    fun `a level falling while charging does not end the charge session`() {
        // Real: a device under heavy load can lose charge faster than it gains it.
        val e = engine()
        var s = e.reconcile(null, charging(0, level = 60)).state
        val id = s.session!!.id

        s = e.accept(s, charging(5 * MINUTE, level = 58)).state
        s = e.accept(s, charging(10 * MINUTE, level = 55)).state

        assertEquals(SessionType.CHARGE, s.session!!.type)
        assertEquals("level is not authority", id, s.session!!.id)
    }

    @Test
    fun `a level rising while discharging does not start a charge session`() {
        // Real: the platform recalibrates, or the reading was simply wrong before.
        val e = engine()
        var s = e.reconcile(null, discharging(0, level = 40)).state
        val id = s.session!!.id

        s = e.accept(s, discharging(5 * MINUTE, level = 44)).state

        assertEquals(SessionType.DISCHARGE, s.session!!.type)
        assertEquals(id, s.session!!.id)
    }

    @Test
    fun `reaching full while still plugged does not end the charge interval`() {
        val e = engine()
        var s = e.reconcile(null, charging(0, level = 95)).state
        val id = s.session!!.id

        val t = e.accept(s, fullPlugged(30 * MINUTE))
        s = t.state

        assertTrue("full while plugged is not a boundary: ${t.result}", t.result !is TransitionResult.Boundary)
        assertEquals(id, s.session!!.id)
        assertEquals(SessionType.CHARGE, s.session!!.type)
    }

    @Test
    fun `not charging while plugged does not begin a discharge interval`() {
        // A charge limit holding at 80% is still external power, and the user has not
        // unplugged anything.
        val e = engine()
        var s = e.reconcile(null, charging(0, level = 79)).state
        val id = s.session!!.id

        val t = e.accept(s, notChargingPlugged(20 * MINUTE))
        s = t.state

        assertEquals(SessionType.CHARGE, s.session!!.type)
        assertEquals(id, s.session!!.id)
        assertEquals(BatteryStatus.NOT_CHARGING, s.session!!.latest.battery.status)
    }

    @Test
    fun `an unknown battery status does not end a valid session`() {
        // Absence of evidence. Ending a real interval on it would lose a real measurement.
        val e = engine()
        var s = e.reconcile(null, discharging(0)).state
        val id = s.session!!.id

        val t = e.accept(s, unknownState(MINUTE))
        s = t.state

        assertTrue(t.result !is TransitionResult.Boundary)
        assertEquals(id, s.session!!.id)
        assertEquals(SessionType.DISCHARGE, s.session!!.type)
    }

    @Test
    fun `plug is authoritative over a contradictory status`() {
        // DISCHARGING while plugged happens under load. The cable is still connected, so
        // the interval is still a charge-family interval, and the contradiction is recorded.
        val o = observation(0, status = BatteryStatus.DISCHARGING, plug = PlugSource.AC)

        assertEquals(PowerAttachment.ATTACHED, o.powerAttachment)
        assertTrue("the disagreement must be visible", o.statusContradictsPlug)
    }

    @Test
    fun `status resolves attachment only when the plug is unknown`() {
        assertEquals(
            PowerAttachment.DETACHED,
            observation(0, status = BatteryStatus.DISCHARGING, plug = PlugSource.UNKNOWN).powerAttachment,
        )
        assertEquals(
            PowerAttachment.ATTACHED,
            observation(0, status = BatteryStatus.CHARGING, plug = PlugSource.UNKNOWN).powerAttachment,
        )
        // FULL and NOT_CHARGING say nothing about where power came from.
        assertEquals(
            PowerAttachment.UNKNOWN,
            observation(0, status = BatteryStatus.FULL, plug = PlugSource.UNKNOWN).powerAttachment,
        )
        assertEquals(
            PowerAttachment.UNKNOWN,
            observation(0, status = BatteryStatus.NOT_CHARGING, plug = PlugSource.UNKNOWN).powerAttachment,
        )
    }

    // --------------------------------------------------- 33, 35-36. identity and provenance

    @Test
    fun `a recovered boundary is distinguishable from an observed one`() {
        val e = engine()
        val cold = e.reconcile(null, discharging(0)).state

        val observed = e.accept(cold, charging(MINUTE, trigger = SessionTrigger.POWER_CONNECTED))
        val recovered = e.reconcile(cold, charging(MINUTE, trigger = SessionTrigger.APP_START))

        assertEquals(
            SessionBoundaryReason.POWER_TRANSITION,
            (observed.result as TransitionResult.Boundary).reason,
        )
        assertEquals(
            SessionBoundaryReason.RECOVERY,
            (recovered.result as TransitionResult.Boundary).reason,
        )
    }

    @Test
    fun `a real direction change always produces a new session identity`() {
        val e = engine()
        var s = e.reconcile(null, discharging(0)).state
        val ids = mutableListOf(s.session!!.id)

        s = e.accept(s, charging(HOUR, trigger = SessionTrigger.POWER_CONNECTED)).state
        ids += s.session!!.id
        s = e.accept(s, discharging(2 * HOUR, trigger = SessionTrigger.POWER_DISCONNECTED)).state
        ids += s.session!!.id

        assertEquals("each interval needs its own identity", 3, ids.toSet().size)
    }

    // ------------------------------------------------- 37-38. counters versus session boundaries

    @Test
    fun `a counter reset does not end the session`() {
        // Counters restarting does not plug the device in. The user's interval continues.
        val e = engine()
        val s = e.reconcile(null, discharging(0)).state
        val id = s.session!!.id
        val generationBefore = s.counterGeneration

        val after = e.noteCounterReset(s, CounterGenerationChange.PLATFORM_COUNTER_RESET)

        assertEquals("the interval is unaffected", id, after.session!!.id)
        assertEquals(SessionType.DISCHARGE, after.session!!.type)
        assertNotEquals("but the generation moves", generationBefore, after.counterGeneration)
    }

    @Test
    fun `a session boundary does not by itself change the counter generation`() {
        val e = engine()
        val s = e.reconcile(null, discharging(0)).state
        val generation = s.counterGeneration

        val after = e.accept(s, charging(HOUR, trigger = SessionTrigger.POWER_CONNECTED))

        assertTrue(after.result is TransitionResult.Boundary)
        assertEquals(
            "plugging in resets nothing in Android's counters",
            generation,
            after.state.counterGeneration,
        )
    }

    @Test
    fun `a boot change moves the counter generation, because counters always restart`() {
        val e = engine()
        val s = e.reconcile(null, discharging(HOUR, boot = kernelBoot("boot-a"))).state
        val generation = s.counterGeneration

        val after = e.accept(s, discharging(10_000, boot = kernelBoot("boot-b")))

        assertEquals(generation.next(), after.state.counterGeneration)
    }

    @Test
    fun `noting no counter change leaves the generation alone`() {
        val e = engine()
        val s = e.reconcile(null, discharging(0)).state
        assertEquals(s, e.noteCounterReset(s, CounterGenerationChange.NONE))
    }

    // ------------------------------------------------------ 39-40. things that must not matter

    @Test
    fun `a manual snapshot does not change the session`() {
        val e = engine()
        var s = e.reconcile(null, discharging(0)).state
        val id = s.session!!.id
        val type = s.session!!.type

        val t = e.accept(s, discharging(MINUTE, trigger = SessionTrigger.MANUAL))
        s = t.state

        assertTrue(t.result !is TransitionResult.Boundary)
        assertEquals(id, s.session!!.id)
        assertEquals(type, s.session!!.type)
    }

    @Test
    fun `a periodic sample does not change the session`() {
        val e = engine()
        var s = e.reconcile(null, discharging(0)).state
        val id = s.session!!.id

        repeat(5) { i ->
            s = e.accept(s, discharging((i + 1) * 15L * MINUTE, trigger = SessionTrigger.PERIODIC)).state
        }
        assertEquals(id, s.session!!.id)
        assertEquals(75 * MINUTE, s.session!!.elapsedMillis)
    }

    /**
     * The engine has no reference to the capability layer at all, which is the strongest
     * form this guarantee can take: a battery session is a fact about the device, and it
     * cannot move because the user changed access method or a permission was revoked.
     */
    @Test
    fun `the engine depends on nothing from the access or capability layers`() {
        val e = engine()
        var s = e.reconcile(null, discharging(0)).state
        val id = s.session!!.id
        val start = s.session!!.start.time.elapsedRealtime

        // Whatever else the application is doing, only battery observations reach the engine.
        s = e.accept(s, discharging(MINUTE)).state
        s = e.accept(s, discharging(2 * MINUTE)).state

        assertEquals(id, s.session!!.id)
        assertEquals(start, s.session!!.start.time.elapsedRealtime)
    }

    // ---------------------------------------------------------------- reconciliation details

    @Test
    fun `saved state claiming a later time than the present is rejected as inconsistent`() {
        val e = engine()
        val saved = e.reconcile(null, discharging(2 * HOUR)).state

        // Same boot, but the current reading is earlier than what was saved. Impossible.
        val after = e.reconcile(saved, discharging(HOUR, trigger = SessionTrigger.APP_START))

        val rejected = after.result as TransitionResult.Rejected
        assertEquals(TransitionResult.RejectionReason.INCONSISTENT_STATE, rejected.reason)
        assertEquals("state must be untouched", saved, after.state)
    }

    @Test
    fun `an unprovable boot relation starts a fresh interval rather than assuming continuity`() {
        // Derived identities cannot prove sameness. Adopting the old interval onto a clock
        // that might not be the same clock would invent continuity that was never measured.
        //
        // The reason is RECOVERY rather than INCONSISTENT_STATE: monotonic time progressed
        // normally, so nothing here is contradictory -- continuity simply could not be
        // established. That is UNPROVEN_CONTINUITY, which is a weaker and more honest claim
        // than either of its neighbours: INCONSISTENT_STATE means the saved timeline is
        // provably broken, and RECOVERY means a real change is known to have happened on a
        // proven same boot. Neither is true here.
        val e = engine()
        val saved = e.reconcile(
            null,
            discharging(HOUR, boot = BootIdentity.Derived(EPOCH)),
        ).state
        val oldId = saved.session!!.id

        val after = e.reconcile(
            saved,
            discharging(HOUR + MINUTE, boot = BootIdentity.Derived(EPOCH + 1_000)),
        )

        val boundary = after.result as TransitionResult.Boundary
        assertEquals(SessionBoundaryReason.UNPROVEN_CONTINUITY, boundary.reason)
        assertEquals(SessionTrigger.RECOVERY, boundary.trigger)
        assertNotEquals(
            "a fresh interval, because continuity was never proven",
            oldId,
            after.state.session!!.id,
        )
        assertNotEquals(
            "and never labelled a reboot, which nothing here proved",
            SessionBoundaryReason.BOOT_BOUNDARY,
            boundary.reason,
        )
    }

    /**
     * Replaces a test that asserted the defect.
     *
     * It previously required a derived estimate a day apart to produce a `BOOT_BOUNDARY`.
     * That is unsound: a clock correction moves the estimate without any reboot, and the
     * result was a session split labelled *device restarted* that never happened.
     *
     * Without a kernel identifier nothing here proves a reboot, so the boundary is reported
     * for what it is -- state that could not be carried forward -- and never as a boot
     * change. The monotonic reading going backwards is what makes this case
     * `INCONSISTENT_STATE` rather than `RECOVERY`.
     */
    @Test
    fun `a derived identity never produces a boot boundary, however far apart`() {
        val e = engine()
        val saved = e.reconcile(null, discharging(HOUR, boot = BootIdentity.Derived(EPOCH))).state

        val after = e.reconcile(
            saved,
            discharging(60_000, boot = BootIdentity.Derived(EPOCH + 24 * HOUR)),
        )

        val boundary = after.result as TransitionResult.Boundary
        assertNotEquals(
            "a reboot must never be claimed without proof",
            SessionBoundaryReason.BOOT_BOUNDARY,
            boundary.reason,
        )
        assertNotEquals(SessionTrigger.BOOT_CHANGED, boundary.trigger)
        assertEquals(SessionBoundaryReason.INCONSISTENT_STATE, boundary.reason)
        assertEquals(SessionTrigger.RECOVERY, boundary.trigger)
    }

    /**
     * Kernel identifier unavailable and monotonic time progressing normally.
     *
     * Nothing is known to have changed and nothing is known to be wrong, so the boundary
     * says exactly that. It is not RECOVERY -- that would assert a real change was
     * reconstructed -- and it is not INCONSISTENT_STATE, because nothing contradicts
     * anything.
     */
    @Test
    fun `an unprovable boot with time progressing normally reports unproven continuity`() {
        val e = engine()
        val saved = e.reconcile(null, discharging(HOUR, boot = BootIdentity.Derived(EPOCH))).state

        val after = e.reconcile(
            saved,
            discharging(HOUR + 30 * MINUTE, boot = BootIdentity.Derived(EPOCH)),
        )

        val boundary = after.result as TransitionResult.Boundary
        assertEquals(SessionBoundaryReason.UNPROVEN_CONTINUITY, boundary.reason)
        assertEquals(SessionTrigger.RECOVERY, boundary.trigger)
        assertNotEquals(SessionBoundaryReason.BOOT_BOUNDARY, boundary.reason)
        assertNotEquals(SessionBoundaryReason.INCONSISTENT_STATE, boundary.reason)
    }

    /**
     * The three cold-start boundaries are distinguishable from one another.
     *
     * They are different claims about what BattInsight knows, and collapsing any two would
     * put a sentence on screen that is not true.
     */
    @Test
    fun `the cold-start boundary reasons say three different things`() {
        val e = engine()

        // Proven same boot, direction genuinely changed while the process was gone.
        val known = e.reconcile(
            e.reconcile(null, discharging(0, boot = kernelBoot("k"))).state,
            charging(HOUR, boot = kernelBoot("k"), trigger = SessionTrigger.APP_START),
        ).result as TransitionResult.Boundary

        // No kernel identifier, time fine: nothing known either way.
        val unproven = e.reconcile(
            e.reconcile(null, discharging(0, boot = BootIdentity.Derived(EPOCH))).state,
            discharging(HOUR, boot = BootIdentity.Derived(EPOCH)),
        ).result as TransitionResult.Boundary

        // No kernel identifier, time went backwards: the saved timeline is disproven.
        val broken = e.reconcile(
            e.reconcile(null, discharging(2 * HOUR, boot = BootIdentity.Derived(EPOCH))).state,
            discharging(MINUTE, boot = BootIdentity.Derived(EPOCH)),
        ).result as TransitionResult.Boundary

        assertEquals(SessionBoundaryReason.RECOVERY, known.reason)
        assertEquals(SessionBoundaryReason.UNPROVEN_CONTINUITY, unproven.reason)
        assertEquals(SessionBoundaryReason.INCONSISTENT_STATE, broken.reason)
        assertEquals(
            "all three are distinct",
            3,
            setOf(known.reason, unproven.reason, broken.reason).size,
        )
        listOf(known, unproven, broken).forEach {
            assertNotEquals(
                "none of them may claim a reboot",
                SessionBoundaryReason.BOOT_BOUNDARY,
                it.reason,
            )
            assertNotEquals(SessionTrigger.BOOT_CHANGED, it.trigger)
        }
    }

    /**
     * A stale broadcast must still be refused when the identifier is unavailable.
     *
     * A live process cannot span a reboot, so consecutive observations share a boot whatever
     * the identity can prove. Gating the ordering check on a *proven* same boot would have
     * disabled it exactly when the engine can least afford to be rewound.
     */
    @Test
    fun `an out-of-order observation is rejected even without a kernel identity`() {
        val e = engine()
        val state = e.reconcile(
            null,
            discharging(10 * MINUTE, boot = BootIdentity.Derived(EPOCH)),
        ).state

        val stale = e.accept(state, discharging(5 * MINUTE, boot = BootIdentity.Derived(EPOCH)))

        val rejected = stale.result as TransitionResult.Rejected
        assertEquals(TransitionResult.RejectionReason.OUT_OF_ORDER, rejected.reason)
        assertEquals("state must be untouched", state, stale.state)
    }

    @Test
    fun `reconciling with no saved state simply starts`() {
        val t = engine().reconcile(null, discharging(0, trigger = SessionTrigger.APP_START))
        assertTrue(t.result is TransitionResult.Started)
        assertNull(t.state.session!!.end)
        assertTrue(t.state.session!!.isActive)
    }

    @Test
    fun `an active session has no end snapshot and still reports a duration`() {
        val e = engine()
        var s = e.reconcile(null, discharging(0)).state
        s = e.accept(s, discharging(42 * MINUTE)).state

        assertTrue(s.session!!.isActive)
        assertNull(s.session!!.end)
        assertEquals(42 * MINUTE, s.session!!.elapsedMillis)
    }
}

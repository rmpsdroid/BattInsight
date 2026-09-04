package com.rmpsdroid.battinsight.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the fallback boot identity may and may not conclude.
 *
 * The rule this file exists to defend: **wall-clock movement is not reboot evidence.**
 *
 * The fallback estimates when a boot began as `wallClock - elapsedRealtime`. That looks
 * stable and is not. Android's contract is explicit that `System.currentTimeMillis` may be
 * changed by the user or the network and may jump in either direction at any moment, while
 * `SystemClock.elapsedRealtime` continues undisturbed. So on one uninterrupted boot a clock
 * correction of six hours moves the estimate by six hours, and any rule that read a large
 * difference as proof of a reboot would manufacture one.
 *
 * The consequence, had it shipped, would have been a real user's discharge session split in
 * half by a network time correction, with a boundary labelled *device restarted* that never
 * happened. That is worse than an honest "cannot tell": it is a confident false statement.
 *
 * The fallback therefore proves nothing in either direction. It carries an estimate for
 * diagnostics and export, and the relation it produces is always
 * [BootRelation.UNKNOWN].
 */
class FallbackBootIdentityTest {

    // ------------------------------------------------ wall-clock movement proves nothing

    @Test
    fun `a wall clock jumping forward six hours is not evidence of a reboot`() {
        val before = BootIdentity.Derived(EPOCH)
        // Same boot; the clock gained six hours, so the estimate did too.
        val after = BootIdentity.Derived(EPOCH + 6 * HOUR)

        assertEquals(
            "a clock change must never prove a different boot",
            BootRelation.UNKNOWN,
            before.relationTo(after),
        )
    }

    @Test
    fun `a wall clock jumping backward six hours is not evidence of a reboot`() {
        val before = BootIdentity.Derived(EPOCH)
        val after = BootIdentity.Derived(EPOCH - 6 * HOUR)

        assertEquals(BootRelation.UNKNOWN, before.relationTo(after))
    }

    @Test
    fun `a timezone or DST change is not evidence of a reboot`() {
        // An offset change moves the wall clock, and with it the estimate.
        listOf(-12 * HOUR, -HOUR, HOUR, 9 * HOUR, 14 * HOUR).forEach { shift ->
            assertEquals(
                "a shift of ${shift / HOUR}h must not prove a boot change",
                BootRelation.UNKNOWN,
                BootIdentity.Derived(EPOCH).relationTo(BootIdentity.Derived(EPOCH + shift)),
            )
        }
    }

    @Test
    fun `an enormous wall-clock jump is still not evidence of a reboot`() {
        // A device whose clock was wrong by years, then corrected. Still one boot.
        val before = BootIdentity.Derived(EPOCH)
        val after = BootIdentity.Derived(EPOCH + 5L * 365 * 24 * HOUR)

        assertEquals(BootRelation.UNKNOWN, before.relationTo(after))
    }

    // ------------------------------------------------ nor does closeness prove sameness

    @Test
    fun `identical estimates do not prove the same boot`() {
        // Two separate boots can easily produce the same estimate, and equal values were
        // never proof of anything.
        assertEquals(
            BootRelation.UNKNOWN,
            BootIdentity.Derived(EPOCH).relationTo(BootIdentity.Derived(EPOCH)),
        )
    }

    @Test
    fun `a fallback identity never claims it can establish a boot relation`() {
        assertTrue(!BootIdentity.Derived(EPOCH).canProveBootRelation)
        assertTrue(!BootIdentity.Unknown.canProveBootRelation)
        assertTrue(BootIdentity.Kernel("k").canProveBootRelation)
    }

    // ---------------------------------------------------- the kernel identity still works

    @Test
    fun `equal kernel identities prove the same boot`() {
        assertEquals(
            BootRelation.SAME,
            BootIdentity.Kernel("abc").relationTo(BootIdentity.Kernel("abc")),
        )
    }

    @Test
    fun `differing kernel identities prove different boots`() {
        assertEquals(
            BootRelation.DIFFERENT,
            BootIdentity.Kernel("abc").relationTo(BootIdentity.Kernel("xyz")),
        )
    }

    @Test
    fun `a kernel identity and a fallback are never comparable to each other`() {
        assertEquals(
            BootRelation.UNKNOWN,
            BootIdentity.Kernel("abc").relationTo(BootIdentity.Derived(EPOCH)),
        )
        assertEquals(
            BootRelation.UNKNOWN,
            BootIdentity.Derived(EPOCH).relationTo(BootIdentity.Kernel("abc")),
        )
    }

    @Test
    fun `no pair of fallback identities can ever produce a proven relation`() {
        // Exhaustive over a wide spread of estimates, including extremes.
        val estimates = listOf(
            0L, 1L, EPOCH - 10L * 365 * 24 * HOUR, EPOCH - HOUR, EPOCH,
            EPOCH + 1, EPOCH + HOUR, EPOCH + 6 * HOUR, EPOCH + 10L * 365 * 24 * HOUR,
            Long.MAX_VALUE / 4,
        )
        estimates.forEach { a ->
            estimates.forEach { b ->
                assertEquals(
                    "Derived($a) vs Derived($b) must be UNKNOWN",
                    BootRelation.UNKNOWN,
                    BootIdentity.Derived(a).relationTo(BootIdentity.Derived(b)),
                )
            }
        }
    }

    // ---------------------------------------------- the estimate survives as diagnostics

    @Test
    fun `the estimate is still carried, for diagnostics and export`() {
        // Removing it would lose genuinely useful debugging information. What changed is
        // that nothing authoritative may be derived from it.
        val identity = BootIdentity.Derived(EPOCH)
        assertEquals(EPOCH, identity.approximateBootWallClockMillis)
        assertTrue(identity.abbreviated.isNotBlank())
    }

    // ------------------------------------------ the engine never manufactures a boundary

    @Test
    fun `no wall-clock movement can manufacture a boot boundary through the engine`() {
        val engine = SessionEngine(SequentialIds())
        var state = engine.reconcile(
            null,
            discharging(0, boot = BootIdentity.Derived(EPOCH), wallClockMillis = EPOCH),
        ).state
        val sessionId = state.session!!.id

        // Ten monotonic minutes pass. The clock lurches wildly throughout.
        val shifts = listOf(6 * HOUR, -12 * HOUR, HOUR, -3 * HOUR, 48 * HOUR)
        shifts.forEachIndexed { i, shift ->
            val elapsed = (i + 1) * MINUTE
            val wall = EPOCH + elapsed + shift
            val transition = engine.accept(
                state,
                discharging(
                    elapsed,
                    // The adapter recomputes the estimate from the moved clock, exactly as
                    // it would on a device.
                    boot = BootIdentity.Derived(wall - elapsed),
                    wallClockMillis = wall,
                ),
            )
            state = transition.state
            assertTrue(
                "shift ${shift / HOUR}h produced ${transition.result}",
                transition.result !is TransitionResult.Boundary,
            )
        }

        assertEquals("the session must survive every clock change", sessionId, state.session!!.id)
        assertEquals(
            "and its duration must come from the monotonic clock alone",
            5 * MINUTE,
            state.session!!.elapsedMillis,
        )
    }

    @Test
    fun `a proven kernel boot change still produces a boot boundary`() {
        // The fix must not weaken the case that genuinely is provable.
        val engine = SessionEngine(SequentialIds())
        val state = engine.reconcile(null, discharging(2 * HOUR, boot = kernelBoot("boot-a"))).state
        val sessionId = state.session!!.id

        val transition = engine.accept(state, discharging(30_000, boot = kernelBoot("boot-b")))

        val boundary = transition.result as TransitionResult.Boundary
        assertEquals(SessionBoundaryReason.BOOT_BOUNDARY, boundary.reason)
        assertEquals(SessionTrigger.BOOT_CHANGED, boundary.trigger)
        assertNotEquals(sessionId, transition.state.session!!.id)
    }
}

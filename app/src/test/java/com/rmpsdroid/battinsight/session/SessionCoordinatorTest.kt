package com.rmpsdroid.battinsight.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator's own responsibilities: sequencing, publication and saving.
 *
 * Every decision about meaning belongs to [SessionEngine] and is tested there. What is
 * checked here is that the coordinator does not corrupt those decisions on the way out —
 * in particular that a rejected observation is neither saved nor published as though it
 * had been accepted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {

    private fun coordinator(
        store: SessionStateStore = InMemorySessionStateStore(),
        scope: TestScope,
    ) = SessionCoordinator(
        engine = SessionEngine(SequentialIds()),
        store = store,
        scope = scope,
    )

    @Test
    fun `beginning with nothing saved starts a session and publishes it`() = runTest {
        val c = coordinator(scope = TestScope(testScheduler))

        val result = c.begin(discharging(0, trigger = SessionTrigger.APP_START))

        assertTrue(result is TransitionResult.Started)
        assertEquals(SessionType.DISCHARGE, c.status.value.session!!.type)
        assertTrue(c.status.value.isActive)
    }

    @Test
    fun `beginning with saved state from the same boot continues the session`() = runTest {
        val store = InMemorySessionStateStore()
        val first = coordinator(store, TestScope(testScheduler))
        first.begin(discharging(0, trigger = SessionTrigger.APP_START))
        val sessionId = first.status.value.session!!.id

        // A new process, the same store.
        val second = coordinator(store, TestScope(testScheduler))
        val result = second.begin(discharging(20 * MINUTE, trigger = SessionTrigger.APP_START))

        assertTrue("expected continuation, was $result", result is TransitionResult.Continued)
        assertEquals(sessionId, second.status.value.session!!.id)
        assertEquals(20 * MINUTE, second.status.value.session!!.elapsedMillis)
    }

    @Test
    fun `a rejected observation is neither saved nor published as accepted`() = runTest {
        val store = InMemorySessionStateStore()
        val c = coordinator(store, TestScope(testScheduler))
        c.begin(discharging(10 * MINUTE, trigger = SessionTrigger.APP_START))
        val savedBefore = store.load()
        val publishedBefore = c.status.value.session

        val result = c.observe(discharging(MINUTE))

        assertTrue(result is TransitionResult.Rejected)
        assertEquals("nothing may be saved from a rejection", savedBefore, store.load())
        assertEquals("the published session is unchanged", publishedBefore, c.status.value.session)
        assertTrue(
            "but the rejection is still visible to the UI",
            c.status.value.lastResult is TransitionResult.Rejected,
        )
    }

    @Test
    fun `each accepted observation is saved`() = runTest {
        val store = InMemorySessionStateStore()
        val c = coordinator(store, TestScope(testScheduler))
        c.begin(discharging(0, trigger = SessionTrigger.APP_START))
        c.observe(discharging(MINUTE))
        c.observe(charging(2 * MINUTE, trigger = SessionTrigger.POWER_CONNECTED))

        // load() now returns a typed result: an unreadable store is distinguishable from
        // an empty one, so a plain null no longer conflates the two.
        val saved = store.load()
        assertTrue("expected loaded state, was $saved", saved is StoredState.Loaded)
        assertEquals(SessionType.CHARGE, (saved as StoredState.Loaded).state.session!!.type)
    }

    @Test
    fun `a counter reset is published without ending the session`() = runTest {
        val c = coordinator(scope = TestScope(testScheduler))
        c.begin(discharging(0, trigger = SessionTrigger.APP_START))
        val sessionId = c.status.value.session!!.id
        val generation = c.status.value.counterGeneration

        c.noteCounterReset(CounterGenerationChange.PLATFORM_COUNTER_RESET)

        assertEquals("the interval is untouched", sessionId, c.status.value.session!!.id)
        assertEquals(generation.next(), c.status.value.counterGeneration)
    }

    @Test
    fun `status before any observation claims nothing`() = runTest {
        val c = coordinator(scope = TestScope(testScheduler))
        assertNull(c.status.value.session)
        assertNull(c.status.value.lastObservation)
        assertNull(c.status.value.lastResult)
        assertEquals(BootIdentity.Unknown, c.status.value.bootIdentity)
        assertTrue(!c.status.value.isActive)
    }

    @Test
    fun `the published boot identity comes from the last accepted observation`() = runTest {
        val c = coordinator(scope = TestScope(testScheduler))
        c.begin(discharging(0, boot = kernelBoot("boot-x"), trigger = SessionTrigger.APP_START))

        assertEquals(kernelBoot("boot-x"), c.status.value.bootIdentity)
    }

    /**
     * The store is the seam Phase 6 will implement durably. With the in-memory one, a
     * genuinely new process has nothing to load — which is stated in the type's own
     * documentation rather than hidden, and is the reason cold-start reconciliation exists.
     */
    @Test
    fun `an empty store means a cold start begins a fresh interval`() = runTest {
        val first = coordinator(InMemorySessionStateStore(), TestScope(testScheduler))
        first.begin(discharging(0, trigger = SessionTrigger.APP_START))

        // A new process with a new in-memory store: nothing survived.
        val second = coordinator(InMemorySessionStateStore(), TestScope(testScheduler))
        val result = second.begin(discharging(HOUR, trigger = SessionTrigger.APP_START))

        // Identity is not the discriminator here: the two coordinators hold independent
        // deterministic id factories, so both mint the same first UUID. What distinguishes
        // a fresh interval from a continued one is where it starts and how long it claims
        // to have run.
        assertTrue("expected a fresh start, was $result", result is TransitionResult.Started)
        assertEquals(
            "a fresh interval starts at the observation that opened it",
            HOUR,
            second.status.value.session!!.start.time.elapsedRealtime.millis,
        )
        assertEquals(
            "and has accrued no duration yet",
            0L,
            second.status.value.session!!.elapsedMillis,
        )
    }
}

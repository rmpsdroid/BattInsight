package com.rmpsdroid.battinsight.session

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the coordinator publishes when the database will not cooperate.
 *
 * The failure mode this guards against is specific and quiet: an application that accepts an
 * observation, shows a session, and has stored nothing. It would look completely correct
 * until the next process death, at which point the interval it had been reporting for hours
 * simply would not be there.
 *
 * So persistence is not an afterthought of the transition -- it gates it.
 */
class CoordinatorPersistenceTest {

    private val boot = BootIdentity.Kernel("boot-under-test")

    private fun observation(
        elapsedMillis: Long,
        attached: Boolean = false,
        trigger: SessionTrigger = SessionTrigger.BATTERY_CHANGED,
    ) = BatteryObservation(
        time = CaptureTime(ElapsedRealtime(elapsedMillis), 1_700_000_000_000L + elapsedMillis, 0),
        bootIdentity = boot,
        status = if (attached) BatteryStatus.CHARGING else BatteryStatus.DISCHARGING,
        plug = if (attached) PlugSource.AC else PlugSource.NONE,
        level = 50,
        scale = 100,
        trigger = trigger,
    )

    /** A store that refuses every write, and remembers nothing. */
    private class RefusingStore(
        private val outcome: PersistenceOutcome = PersistenceOutcome.DATABASE_UNAVAILABLE,
        private val stored: StoredState = StoredState.Empty,
    ) : SessionStateStore {
        var writes = 0
            private set

        override suspend fun load(): StoredState = stored

        override suspend fun persist(transition: SessionTransition): PersistenceResult {
            writes++
            return PersistenceResult.Failure(outcome, "refused by test")
        }

        override suspend fun saveState(state: SessionEngineState): PersistenceResult {
            writes++
            return PersistenceResult.Failure(outcome, "refused by test")
        }

        override suspend fun clear() = PersistenceResult.Failure(outcome, "refused by test")
    }

    // ---------------------------------------------------------- a failed write is not success

    @Test
    fun `a refused write is reported, and no session is published`() = runTest {
        val store = RefusingStore()
        val coordinator = SessionCoordinator(store = store, scope = TestScope())

        coordinator.begin(observation(0, trigger = SessionTrigger.APP_START))

        val status = coordinator.status.value
        assertEquals("the write must have been attempted", 1, store.writes)
        assertTrue(
            "a session that was not stored must not be published",
            status.session == null,
        )
        assertNotNull("and the failure must be visible", status.persistence?.failureOrNull)
        assertEquals(
            PersistenceOutcome.DATABASE_UNAVAILABLE,
            status.persistence!!.failureOrNull!!.outcome,
        )
    }

    @Test
    fun `state does not advance past a write that failed`() = runTest {
        val store = RefusingStore()
        val coordinator = SessionCoordinator(store = store, scope = TestScope())

        coordinator.begin(observation(0, trigger = SessionTrigger.APP_START))
        val afterFirst = coordinator.status.value

        // A second observation that would ordinarily open a charge interval.
        coordinator.observe(observation(HOUR, attached = true, trigger = SessionTrigger.POWER_CONNECTED))

        assertEquals(
            "nothing may have been adopted",
            afterFirst.session,
            coordinator.status.value.session,
        )
        assertTrue(coordinator.status.value.persistence!!.failureOrNull != null)
    }

    // -------------------------------------------------------- an unreadable store is not empty

    /**
     * Start-up with a store that exists but cannot be read.
     *
     * Reconciling from null here is correct -- there is nothing else to reconcile from -- but
     * it must be done *knowingly*. The distinction reaches the UI as [SessionStatus.loadFailure]
     * so the application can say it lost sight of history rather than implying there was none.
     */
    @Test
    fun `an unreadable store surfaces as a load failure rather than a fresh start`() = runTest {
        val store = object : SessionStateStore {
            override suspend fun load() =
                StoredState.Failed(PersistenceOutcome.CORRUPT_STATE, "unreadable")
            override suspend fun persist(transition: SessionTransition) = PersistenceResult.Success
            override suspend fun saveState(state: SessionEngineState) = PersistenceResult.Success
            override suspend fun clear() = PersistenceResult.Success
        }
        val coordinator = SessionCoordinator(store = store, scope = TestScope())

        coordinator.begin(observation(0, trigger = SessionTrigger.APP_START))

        val status = coordinator.status.value
        assertNotNull("the load failure must reach the UI", status.loadFailure)
        assertEquals(PersistenceOutcome.CORRUPT_STATE, status.loadFailure!!.outcome)
        // Reconciliation still proceeded: a new interval exists, and it was storable.
        assertNotNull("a fresh interval still starts", status.session)
        assertTrue(status.persistence!!.succeeded)
    }

    @Test
    fun `a successful load reports no failure`() = runTest {
        val coordinator = SessionCoordinator(store = InMemorySessionStateStore(), scope = TestScope())

        coordinator.begin(observation(0, trigger = SessionTrigger.APP_START))

        assertNull(coordinator.status.value.loadFailure)
        assertTrue(coordinator.status.value.persistence!!.succeeded)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}

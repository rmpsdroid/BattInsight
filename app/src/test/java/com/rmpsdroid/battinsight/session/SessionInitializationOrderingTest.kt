package com.rmpsdroid.battinsight.session

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An observation arriving before start-up must not invent a new interval.
 *
 * ## The defect this pins (Phase 10A.2, P1)
 *
 * A Samsung SM-M156B split one continuous discharge interval into two open sessions after an
 * ordinary process restart. Nothing was wrong with the stored state -- it was re-read
 * afterwards and proved fully loadable -- and no boot, power attachment or session type
 * changed. Production simply has two independently scheduled start-up coroutines:
 *
 * ```
 * viewModelScope.launch { readCurrent(APP_START)?.let { sessions.begin(it) } }        // A
 * lifecycleScope.launch { repeatOnLifecycle(STARTED) { sampleOnBecomingVisible() } }  // B
 * ```
 *
 * When **B** reached the coordinator first, `SessionEngine.accept` saw
 * `SessionEngineState.empty`, took its `?: return start(...)` branch, and opened a fresh
 * session on top of the stored one -- which was then never reconciled and never closed.
 * Measured signature: two `APP_START` snapshots 2 ms apart, only one `battery_sample`.
 *
 * `begin` used to document "call once per process, before observe" and trust callers to obey
 * it. These tests hold the stronger property: **whichever call arrives first initialises**, so
 * the ordering cannot be got wrong by a caller, present or future.
 *
 * Every case is deterministic. The unsafe interleaving is produced by holding `load()` open on
 * a [CompletableDeferred], never by sleeps or thread races.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionInitializationOrderingTest {

    // ------------------------------------------------------------------ the defect itself

    @Test
    fun `an observation arriving before begin adopts the stored interval`() = runTest {
        val prior = seedStore()
        val store = RecordingStore(prior.store)
        val c = coordinator(store)

        // B wins the race and is the only call to reach the coordinator.
        c.observe(discharging(10 * MINUTE, trigger = SessionTrigger.APP_VISIBLE))

        assertEquals(
            "the stored interval must be adopted, not replaced",
            prior.sessionId,
            c.status.value.session?.id,
        )
        assertEquals("initialisation reads storage exactly once", 1, store.loads)
    }

    @Test
    fun `the interleaving that caused the physical split is now safe`() = runTest {
        val prior = seedStore()
        val gate = CompletableDeferred<Unit>()
        val store = RecordingStore(prior.store, blockLoadOn = gate)
        val c = coordinator(store)

        // begin() is held inside load(); observe() reaches the coordinator meanwhile.
        val beginJob = launch { c.begin(discharging(5 * MINUTE, trigger = SessionTrigger.APP_START)) }
        val observeJob = launch { c.observe(discharging(6 * MINUTE, trigger = SessionTrigger.APP_VISIBLE)) }

        gate.complete(Unit)
        beginJob.join()
        observeJob.join()

        assertEquals("one interval, not two", prior.sessionId, c.status.value.session?.id)
        assertEquals("exactly one load-and-reconcile", 1, store.loads)
    }

    @Test
    fun `many observations queued before initialisation still yield one interval`() = runTest {
        val prior = seedStore()
        val gate = CompletableDeferred<Unit>()
        val store = RecordingStore(prior.store, blockLoadOn = gate)
        val c = coordinator(store)

        val jobs = (1..5).map { i ->
            launch { c.observe(discharging(i * MINUTE + 5 * MINUTE)) }
        }
        gate.complete(Unit)
        jobs.forEach { it.join() }

        assertEquals(prior.sessionId, c.status.value.session?.id)
        assertEquals("storage is read once however many observers queue", 1, store.loads)
    }

    // ------------------------------------------------------- ordinary ordering unchanged

    @Test
    fun `begin before observe behaves exactly as before`() = runTest {
        val prior = seedStore()
        val store = RecordingStore(prior.store)
        val c = coordinator(store)

        c.begin(discharging(5 * MINUTE, trigger = SessionTrigger.APP_START))
        c.observe(discharging(6 * MINUTE))

        assertEquals(prior.sessionId, c.status.value.session?.id)
        assertEquals(1, store.loads)
    }

    @Test
    fun `a duplicate begin is one logical initialisation`() = runTest {
        val prior = seedStore()
        val store = RecordingStore(prior.store)
        val c = coordinator(store)

        c.begin(discharging(5 * MINUTE, trigger = SessionTrigger.APP_START))
        c.begin(discharging(6 * MINUTE, trigger = SessionTrigger.APP_START))

        assertEquals("a second begin must not re-reconcile", 1, store.loads)
        assertEquals(prior.sessionId, c.status.value.session?.id)
    }

    @Test
    fun `concurrent duplicate begins do not both reconcile`() = runTest {
        val prior = seedStore()
        val gate = CompletableDeferred<Unit>()
        val store = RecordingStore(prior.store, blockLoadOn = gate)
        val c = coordinator(store)

        val a = launch { c.begin(discharging(5 * MINUTE, trigger = SessionTrigger.APP_START)) }
        val b = launch { c.begin(discharging(6 * MINUTE, trigger = SessionTrigger.APP_START)) }
        gate.complete(Unit)
        a.join(); b.join()

        assertEquals(1, store.loads)
        assertEquals(prior.sessionId, c.status.value.session?.id)
    }

    // ----------------------------------------------------------- stored-state variants

    @Test
    fun `an empty store still starts exactly one genuine interval`() = runTest {
        val store = RecordingStore(InMemorySessionStateStore())
        val c = coordinator(store)

        c.observe(discharging(0, trigger = SessionTrigger.APP_VISIBLE))
        val first = c.status.value.session?.id
        c.observe(discharging(MINUTE))

        assertNotNull(first)
        assertEquals("the second observation joins the first interval", first, c.status.value.session?.id)
        assertEquals(1, store.loads)
    }

    @Test
    fun `an unreadable store still surfaces a load failure and still starts knowingly`() = runTest {
        val store = FixedStore(StoredState.Failed(PersistenceOutcome.CORRUPT_STATE, "unreadable"))
        val c = coordinator(store)

        // The conservative path is unchanged even when observe() is the first caller.
        c.observe(discharging(0, trigger = SessionTrigger.APP_VISIBLE))

        assertNotNull("the load failure must still reach the UI", c.status.value.loadFailure)
        assertEquals(PersistenceOutcome.CORRUPT_STATE, c.status.value.loadFailure!!.outcome)
        assertNotNull("a fresh interval still starts", c.status.value.session)
    }

    // ---------------------------------------------------- reconciliation still reconciles

    @Test
    fun `a real power change while the process was dead is still a boundary`() = runTest {
        val prior = seedStore()
        val c = coordinator(RecordingStore(prior.store))

        // The stored interval was DISCHARGE; the device came back plugged in.
        val result = c.observe(charging(20 * MINUTE, trigger = SessionTrigger.APP_VISIBLE))

        assertTrue("a genuine transition must still open a new interval", result is TransitionResult.Boundary)
        assertEquals(SessionType.CHARGE, c.status.value.session?.type)
        assertNotEquals(prior.sessionId, c.status.value.session?.id)
    }

    @Test
    fun `a different boot is still a boundary when observe initialises`() = runTest {
        val prior = seedStore(boot = kernelBoot("boot-a"))
        val c = coordinator(RecordingStore(prior.store))

        val result = c.observe(
            discharging(20 * MINUTE, boot = kernelBoot("boot-b"), trigger = SessionTrigger.APP_VISIBLE),
        )

        assertTrue("a reboot must still end the previous interval", result is TransitionResult.Boundary)
        assertNotEquals(prior.sessionId, c.status.value.session?.id)
    }

    @Test
    fun `a contradictory monotonic reading is still refused, not initialised over`() = runTest {
        val prior = seedStore(latestElapsed = 10 * MINUTE)
        val c = coordinator(RecordingStore(prior.store))

        // Same boot, but the reading predates the saved state.
        val result = c.observe(discharging(MINUTE, trigger = SessionTrigger.APP_VISIBLE))

        assertTrue("the reading must be rejected: $result", result is TransitionResult.Rejected)

        // A rejection adopts nothing, so the coordinator is deliberately still uninitialised.
        // The next sane reading must reconcile against the stored interval -- not be accepted
        // against empty state, which would split the session one step later.
        c.observe(discharging(20 * MINUTE))
        assertEquals(
            "the stored interval must survive a contradictory reading",
            prior.sessionId,
            c.status.value.session?.id,
        )
    }

    // -------------------------------------------------------- cancellation and failure

    @Test
    fun `cancelling an early observer does not break initialisation for anyone else`() = runTest {
        val prior = seedStore()
        val gate = CompletableDeferred<Unit>()
        val store = RecordingStore(prior.store, blockLoadOn = gate)
        val c = coordinator(store)

        val cancelled = launch { c.observe(discharging(5 * MINUTE, trigger = SessionTrigger.APP_VISIBLE)) }
        cancelled.cancel()
        cancelled.join()

        gate.complete(Unit)
        c.observe(discharging(6 * MINUTE))

        assertEquals(
            "one observer's cancellation must not poison shared initialisation",
            prior.sessionId,
            c.status.value.session?.id,
        )
        assertNull(c.status.value.loadFailure)
    }

    @Test
    fun `a throwing store does not wedge the coordinator forever`() = runTest {
        val prior = seedStore()
        val store = ThrowOnceStore(prior.store)
        val c = coordinator(store)

        var threw = false
        try {
            c.observe(discharging(5 * MINUTE, trigger = SessionTrigger.APP_VISIBLE))
        } catch (t: IllegalStateException) {
            threw = true
        }
        assertTrue("the failure is surfaced, not swallowed into a fresh interval", threw)

        // The coordinator is still usable: the next observation initialises properly.
        c.observe(discharging(6 * MINUTE))
        assertEquals(prior.sessionId, c.status.value.session?.id)
    }

    // --------------------------------------------------------------------------- helpers

    private class Prior(val store: SessionStateStore, val sessionId: UUID)

    /** Seeds a real store by running a first coordinator, as a previous process would have. */
    private suspend fun TestScope.seedStore(
        boot: BootIdentity = kernelBoot(),
        latestElapsed: Long = MINUTE,
    ): Prior {
        val store = InMemorySessionStateStore()
        val first = SessionCoordinator(store = store, scope = this)
        first.begin(discharging(0, boot = boot, trigger = SessionTrigger.APP_START))
        first.observe(discharging(latestElapsed, boot = boot))
        return Prior(store, first.status.value.session!!.id)
    }

    private fun TestScope.coordinator(store: SessionStateStore) =
        SessionCoordinator(store = store, scope = this)

    /** Counts reads and can hold `load()` open, so an interleaving is deterministic. */
    private class RecordingStore(
        private val delegate: SessionStateStore,
        private val blockLoadOn: CompletableDeferred<Unit>? = null,
    ) : SessionStateStore {
        var loads: Int = 0
            private set

        override suspend fun load(): StoredState {
            loads++
            blockLoadOn?.await()
            return delegate.load()
        }

        override suspend fun persist(transition: SessionTransition) = delegate.persist(transition)
        override suspend fun saveState(state: SessionEngineState) = delegate.saveState(state)
        override suspend fun clear() = delegate.clear()
    }

    /** Always returns one typed result. */
    private class FixedStore(private val result: StoredState) : SessionStateStore {
        override suspend fun load() = result
        override suspend fun persist(transition: SessionTransition) = PersistenceResult.Success
        override suspend fun saveState(state: SessionEngineState) = PersistenceResult.Success
        override suspend fun clear() = PersistenceResult.Success
    }

    /** Throws on the first read, then delegates. Models a failure outside the typed path. */
    private class ThrowOnceStore(private val delegate: SessionStateStore) : SessionStateStore {
        private var calls = 0
        override suspend fun load(): StoredState {
            calls++
            if (calls == 1) throw IllegalStateException("store unavailable")
            return delegate.load()
        }

        override suspend fun persist(transition: SessionTransition) = delegate.persist(transition)
        override suspend fun saveState(state: SessionEngineState) = delegate.saveState(state)
        override suspend fun clear() = delegate.clear()
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}

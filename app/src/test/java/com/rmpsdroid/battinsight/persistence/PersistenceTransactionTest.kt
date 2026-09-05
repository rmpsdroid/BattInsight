package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.CounterGenerationChange
import com.rmpsdroid.battinsight.session.PersistenceOutcome
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionEngine
import com.rmpsdroid.battinsight.session.SessionEngineState
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType
import com.rmpsdroid.battinsight.session.StoredState
import com.rmpsdroid.battinsight.session.TransitionResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Atomicity, and what the engine's own transitions look like once stored.
 *
 * A session boundary is several durable facts at once: an interval ends, another begins,
 * snapshots appear, and the current engine state moves. If a crash could land between them
 * the database would hold two active sessions, or none, or an engine state pointing at a
 * row that was never written. These check that it cannot.
 *
 * The engine used here is the real one. Persisting a hand-built graph would test the mapper;
 * persisting what the engine actually produces tests the thing the application does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PersistenceTransactionTest {

    private lateinit var db: BattInsightDatabase
    private lateinit var store: RoomSessionStateStore
    private val engine = SessionEngine()
    private val boot = BootIdentity.Kernel("boot-under-test")

    @Before
    fun setUp() {
        db = testDatabase()
        store = RoomSessionStateStore(db.sessionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun observation(
        elapsedMillis: Long,
        attached: Boolean = false,
        trigger: SessionTrigger = SessionTrigger.BATTERY_CHANGED,
        bootIdentity: BootIdentity = boot,
    ) = com.rmpsdroid.battinsight.session.BatteryObservation(
        time = com.rmpsdroid.battinsight.session.CaptureTime(
            com.rmpsdroid.battinsight.session.ElapsedRealtime(elapsedMillis),
            EPOCH + elapsedMillis,
            0,
        ),
        bootIdentity = bootIdentity,
        status = if (attached) BatteryStatus.CHARGING else BatteryStatus.DISCHARGING,
        plug = if (attached) PlugSource.AC else PlugSource.NONE,
        level = 50,
        scale = 100,
        trigger = trigger,
    )

    // -------------------------------------------------------------- 15, 17. atomic boundary

    @Test
    fun `a boundary closes one session and opens another in a single transaction`() = runTest {
        var state = engine.reconcile(null, observation(0, trigger = SessionTrigger.APP_START)).state
        assertTrue(store.saveState(state).succeeded)
        val firstId = state.session!!.id

        val transition = engine.accept(
            state,
            observation(HOUR, attached = true, trigger = SessionTrigger.POWER_CONNECTED),
        )
        assertTrue("the boundary must persist", store.persist(transition).succeeded)
        state = transition.state

        // Both intervals are durable: the one that ended and the one that began.
        assertEquals(2, db.sessionDao().sessionCount())
        val ended = db.sessionDao().session(firstId.toString())!!
        assertNotEquals("the closed interval must have an end snapshot", null, ended.endSnapshotId)
        assertEquals(SessionBoundaryReason.POWER_TRANSITION.name, ended.endReason)

        // And exactly one is still open.
        val active = db.sessionDao().activeSessions()
        assertEquals("exactly one interval may be open", 1, active.size)
        assertEquals(state.session!!.id.toString(), active.single().sessionId)
    }

    @Test
    fun `a full lifecycle leaves exactly one active session throughout`() = runTest {
        var state = engine.reconcile(null, observation(0, trigger = SessionTrigger.APP_START)).state
        store.saveState(state)

        // Plug in, unplug, plug in again.
        listOf(true, false, true).forEachIndexed { i, attached ->
            val transition = engine.accept(
                state,
                observation((i + 1) * HOUR, attached = attached, trigger = SessionTrigger.POWER_CONNECTED),
            )
            assertTrue(store.persist(transition).succeeded)
            state = transition.state
            assertEquals(
                "after transition $i there must be exactly one open interval",
                1,
                db.sessionDao().activeSessions().size,
            )
        }

        assertEquals("four intervals in total", 4, db.sessionDao().sessionCount())
    }

    // ------------------------------------------------------- 16. a failed write changes nothing

    @Test
    fun `a failed transaction leaves the previous state entirely intact`() = runTest {
        val good = engine.reconcile(null, observation(0, trigger = SessionTrigger.APP_START))
        assertTrue(store.persist(good).succeeded)
        val before = (store.load() as StoredState.Loaded).state
        val countsBefore = store.counts()

        // A violating write through the real transaction: sessions naming snapshots that are
        // deliberately not in the same batch. Going through the DAO directly is what makes
        // this a genuine test -- an earlier attempt wrapped the DAO in a delegating object to
        // drop the snapshots, and Kotlin's interface delegation forwarded the Room-generated
        // persistTransition straight past the override, so nothing was actually broken.
        val orphanSession = Mappers.toEntity(
            activeSession(id = uuid(777), start = fullSnapshot(id = uuid(778), sessionId = uuid(777))),
        )
        var threw = false
        try {
            db.sessionDao().persistTransition(
                snapshots = emptyList(),
                sessions = listOf(orphanSession),
                engineState = EngineStateEntity(
                    sessionId = orphanSession.sessionId,
                    lastAcceptedSnapshotId = null,
                    counterGeneration = 9,
                ),
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            threw = true
        }

        assertTrue("the constraint must refuse the write", threw)
        assertEquals("nothing may have changed", before, (store.load() as StoredState.Loaded).state)
        assertEquals(countsBefore, store.counts())
        assertNull("and the orphan must not exist", db.sessionDao().session(orphanSession.sessionId))
    }

    @Test
    fun `a constraint failure is classified rather than thrown at the caller`() = runTest {
        val orphan = SessionEngineState(
            session = activeSession(id = uuid(777), start = fullSnapshot(id = uuid(778), sessionId = uuid(777))),
            lastAccepted = null,
            counterGeneration = CounterGeneration(9),
        )
        // A DAO that writes the sessions but silently drops the snapshots they depend on.
        val droppingDao = DroppingSnapshotDao(db.sessionDao())

        val result = RoomSessionStateStore(droppingDao).saveState(orphan)

        assertTrue("the write must be reported as failed", !result.succeeded)
        assertEquals(PersistenceOutcome.CONSTRAINT_FAILURE, result.failureOrNull!!.outcome)
        assertTrue(result.failureOrNull!!.detail.isNotBlank())
    }

    // ------------------------------------------------ 20-23. recovery through the database

    @Test
    fun `a process restart on the same boot recovers the same session identity`() = runTest {
        // First process: establish an interval and persist it.
        val first = engine.reconcile(null, observation(0, trigger = SessionTrigger.APP_START))
        assertTrue(store.persist(first).succeeded)
        val originalId = first.state.session!!.id

        // Second process: a new store over the same database, loading what was written.
        val reopened = RoomSessionStateStore(db.sessionDao())
        val loaded = reopened.load()
        assertTrue("state must survive", loaded is StoredState.Loaded)

        val recovered = engine.reconcile(
            (loaded as StoredState.Loaded).state,
            observation(30 * MINUTE, trigger = SessionTrigger.APP_START),
        )

        assertTrue("expected continuation, was ${recovered.result}", recovered.result is TransitionResult.Continued)
        assertEquals(
            "process death is not a session boundary, and persistence is what makes that true",
            originalId,
            recovered.state.session!!.id,
        )
        assertEquals(30 * MINUTE, recovered.state.session!!.elapsedMillis)
    }

    @Test
    fun `a proven boot change after a restart opens a new interval`() = runTest {
        val first = engine.reconcile(null, observation(HOUR, trigger = SessionTrigger.APP_START))
        store.persist(first)
        val originalId = first.state.session!!.id

        val loaded = (store.load() as StoredState.Loaded).state
        val afterReboot = engine.reconcile(
            loaded,
            observation(30_000, trigger = SessionTrigger.APP_START, bootIdentity = BootIdentity.Kernel("a-different-boot")),
        )
        assertTrue(store.persist(afterReboot).succeeded)

        val boundary = afterReboot.result as TransitionResult.Boundary
        assertEquals(SessionBoundaryReason.BOOT_BOUNDARY, boundary.reason)
        assertNotEquals(originalId, afterReboot.state.session!!.id)
        assertEquals("both intervals are stored", 2, db.sessionDao().sessionCount())
        assertEquals(1, db.sessionDao().activeSessions().size)
    }

    @Test
    fun `unprovable continuity after a restart opens a fresh interval`() = runTest {
        val fallback = BootIdentity.Derived(EPOCH)
        val first = engine.reconcile(
            null,
            observation(HOUR, trigger = SessionTrigger.APP_START, bootIdentity = fallback),
        )
        store.persist(first)
        val originalId = first.state.session!!.id

        val loaded = (store.load() as StoredState.Loaded).state
        val after = engine.reconcile(
            loaded,
            observation(HOUR + MINUTE, trigger = SessionTrigger.APP_START, bootIdentity = fallback),
        )
        assertTrue(store.persist(after).succeeded)

        val boundary = after.result as TransitionResult.Boundary
        assertEquals(SessionBoundaryReason.UNPROVEN_CONTINUITY, boundary.reason)
        assertNotEquals(originalId, after.state.session!!.id)
        assertNotEquals(
            "and it is never labelled a reboot, which nothing proved",
            SessionTrigger.BOOT_CHANGED,
            boundary.trigger,
        )
    }

    @Test
    fun `a rolled-back monotonic clock after a restart is reported as inconsistent`() = runTest {
        val fallback = BootIdentity.Derived(EPOCH)
        val first = engine.reconcile(
            null,
            observation(2 * HOUR, trigger = SessionTrigger.APP_START, bootIdentity = fallback),
        )
        store.persist(first)

        val loaded = (store.load() as StoredState.Loaded).state
        val after = engine.reconcile(
            loaded,
            observation(MINUTE, trigger = SessionTrigger.APP_START, bootIdentity = fallback),
        )

        assertEquals(
            SessionBoundaryReason.INCONSISTENT_STATE,
            (after.result as TransitionResult.Boundary).reason,
        )
    }

    // --------------------------------------------- 31. generation is independent of identity

    @Test
    fun `a counter reset changes the generation without ending the interval`() = runTest {
        val first = engine.reconcile(null, observation(0, trigger = SessionTrigger.APP_START))
        store.persist(first)
        val sessionId = first.state.session!!.id
        val before = first.state.counterGeneration

        val afterReset = engine.noteCounterReset(first.state, CounterGenerationChange.PLATFORM_COUNTER_RESET)
        assertTrue(store.saveState(afterReset).succeeded)

        val loaded = (store.load() as StoredState.Loaded).state
        assertEquals("the interval is untouched", sessionId, loaded.session!!.id)
        assertNotEquals("but the generation moved", before, loaded.counterGeneration)
        assertEquals(before.next(), loaded.counterGeneration)
        assertEquals(1, db.sessionDao().sessionCount())
    }

    // --------------------------------------------------------------- 35. close and reopen

    @Test
    fun `state survives closing and reopening the database`() = runTest {
        // A file-backed database, because an in-memory one is defined to vanish on close --
        // which would make this test prove nothing.
        val file = java.io.File.createTempFile("battinsight-reopen", ".db").also { it.delete() }
        try {
            val first = androidx.room3.Room.databaseBuilder(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                BattInsightDatabase::class.java,
                file.absolutePath,
            ).build()
            val transition = engine.reconcile(null, observation(0, trigger = SessionTrigger.APP_START))
            assertTrue(RoomSessionStateStore(first.sessionDao()).persist(transition).succeeded)
            val sessionId = transition.state.session!!.id
            first.close()

            val second = androidx.room3.Room.databaseBuilder(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                BattInsightDatabase::class.java,
                file.absolutePath,
            ).build()
            val loaded = RoomSessionStateStore(second.sessionDao()).load()
            assertTrue("expected the state to survive, was $loaded", loaded is StoredState.Loaded)
            assertEquals(sessionId, (loaded as StoredState.Loaded).state.session!!.id)
            assertEquals(SessionType.DISCHARGE, loaded.state.session!!.type)
            second.close()
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------------- a closed database

    /**
     * A closed database is reported, not swallowed and not rethrown.
     *
     * Worth stating what this replaced. Under Room 2.8.4 both halves of this were different
     * and worse: a plain DAO call threw `JobCancellationException` from Room's own internal
     * scope, so the obvious handler -- rethrow every `CancellationException` -- would have
     * cancelled the session coordinator *because a database went away*; and a `@Transaction`
     * did not fail at all, because Room reopened the database underneath it and wrote into
     * the reopened one.
     *
     * Room 3.0.2 throws `IllegalStateException("Database is closed")` from both paths. That
     * is an ordinary, documented failure, so the store classifies it and the special-case
     * cancellation handling that Room 2 required is gone rather than carried forward.
     */
    @Test
    fun `writing to a closed database is reported, not swallowed`() = runTest {
        val transition = engine.reconcile(null, observation(0, trigger = SessionTrigger.APP_START))
        db.close()

        val result = store.persist(transition)

        assertTrue("a closed database must not report success", !result.succeeded)
        assertEquals(PersistenceOutcome.DATABASE_UNAVAILABLE, result.failureOrNull!!.outcome)
        assertTrue("and it must explain itself", result.failureOrNull!!.detail.isNotBlank())
    }

    @Test
    fun `loading from a closed database reports failure rather than empty`() = runTest {
        db.close()

        val loaded = store.load()

        assertTrue("expected a failure, was $loaded", loaded is StoredState.Failed)
        assertEquals(
            "an unreadable store is never an empty one",
            PersistenceOutcome.DATABASE_UNAVAILABLE,
            (loaded as StoredState.Failed).outcome,
        )
        assertNull("counts are unavailable too, and say so", store.counts())
    }
}

/**
 * A DAO that performs every write except the snapshots, so the sessions that reference them
 * violate their foreign key.
 *
 * Written out rather than delegated: Kotlin interface delegation forwards the Room-generated
 * `persistTransition` to the real DAO, which would bypass an override of `upsertSnapshots`
 * entirely and quietly make the test pass for the wrong reason.
 */
internal class DroppingSnapshotDao(private val real: SessionDao) : SessionDao {
    override suspend fun engineState(id: Int) = real.engineState(id)
    override suspend fun session(sessionId: String) = real.session(sessionId)
    override suspend fun snapshots(ids: List<String>) = real.snapshots(ids)
    override suspend fun sessionCount() = real.sessionCount()
    override suspend fun snapshotCount() = real.snapshotCount()
    override suspend fun activeSessions() = real.activeSessions()
    override suspend fun upsertSnapshots(snapshots: List<SnapshotEntity>) = Unit
    override suspend fun upsertSessions(sessions: List<SessionEntity>) = real.upsertSessions(sessions)
    override suspend fun upsertEngineState(state: EngineStateEntity) = real.upsertEngineState(state)
    override suspend fun deleteEngineState() = real.deleteEngineState()
    override suspend fun deleteSessionCounterState() = real.deleteSessionCounterState()
    override suspend fun deleteCounterCaptures() = real.deleteCounterCaptures()
    override suspend fun deleteSessions() = real.deleteSessions()
    override suspend fun deleteSnapshots() = real.deleteSnapshots()
}

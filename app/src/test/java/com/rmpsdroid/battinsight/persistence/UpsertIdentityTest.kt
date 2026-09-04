package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.session.PersistenceOutcome
import com.rmpsdroid.battinsight.session.SessionTrigger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An ordinary update must change a row, not replace it.
 *
 * This suite exists because of a measured defect. The DAO's writes were
 * `@Insert(onConflict = REPLACE)`, which Room compiles to SQLite's `INSERT OR REPLACE`, and
 * SQLite resolves a primary-key collision there by **deleting** the existing row and
 * inserting a new one. Advancing a session's `latest_snapshot_id` -- the most ordinary write
 * this application performs, happening on every accepted observation -- was therefore
 * destroying and recreating the session row.
 *
 * The measurement that proved it was the rowid:
 *
 * ```
 * PROBE-REPLACE rowidBefore=1 rowidAfter=2 identityPreserved=false engineStateSurvived=true
 * ```
 *
 * Nothing was lost, and that is the uncomfortable part. It survived only because every
 * foreign key in this schema is `NO_ACTION` and the reinsert lands inside the same statement,
 * so no immediate constraint is violated at statement end. That is a coincidence of today's
 * schema rather than a property of the operation: the first child table declared with
 * `ON DELETE CASCADE` would have its rows silently deleted by what reads as a field update.
 *
 * So these tests assert row identity directly rather than only asserting that the data looks
 * right afterwards. `INSERT OR REPLACE` passes a contents check; it fails this one.
 *
 * That claim is itself tested. Temporarily restoring `@Insert(onConflict = REPLACE)` on the
 * DAO fails exactly three of these -- the two row-identity tests and the end-to-end transition
 * -- which is what makes them evidence rather than decoration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class UpsertIdentityTest {

    private lateinit var db: BattInsightDatabase
    private lateinit var store: RoomSessionStateStore

    @Before
    fun setUp() {
        db = testDatabase()
        store = RoomSessionStateStore(db.sessionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The rowid is SQLite's own identity for the row, and a REPLACE changes it. */
    private suspend fun sessionRowId(sessionId: String): Long? =
        db.queryLong("SELECT rowid FROM battery_sessions WHERE session_id = '$sessionId'")

    private suspend fun snapshotRowId(snapshotId: String): Long? =
        db.queryLong("SELECT rowid FROM battery_snapshots WHERE snapshot_id = '$snapshotId'")

    private suspend fun seed(): Triple<SessionEntity, SnapshotEntity, SnapshotEntity> {
        val first = Mappers.toEntity(fullSnapshot(id = uuid(1), sessionId = uuid(100)))
        val second = Mappers.toEntity(fullSnapshot(id = uuid(2), sessionId = uuid(100)))
        val session = Mappers.toEntity(activeSession(id = uuid(100)))
        db.sessionDao().upsertSnapshots(listOf(first, second))
        db.sessionDao().upsertSessions(listOf(session))
        db.sessionDao().upsertEngineState(
            EngineStateEntity(
                sessionId = session.sessionId,
                lastAcceptedSnapshotId = first.snapshotId,
                counterGeneration = 1,
            ),
        )
        return Triple(session, first, second)
    }

    // ------------------------------------------------------------ 1, 2, 4. row identity

    @Test
    fun `advancing the latest snapshot updates the session row in place`() = runTest {
        val (session, _, second) = seed()
        val before = sessionRowId(session.sessionId)
        assertNotNull("the session must exist", before)

        db.sessionDao().upsertSessions(listOf(session.copy(latestSnapshotId = second.snapshotId)))

        assertEquals(
            "the row must be updated, not deleted and reinserted",
            before,
            sessionRowId(session.sessionId),
        )
        assertEquals(
            "and the update must actually have happened",
            second.snapshotId,
            db.sessionDao().session(session.sessionId)!!.latestSnapshotId,
        )
        assertEquals("with no duplicate row", 1, db.sessionDao().sessionCount())
    }

    @Test
    fun `the primary key survives an update, and so does the row count`() = runTest {
        val (session, _, _) = seed()

        db.sessionDao().upsertSessions(
            listOf(session.copy(endSnapshotId = null, counterGeneration = 42)),
        )

        val stored = db.sessionDao().session(session.sessionId)
        assertEquals("identity is the primary key, and it does not move", session.sessionId, stored!!.sessionId)
        assertEquals(42L, stored.counterGeneration)
        assertEquals(1, db.sessionDao().sessionCount())
    }

    @Test
    fun `re-writing a snapshot updates it in place too`() = runTest {
        val (_, first, _) = seed()
        val before = snapshotRowId(first.snapshotId)
        assertNotNull("the snapshot must exist", before)

        db.sessionDao().upsertSnapshots(listOf(first.copy(level = 11)))

        assertEquals(before, snapshotRowId(first.snapshotId))
        assertEquals(11, db.sessionDao().snapshots(listOf(first.snapshotId)).single().level)
    }

    /**
     * Unlike the others, this one does not discriminate, and says so rather than implying it.
     *
     * `engine_state` holds exactly one row, so a delete-and-reinsert frees the only rowid and
     * SQLite hands the same value straight back. The mutation run confirmed it: with
     * `INSERT OR REPLACE` restored, the three session and snapshot tests here failed and this
     * one still passed. It is kept as a consistency check -- one row, correct contents -- not
     * as evidence about how the row was written.
     */
    @Test
    fun `refreshing the singleton engine state updates one row`() = runTest {
        val (session, _, second) = seed()
        val before = db.queryLong("SELECT rowid FROM engine_state WHERE id = 0")

        db.sessionDao().upsertEngineState(
            EngineStateEntity(
                sessionId = session.sessionId,
                lastAcceptedSnapshotId = second.snapshotId,
                counterGeneration = 7,
            ),
        )

        assertEquals(before, db.queryLong("SELECT rowid FROM engine_state WHERE id = 0"))
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM engine_state"))
        assertEquals(7L, db.sessionDao().engineState()!!.counterGeneration)
    }

    // -------------------------------------------------- 3, 5. dependants are undisturbed

    /**
     * The engine-state row references the session, and must not be collateral damage.
     *
     * Under `INSERT OR REPLACE` the parent row is deleted mid-statement. Nothing breaks today
     * because the foreign key is `NO_ACTION` and the row returns before the statement ends,
     * but the reference is momentarily dangling. A real update never creates that window.
     */
    @Test
    fun `an ordinary session update leaves the engine state pointing at it`() = runTest {
        val (session, _, second) = seed()

        db.sessionDao().upsertSessions(listOf(session.copy(latestSnapshotId = second.snapshotId)))

        val engineState = db.sessionDao().engineState()
        assertNotNull("the engine state must still exist", engineState)
        assertEquals(
            "and must still name the same session",
            session.sessionId,
            engineState!!.sessionId,
        )
        // The whole graph still loads, which is the property a user would notice.
        val loaded = store.load()
        assertTrue("state must still load, was $loaded", loaded is com.rmpsdroid.battinsight.session.StoredState.Loaded)
    }

    @Test
    fun `no snapshot is destroyed by updating the session that names it`() = runTest {
        val (session, first, second) = seed()
        val snapshotsBefore = db.sessionDao().snapshotCount()

        db.sessionDao().upsertSessions(listOf(session.copy(latestSnapshotId = second.snapshotId)))

        assertEquals("no dependent row may vanish", snapshotsBefore, db.sessionDao().snapshotCount())
        assertEquals(2, db.sessionDao().snapshots(listOf(first.snapshotId, second.snapshotId)).size)
    }

    // ------------------------------------------------------ 6, 7. atomicity is unaffected

    /**
     * The Phase 6 integrity guarantee, re-measured on the upsert implementation.
     *
     * Changing how rows are written is exactly the kind of change that could quietly
     * reintroduce a partial commit, so the original probe is repeated verbatim in intent: a
     * transition whose sessions reference snapshots that are not in the batch must leave the
     * database completely untouched.
     */
    @Test
    fun `a violating transition still leaves zero partial rows`() = runTest {
        val orphanSession = Mappers.toEntity(
            activeSession(id = uuid(777), start = fullSnapshot(id = uuid(778), sessionId = uuid(777))),
        )

        val threw = runCatching {
            db.sessionDao().persistTransition(
                snapshots = emptyList(),
                sessions = listOf(orphanSession),
                engineState = EngineStateEntity(
                    sessionId = orphanSession.sessionId,
                    lastAcceptedSnapshotId = null,
                    counterGeneration = 9,
                ),
            )
        }.exceptionOrNull()

        assertNotNull("the constraint must refuse the write", threw)
        assertEquals("sessions", 0, db.sessionDao().sessionCount())
        assertEquals("snapshots", 0, db.sessionDao().snapshotCount())
        assertNull("engine state", db.sessionDao().engineState())
    }

    @Test
    fun `a violating transition after real data leaves that data intact`() = runTest {
        val (session, first, _) = seed()
        val sessionsBefore = db.sessionDao().sessionCount()
        val snapshotsBefore = db.sessionDao().snapshotCount()
        val rowIdBefore = sessionRowId(session.sessionId)

        val orphan = Mappers.toEntity(
            activeSession(id = uuid(777), start = fullSnapshot(id = uuid(778), sessionId = uuid(777))),
        )
        val failure = RoomSessionStateStore(DroppingSnapshotDao(db.sessionDao())).saveState(
            com.rmpsdroid.battinsight.session.SessionEngineState(
                session = activeSession(id = uuid(777), start = fullSnapshot(id = uuid(778), sessionId = uuid(777))),
                lastAccepted = null,
                counterGeneration = com.rmpsdroid.battinsight.session.CounterGeneration(9),
            ),
        )

        assertTrue("the write must fail", !failure.succeeded)
        assertEquals(PersistenceOutcome.CONSTRAINT_FAILURE, failure.failureOrNull!!.outcome)
        assertEquals(sessionsBefore, db.sessionDao().sessionCount())
        assertEquals(snapshotsBefore, db.sessionDao().snapshotCount())
        assertEquals("and the surviving row is the same row", rowIdBefore, sessionRowId(session.sessionId))
        assertNull(db.sessionDao().session(orphan.sessionId))
        assertEquals(first.snapshotId, db.sessionDao().engineState()!!.lastAcceptedSnapshotId)
    }

    /**
     * A boundary written through the real engine still updates rather than replaces.
     *
     * The unit tests above drive the DAO directly. This one goes through the path the
     * application actually takes, because that is where the ordinary update lives.
     */
    @Test
    fun `a transition through the store updates the active session in place`() = runTest {
        val engine = com.rmpsdroid.battinsight.session.SessionEngine()
        val boot = com.rmpsdroid.battinsight.session.BootIdentity.Kernel("boot-under-test")
        fun observation(elapsed: Long, trigger: SessionTrigger) =
            com.rmpsdroid.battinsight.session.BatteryObservation(
                time = com.rmpsdroid.battinsight.session.CaptureTime(
                    com.rmpsdroid.battinsight.session.ElapsedRealtime(elapsed),
                    EPOCH + elapsed,
                    0,
                ),
                bootIdentity = boot,
                status = com.rmpsdroid.battinsight.session.BatteryStatus.DISCHARGING,
                plug = com.rmpsdroid.battinsight.session.PlugSource.NONE,
                level = 50,
                scale = 100,
                trigger = trigger,
            )

        val first = engine.reconcile(null, observation(0, SessionTrigger.APP_START))
        assertTrue(store.persist(first).succeeded)
        val sessionId = first.state.session!!.id.toString()
        val rowIdBefore = sessionRowId(sessionId)

        // A second observation in the same interval: continues it, moving latest forward.
        val second = engine.accept(first.state, observation(MINUTE, SessionTrigger.BATTERY_CHANGED))
        assertTrue(store.persist(second).succeeded)

        assertEquals("the same interval", sessionId, second.state.session!!.id.toString())
        assertEquals("must be the same row", rowIdBefore, sessionRowId(sessionId))
        assertEquals("with no second session", 1, db.sessionDao().sessionCount())
    }
}

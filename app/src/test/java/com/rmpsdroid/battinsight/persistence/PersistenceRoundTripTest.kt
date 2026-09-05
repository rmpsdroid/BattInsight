package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.BootRelation
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.CounterSource
import com.rmpsdroid.battinsight.session.PersistenceOutcome
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionEngineState
import com.rmpsdroid.battinsight.session.SessionType
import com.rmpsdroid.battinsight.session.SnapshotSchemaVersion
import com.rmpsdroid.battinsight.session.StoredState
import com.rmpsdroid.battinsight.session.relationTo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What survives a trip through the database.
 *
 * Every case asserts **semantic identity** of the domain object, not merely that some row
 * appeared. A snapshot that comes back with a null measurement turned into zero, or a
 * fallback boot identity promoted to a kernel one, has been corrupted just as surely as if
 * the write had failed — and more dangerously, because nothing would report it.
 *
 * These run under Robolectric against the real schema and real SQLite, so CI exercises them.
 * The Android 16 emulator additionally runs the process-death proof, which is the part only
 * a real platform can settle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PersistenceRoundTripTest {

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

    /** Stores a state and reads it back through the real store. */
    private suspend fun roundTrip(state: SessionEngineState): SessionEngineState {
        assertTrue("save must succeed", store.saveState(state).succeeded)
        val loaded = store.load()
        assertTrue("expected Loaded, was $loaded", loaded is StoredState.Loaded)
        return (loaded as StoredState.Loaded).state
    }

    // -------------------------------------------------------------------- 1-3. snapshots

    @Test
    fun `a fully populated snapshot survives intact`() = runTest {
        val snapshot = fullSnapshot()
        val session = activeSession(start = snapshot, latest = snapshot)
        val out = roundTrip(SessionEngineState(session, snapshot, CounterGeneration(3)))

        assertEquals(snapshot, out.lastAccepted)
        assertEquals("every field, not merely the identity", snapshot, out.session!!.start)
    }

    @Test
    fun `an active session survives with no end snapshot`() = runTest {
        val session = activeSession()
        val out = roundTrip(SessionEngineState(session, session.latest, CounterGeneration(3)))

        assertEquals(session, out.session)
        assertNull("an active interval has no end, and must not acquire one", out.session!!.end)
        assertTrue(out.session!!.isActive)
        assertEquals(SessionBoundaryReason.NONE, out.session!!.endReason)
    }

    @Test
    fun `a closed session survives with its end snapshot and reason`() = runTest {
        val session = closedSession()
        val out = roundTrip(SessionEngineState(session, session.latest, CounterGeneration(3)))

        assertEquals(session, out.session)
        assertNotNull(out.session!!.end)
        assertEquals(SessionBoundaryReason.POWER_TRANSITION, out.session!!.endReason)
        assertTrue(!out.session!!.isActive)
    }

    // --------------------------------------------------------------- 4-6. boot identities

    @Test
    fun `a kernel boot identity survives as a kernel identity`() = runTest {
        val boot = BootIdentity.Kernel("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val snapshot = fullSnapshot(boot = boot)
        val out = roundTrip(
            SessionEngineState(activeSession(start = snapshot, latest = snapshot), snapshot, CounterGeneration(1)),
        )

        assertEquals(boot, out.lastAccepted!!.bootIdentity)
        assertTrue(out.lastAccepted!!.bootIdentity.canProveBootRelation)
        assertEquals(
            "and can still prove sameness after reload",
            BootRelation.SAME,
            out.lastAccepted!!.bootIdentity.relationTo(boot),
        )
    }

    /**
     * The one that matters most: a weak identity must not come back strong.
     *
     * Evidence strength is the basis of every monotonic comparison. If a stored `Derived`
     * estimate reloaded as a `Kernel`, the comparability layer would start proving things the
     * original reading never supported — silently, and only after a restart.
     */
    @Test
    fun `a derived boot identity survives as derived, and proves nothing`() = runTest {
        val boot = BootIdentity.Derived(EPOCH - 3 * HOUR)
        val snapshot = fullSnapshot(boot = boot)
        val out = roundTrip(
            SessionEngineState(activeSession(start = snapshot, latest = snapshot), snapshot, CounterGeneration(1)),
        )

        val reloaded = out.lastAccepted!!.bootIdentity
        assertEquals(boot, reloaded)
        assertTrue("a fallback must not be promoted by a round trip", reloaded is BootIdentity.Derived)
        assertTrue(!reloaded.canProveBootRelation)
        assertEquals(BootRelation.UNKNOWN, reloaded.relationTo(boot))
        assertEquals(EPOCH - 3 * HOUR, (reloaded as BootIdentity.Derived).approximateBootWallClockMillis)
    }

    @Test
    fun `an unknown boot identity survives as unknown`() = runTest {
        val snapshot = sparseSnapshot(boot = BootIdentity.Unknown)
        val out = roundTrip(
            SessionEngineState(activeSession(start = snapshot, latest = snapshot), snapshot, CounterGeneration(1)),
        )

        assertEquals(BootIdentity.Unknown, out.lastAccepted!!.bootIdentity)
        assertTrue(!out.lastAccepted!!.bootIdentity.canProveBootRelation)
    }

    // ------------------------------------------------------- 7-10. comparability metadata

    @Test
    fun `counter generation survives exactly`() = runTest {
        listOf(1L, 2L, 7L, 4_000_000_000L).forEach { value ->
            val generation = CounterGeneration(value)
            val snapshot = fullSnapshot(id = uuid(value), generation = generation)
            val session = activeSession(start = snapshot, latest = snapshot, generation = generation)
            val out = roundTrip(SessionEngineState(session, snapshot, generation))

            assertEquals(generation, out.counterGeneration)
            assertEquals(generation, out.lastAccepted!!.counterGeneration)
            assertEquals(generation, out.session!!.counterGeneration)
        }
    }

    @Test
    fun `snapshot schema version survives and is not the room version`() = runTest {
        val schema = SnapshotSchemaVersion(1)
        val snapshot = fullSnapshot(schema = schema)
        val out = roundTrip(
            SessionEngineState(activeSession(start = snapshot, latest = snapshot), snapshot, CounterGeneration(1)),
        )

        assertEquals(schema, out.lastAccepted!!.schemaVersion)
        // They started in step and no longer are: Phase 7B took the Room schema to 2 by
        // adding counter tables, while the snapshot model did not change at all. That
        // divergence is the point of this test rather than a problem with it -- storing one
        // and inferring the other would couple two independent version domains.
        assertEquals(1, schema.value)
        assertEquals(2, BattInsightDatabase.DATABASE_VERSION)
        assertNotEquals(
            "the two version domains have now demonstrably separated",
            schema.value,
            BattInsightDatabase.DATABASE_VERSION,
        )
    }

    @Test
    fun `absent measurements stay absent and are never zero-filled`() = runTest {
        val snapshot = sparseSnapshot()
        val out = roundTrip(
            SessionEngineState(activeSession(start = snapshot, latest = snapshot), snapshot, CounterGeneration(1)),
        )

        val battery = out.lastAccepted!!.battery
        assertNull("a missing level is not zero percent", battery.level)
        assertNull(battery.scale)
        assertNull(battery.present)
        assertNull(battery.temperatureDeciCelsius)
        assertNull(battery.voltageMilliVolts)
        assertNull(battery.chargeCounterMicroAmpHours)
        assertNull(battery.levelPercent)
        assertNull(out.lastAccepted!!.platformVersionAtCapture)
        assertNull(out.lastAccepted!!.appVersionAtCapture)
    }

    @Test
    fun `wall-clock offsets survive exactly, in both directions`() = runTest {
        listOf(0, 60, 330, 540, -480, -210, 840).forEachIndexed { i, offset ->
            val snapshot = fullSnapshot(id = uuid(200L + i), utcOffsetMinutes = offset)
            val out = roundTrip(
                SessionEngineState(
                    activeSession(start = snapshot, latest = snapshot),
                    snapshot,
                    CounterGeneration(1),
                ),
            )
            assertEquals(
                "offset $offset must survive; an export cannot explain itself without it",
                offset,
                out.lastAccepted!!.time.utcOffsetMinutes,
            )
        }
    }

    @Test
    fun `both triggers survive independently`() = runTest {
        // A cold start produces a snapshot triggered APP_START carrying an observation
        // triggered BATTERY_CHANGED. Storing one and inferring the other would lose that.
        val snapshot = fullSnapshot(
            trigger = com.rmpsdroid.battinsight.session.SessionTrigger.APP_START,
            observationTrigger = com.rmpsdroid.battinsight.session.SessionTrigger.BATTERY_CHANGED,
        )
        val out = roundTrip(
            SessionEngineState(activeSession(start = snapshot, latest = snapshot), snapshot, CounterGeneration(1)),
        )

        assertEquals(
            com.rmpsdroid.battinsight.session.SessionTrigger.APP_START,
            out.lastAccepted!!.trigger,
        )
        assertEquals(
            com.rmpsdroid.battinsight.session.SessionTrigger.BATTERY_CHANGED,
            out.lastAccepted!!.battery.trigger,
        )
    }

    @Test
    fun `every session type survives`() = runTest {
        SessionType.entries.forEachIndexed { i, type ->
            val start = fullSnapshot(id = uuid(300L + i), sessionId = uuid(400L + i))
            val session = activeSession(id = uuid(400L + i), type = type, start = start, latest = start)
            val out = roundTrip(SessionEngineState(session, start, CounterGeneration(1)))
            assertEquals(type, out.session!!.type)
        }
    }

    @Test
    fun `counter source NONE is preserved rather than becoming a claimed source`() = runTest {
        val snapshot = fullSnapshot(source = CounterSource.NONE)
        val out = roundTrip(
            SessionEngineState(activeSession(start = snapshot, latest = snapshot), snapshot, CounterGeneration(1)),
        )

        assertEquals(CounterSource.NONE, out.lastAccepted!!.counterSource)
        assertTrue(
            "a snapshot with no counters must not look counter-comparable",
            !out.lastAccepted!!.hasCounters,
        )
    }

    // ------------------------------------------------------------- 13-14. engine state

    @Test
    fun `an empty store reports empty rather than failing`() = runTest {
        assertEquals(StoredState.Empty, store.load())
    }

    @Test
    fun `saving twice leaves exactly one authoritative engine state`() = runTest {
        val first = activeSession(id = uuid(500), start = fullSnapshot(id = uuid(50), sessionId = uuid(500)))
        store.saveState(SessionEngineState(first, first.latest, CounterGeneration(1)))

        val second = activeSession(id = uuid(501), start = fullSnapshot(id = uuid(51), sessionId = uuid(501)))
        store.saveState(SessionEngineState(second, second.latest, CounterGeneration(2)))

        val loaded = (store.load() as StoredState.Loaded).state
        assertEquals("the later state wins", uuid(501), loaded.session!!.id)
        assertEquals(CounterGeneration(2), loaded.counterGeneration)
        // One row, structurally: the entity has a fixed primary key.
        assertNotNull(db.sessionDao().engineState())
    }

    @Test
    fun `repeated identical saves remain idempotent`() = runTest {
        val session = activeSession()
        val state = SessionEngineState(session, session.latest, CounterGeneration(3))
        repeat(5) { assertTrue(store.saveState(state).succeeded) }

        assertEquals(1, db.sessionDao().sessionCount())
        assertEquals(1, db.sessionDao().snapshotCount())
        assertEquals(session, (store.load() as StoredState.Loaded).state.session)
    }

    @Test
    fun `clearing removes everything and reports empty`() = runTest {
        val session = closedSession()
        store.saveState(SessionEngineState(session, session.latest, CounterGeneration(3)))
        assertTrue(db.sessionDao().sessionCount() > 0)

        assertTrue(store.clear().succeeded)

        assertEquals(0, db.sessionDao().sessionCount())
        assertEquals(0, db.sessionDao().snapshotCount())
        assertEquals(StoredState.Empty, store.load())
    }

    // ------------------------------------------------------------------ 29. diagnostics

    @Test
    fun `stored counts are reported accurately`() = runTest {
        assertEquals(StorageCounts(0, 0), store.counts())

        val session = closedSession()
        store.saveState(SessionEngineState(session, session.latest, CounterGeneration(3)))

        // One session, two snapshots: its start and its end.
        assertEquals(StorageCounts(sessions = 1, snapshots = 2), store.counts())
    }

    // -------------------------------------------------------- 18-19. referential integrity

    @Test
    fun `engine state always references rows that exist`() = runTest {
        val session = activeSession()
        store.saveState(SessionEngineState(session, session.latest, CounterGeneration(3)))

        val engineState = db.sessionDao().engineState()!!
        assertNotNull(db.sessionDao().session(engineState.sessionId!!))
        assertEquals(
            1,
            db.sessionDao().snapshots(listOf(engineState.lastAcceptedSnapshotId!!)).size,
        )
    }

    /**
     * A session naming a snapshot that does not exist is refused, immediately.
     *
     * This is the direction the schema enforces, and it is the one that matters: a stored
     * session must always be rebuildable, which means the snapshots it names must exist.
     */
    @Test
    fun `a session naming a missing snapshot is refused by the database`() = runTest {
        var threw = false
        try {
            db.sessionDao().upsertSessions(listOf(Mappers.toEntity(activeSession())))
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            threw = true
        }
        assertTrue("the foreign key must reject a dangling snapshot reference", threw)
        assertEquals(0, db.sessionDao().sessionCount())
    }

    @Test
    fun `engine state naming a missing session is refused by the database`() = runTest {
        var threw = false
        try {
            db.sessionDao().upsertEngineState(
                EngineStateEntity(
                    sessionId = uuid(999).toString(),
                    lastAcceptedSnapshotId = null,
                    counterGeneration = 1,
                ),
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            threw = true
        }
        assertTrue("the foreign key must reject a dangling session reference", threw)
        assertEquals("and nothing may be left behind", StoredState.Empty, store.load())
    }

    /**
     * If data does become inconsistent by a route the schema cannot prevent -- a future
     * migration defect, or tampering -- the loader must still refuse it rather than report an
     * empty store. Treating unreadable as empty would silently discard a user's history.
     *
     * Reaching that path requires suspending the constraint that normally makes it
     * impossible, which is done here and nowhere else.
     */
    @Test
    fun `an inconsistent graph loads as corrupt, never as empty`() = runTest {
        db.execSql("PRAGMA foreign_keys = OFF")
        db.execSql(
            "INSERT INTO engine_state (id, session_id, last_accepted_snapshot_id, " +
                "counter_generation) VALUES (0, '${uuid(999)}', NULL, 1)",
        )
        db.execSql("PRAGMA foreign_keys = ON")

        val loaded = store.load()
        assertTrue("expected a failure, was $loaded", loaded is StoredState.Failed)
        assertEquals(PersistenceOutcome.CORRUPT_STATE, (loaded as StoredState.Failed).outcome)
        assertTrue(
            "the reason must say what is missing: ${loaded.detail}",
            loaded.detail.contains("session"),
        )
    }
}

/**
 * The SDK Robolectric emulates.
 *
 * Pinned below the project's `targetSdk` because Robolectric requires Java 21 to emulate
 * SDK 36 and this project builds on Java 17. What these tests exercise -- the schema, the
 * generated DAO, SQLite semantics -- does not vary with the emulated API level, and the real
 * Android 16 platform is covered separately by the instrumented run.
 */
internal const val ROBOLECTRIC_SDK = 34

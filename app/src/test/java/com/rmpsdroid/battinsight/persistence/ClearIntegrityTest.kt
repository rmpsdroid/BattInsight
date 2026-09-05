package com.rmpsdroid.battinsight.persistence

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture
import com.rmpsdroid.battinsight.batterystats.CaptureMetadata
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.batterystats.PartialWakelockStat
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.platform.AndroidAccessPreferenceStore
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.PersistenceResult
import com.rmpsdroid.battinsight.session.SessionEngineState
import com.rmpsdroid.battinsight.session.SessionStateStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Clearing saved state must remove everything, from a database that has counters in it.
 *
 * This is a regression test for a defect that shipped in Phase 7B and was found by the Phase 8
 * pre-PR audit. [SessionDao.clearAll] was written in Phase 6, when three tables existed. Phase
 * 7B then added four more, three of which point at `battery_sessions`, and did not revisit it.
 * Because every foreign key in this schema is NO ACTION and immediate, the clear did not leave
 * orphans -- it aborted:
 *
 * ```
 * android.database.sqlite.SQLiteConstraintException: FOREIGN KEY constraint failed
 * ```
 *
 * The old tests did not catch it because each one seeded only the tables it cared about: the
 * session tests had no counters, and the counter tests never called clear. The defect lived in
 * the seam between them, which is why this test seeds *both* halves before clearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ClearIntegrityTest {

    private lateinit var db: BattInsightDatabase
    private lateinit var store: SessionStateStore
    private lateinit var counters: RoomCounterStore
    private lateinit var sampleStore: RoomBatterySampleStore

    @Before
    fun setUp() {
        db = testDatabase()
        // Deliberately typed as the interface: this test is about the published clear
        // contract, not about a Room implementation detail, and production calls it here.
        store = RoomSessionStateStore(db.sessionDao())
        counters = RoomCounterStore(db.counterDao())
        sampleStore = RoomBatterySampleStore(db.batterySampleDao())
    }

    @After
    fun tearDown() = db.close()

    // --------------------------------------------------------------------- the regression

    @Test
    fun `clearing a database that holds counters empties every table`() = runTest {
        seedEverything()

        // Guard: a clear that "passes" against an empty database proves nothing at all, so
        // assert the state exists before removing it.
        assertEquals("session_counter_state seeded", 1L, count("session_counter_state"))
        assertEquals("counter_capture seeded", 2L, count("counter_capture"))
        assertTrue("kernel_wakelock_counter seeded", count("kernel_wakelock_counter") > 0)
        assertTrue("partial_wakelock_counter seeded", count("partial_wakelock_counter") > 0)
        assertEquals("engine_state seeded", 1L, count("engine_state"))
        assertTrue("battery_sample seeded", count("battery_sample") > 0)
        assertTrue("wakelock_identity seeded", count("wakelock_identity") > 0)
        assertEquals("battery_sessions seeded", 1L, count("battery_sessions"))
        assertTrue("battery_snapshots seeded", count("battery_snapshots") > 0)

        val result = store.clear()

        assertEquals("the clear must not be refused by a constraint", PersistenceResult.Success, result)
        for (table in ALL_TABLES) {
            assertEquals("$table must be empty after clear", 0L, count(table))
        }
    }

    /**
     * The wakelock rows are the only CASCADE children in this schema, so they are removed by
     * deleting their capture rather than by a statement of their own. That is worth asserting
     * separately: it is the one part of the deletion order that depends on the schema behaving
     * as declared instead of on [SessionDao.clearAll] listing a table.
     */
    @Test
    fun `wakelock rows go with their capture rather than being orphaned`() = runTest {
        seedEverything()
        val kernel = count("kernel_wakelock_counter")
        val partial = count("partial_wakelock_counter")
        assertTrue("both wakelock families must be seeded", kernel > 0 && partial > 0)

        assertEquals(PersistenceResult.Success, store.clear())

        assertEquals("kernel wakelock rows cascade away", 0L, count("kernel_wakelock_counter"))
        assertEquals("partial wakelock rows cascade away", 0L, count("partial_wakelock_counter"))
    }

    /** Clearing twice must be as harmless as clearing once. */
    @Test
    fun `clearing an already empty database succeeds`() = runTest {
        seedEverything()
        assertEquals(PersistenceResult.Success, store.clear())
        assertEquals(PersistenceResult.Success, store.clear())
        for (table in ALL_TABLES) assertEquals(0L, count(table))
    }

    /**
     * The narrower counter-only clear still keeps history, which is what its own contract says.
     * Without this the fix could have been "make everything delete everything", which would
     * silently destroy session history whenever counters were discarded.
     */
    @Test
    fun `clearing counters alone leaves the session history intact`() = runTest {
        seedEverything()

        assertEquals(PersistenceResult.Success, counters.clear())

        assertEquals("counter captures gone", 0L, count("counter_capture"))
        assertEquals("counter state gone", 0L, count("session_counter_state"))
        assertEquals("kernel rows gone", 0L, count("kernel_wakelock_counter"))
        assertEquals("partial rows gone", 0L, count("partial_wakelock_counter"))
        assertEquals("orphan identities go with them", 0L, count("wakelock_identity"))
        assertEquals("but the battery series survives", 3L, count("battery_sample"))
        assertEquals("but the session survives", 1L, count("battery_sessions"))
        assertEquals("and so does engine state", 1L, count("engine_state"))
        assertTrue("and its snapshots", count("battery_snapshots") > 0)
    }

    // ------------------------------------------------------- access preference survives

    /**
     * Clearing diagnostic history must not cost the user their access setup.
     *
     * The two are already separate stores -- the access choice is a Preferences DataStore file
     * and the history is Room -- so this is asserting a boundary rather than a behaviour. It is
     * worth a test anyway, because the obvious future feature here is a "Clear history" button,
     * and the tempting implementation of "clear everything" would silently send the user back
     * through onboarding to re-grant Shizuku for what they asked to be a data deletion.
     */
    @Test
    fun `clearing history leaves the access preference alone`() = runTest {
        val access = AndroidAccessPreferenceStore(ApplicationProvider.getApplicationContext())
        access.setAccessMode(AccessMode.SHIZUKU_LIVE)
        assertEquals(AccessMode.SHIZUKU_LIVE, access.current())

        seedEverything()
        assertEquals(PersistenceResult.Success, store.clear())

        assertEquals(
            "the access choice must survive a history clear",
            AccessMode.SHIZUKU_LIVE,
            access.current(),
        )
    }

    // ------------------------------------------------------------------------ atomicity

    /**
     * A clear that fails part-way must leave the database exactly as it was.
     *
     * The failure is injected rather than simulated: this replays the *old* Phase 6 deletion
     * order inside one transaction, which really does throw on `DELETE FROM battery_sessions`
     * because the counter children are still present. The assertion is that the two statements
     * that had already succeeded -- the engine-state delete, and everything before the throw --
     * are rolled back rather than committed.
     *
     * This is the same failure the production bug produced, so it also documents what the old
     * code did to a real database: not a partial clear, but no clear at all.
     */
    @Test
    fun `a clear that fails part-way rolls back completely`() = runTest {
        seedEverything()
        val before = ALL_TABLES.associateWith { count(it) }

        val threw = runCatching {
            db.useWriterConnection { connection ->
                connection.immediateTransaction {
                    // The old, incomplete order. Statement 1 succeeds; statement 2 violates
                    // the counter_capture -> battery_sessions constraint.
                    usePrepared("DELETE FROM engine_state") { it.step() }
                    usePrepared("DELETE FROM battery_sessions") { it.step() }
                    usePrepared("DELETE FROM battery_snapshots") { it.step() }
                }
            }
        }.exceptionOrNull()

        assertTrue(
            "the incomplete order must still fail, or this test is no longer injecting anything",
            threw != null,
        )
        assertTrue(
            "and it must fail on the constraint, not on something incidental: $threw",
            threw!!.toString().contains("FOREIGN KEY", ignoreCase = true) ||
                threw.toString().contains("constraint", ignoreCase = true),
        )

        for (table in ALL_TABLES) {
            assertEquals(
                "$table must be untouched after a failed clear",
                before[table],
                count(table),
            )
        }

        // And the corrected clear still works afterwards, so the failure left no lock or
        // half-open transaction behind.
        assertEquals(PersistenceResult.Success, store.clear())
        for (table in ALL_TABLES) assertEquals(0L, count(table))
    }

    // -------------------------------------------------------------------------- helpers

    private suspend fun count(table: String): Long =
        db.queryLong("SELECT COUNT(*) FROM $table") ?: 0L

    /**
     * A database in the state the bug needed: a real session with snapshots and engine state,
     * plus a baseline capture, a later capture, wakelock rows under both, and the session
     * counter state pointing at them.
     */
    private suspend fun seedEverything() {
        val sessionId = UUID.fromString(SESSION)
        val start = fullSnapshot(id = UUID.fromString(SNAPSHOT), sessionId = sessionId)
        val session = activeSession(id = sessionId, start = start)

        // Through the public store, so the seeding uses the same path production does.
        val saved = store.saveState(
            SessionEngineState(
                session = session,
                lastAccepted = start,
                counterGeneration = CounterGeneration(3),
            ),
        )
        assertEquals("seeding must succeed", PersistenceResult.Success, saved)

        counters.store(
            capture = capture(
                elapsed = 1_000L,
                kwl = listOf(kwl("bluetooth", 1_000L, 10L), kwl("alarm", 2_000L, 4L)),
                pwl = listOf(pwl(10_123, "*job*/com.x", 500L, 2L)),
            ),
            batterySessionId = SESSION,
            batterySnapshotId = SNAPSHOT,
            counterGeneration = CounterGeneration(3),
            bootIdentity = BOOT,
            newCaptureId = "cap-baseline",
        )
        repeat(3) { i ->
            sampleStore.record(
                SESSION,
                com.rmpsdroid.battinsight.session.BatteryObservation(
                    time = com.rmpsdroid.battinsight.session.CaptureTime(
                        com.rmpsdroid.battinsight.session.ElapsedRealtime(1_000L + i * 1_000L),
                        EPOCH + i * 1_000L,
                        330,
                    ),
                    bootIdentity = BOOT,
                    status = com.rmpsdroid.battinsight.session.BatteryStatus.DISCHARGING,
                    plug = com.rmpsdroid.battinsight.session.PlugSource.NONE,
                    level = 73,
                    scale = 100,
                ),
                com.rmpsdroid.battinsight.session.SessionTrigger.PERIODIC,
                CounterGeneration(3),
            )
        }

        counters.store(
            capture = capture(
                elapsed = 61_000L,
                kwl = listOf(kwl("bluetooth", 3_000L, 12L), kwl("alarm", 2_500L, 5L)),
                pwl = listOf(pwl(10_123, "*job*/com.x", 900L, 3L)),
            ),
            batterySessionId = SESSION,
            batterySnapshotId = SNAPSHOT,
            counterGeneration = CounterGeneration(3),
            bootIdentity = BOOT,
            newCaptureId = "cap-latest",
        )
    }

    private fun kwl(name: String, millis: Long, count: Long) =
        KernelWakelockStat(name, millis, count, AggregationWindow.SINCE_CHARGED)

    private fun pwl(uid: Int, name: String, millis: Long, count: Long) =
        PartialWakelockStat(uid, name, millis, count, AggregationWindow.SINCE_CHARGED)

    private fun capture(
        elapsed: Long,
        kwl: List<KernelWakelockStat>,
        pwl: List<PartialWakelockStat>,
    ) = BatteryStatsCapture(
        metadata = CaptureMetadata(
            sourceFormat = SourceFormat.CHECKIN,
            sourceFormatVersion = 9,
            captureElapsedRealtimeMillis = elapsed,
            captureWallClockMillis = EPOCH + elapsed,
            backendKind = BackendIdentity.Kind.SHELL,
            platformVersion = "16",
            payloadByteCount = 900_000,
            payloadHash = null,
            truncated = false,
        ),
        version = CheckinVersionBlock(9, 36, 215L, "BUILD.A", "BUILD.A"),
        kernelWakelocks = kwl,
        partialWakelocks = pwl,
        uidPackages = emptyList(),
        unsupportedTags = emptyMap(),
        historyLineCount = 38_921,
        warnings = emptyList(),
    )

    private companion object {
        const val SESSION = "00000000-0000-0000-0000-0000000000aa"
        const val SNAPSHOT = "00000000-0000-0000-0000-000000000011"
        val BOOT = BootIdentity.Kernel("boot-under-test")

        /** Every table the clear contract covers, in no particular order. */
        val ALL_TABLES = listOf(
            "engine_state",
            "session_counter_state",
            "kernel_wakelock_counter",
            "partial_wakelock_counter",
            "counter_capture",
            "battery_sample",
            "battery_sessions",
            "battery_snapshots",
            "wakelock_identity",
        )
    }
}

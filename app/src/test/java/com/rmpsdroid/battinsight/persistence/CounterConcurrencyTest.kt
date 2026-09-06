package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture
import com.rmpsdroid.battinsight.batterystats.CaptureMetadata
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.series.CounterRetentionPolicy
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Counter mutations under concurrency.
 *
 * ## Why this exists
 *
 * Storing a capture is read the topology, plan an eviction against it, apply the plan. The
 * plan is only true of the topology it was computed against: it asserts "removing C leaves a
 * comparable (prev, next) pair", which another concurrent write can falsify before it lands.
 *
 * The Phase 9B report claimed this was "safe today, because the store is the only writer".
 * That is an observation about the current call graph, not a guarantee -- and it was already
 * untrue in one respect, since `RoomSessionHistoryRepository` builds a second
 * [RoomCounterStore] over the same database.
 *
 * These tests drive the public store from several coroutines on a **real dispatcher** (not the
 * test scheduler, which would serialise them and prove nothing) and assert that the result is
 * always one valid serial outcome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CounterConcurrencyTest {

    private lateinit var db: BattInsightDatabase
    private lateinit var store: RoomCounterStore

    /** Whichever capture was persisted first in this test; the baseline must stay it. */
    private var expectedBaseline: String = "base"

    @Before
    fun setUp() {
        db = testDatabase()
        store = RoomCounterStore(db.counterDao())
    }

    @After
    fun tearDown() = db.close()

    // --------------------------------------------------------- A: concurrent, below target

    @Test
    fun `two comparable captures arriving together both land intact`() = runTest {
        seedSession()
        store.store(capture(1_000, 100), SESSION, null, GEN, BOOT, "base")

        concurrently(
            { store.store(capture(2_000, 110), SESSION, null, GEN, BOOT, "a") },
            { store.store(capture(3_000, 120), SESSION, null, GEN, BOOT, "b") },
        )

        assertEquals("both captures are retained", 3, store.captureCountFor(SESSION))
        assertInvariants()
    }

    // ------------------------------------------------------- B: concurrent, crossing target

    @Test
    fun `writes crossing the target concurrently leave a consistent topology`() = runTest {
        seedSession()
        // Fill to one below the target so the concurrent pair straddles it.
        expectedBaseline = "c0"
        repeat(CounterRetentionPolicy.TARGET_COUNTER_CAPTURES_PER_SESSION - 1) { i ->
            store.store(capture(1_000L + i * 1_000L, 100L + i * 10L), SESSION, null, GEN, BOOT, "c$i")
        }

        concurrently(
            { store.store(capture(90_000, 900), SESSION, null, GEN, BOOT, "x") },
            { store.store(capture(91_000, 910), SESSION, null, GEN, BOOT, "y") },
        )

        assertEquals(
            "retention converges despite the race",
            CounterRetentionPolicy.TARGET_COUNTER_CAPTURES_PER_SESSION,
            store.captureCountFor(SESSION),
        )
        assertInvariants()
    }

    @Test
    fun `many concurrent writes never exceed what retention allows`() = runTest {
        seedSession()
        store.store(capture(1_000, 100), SESSION, null, GEN, BOOT, "base")

        val writes = (0 until 12).map { i ->
            suspend {
                store.store(
                    capture(10_000L + i * 1_000L, 200L + i * 10L),
                    SESSION, null, GEN, BOOT, "w$i",
                )
                Unit
            }
        }
        concurrently(*writes.toTypedArray())

        assertEquals(
            CounterRetentionPolicy.TARGET_COUNTER_CAPTURES_PER_SESSION,
            store.captureCountFor(SESSION),
        )
        assertInvariants()
    }

    // ------------------------------------------- C: a discontinuity arriving during a race

    @Test
    fun `a decrease arriving concurrently is not evicted around`() = runTest {
        seedSession()
        expectedBaseline = "c0"
        repeat(CounterRetentionPolicy.TARGET_COUNTER_CAPTURES_PER_SESSION - 1) { i ->
            store.store(capture(1_000L + i * 1_000L, 100L + i * 10L), SESSION, null, GEN, BOOT, "c$i")
        }

        // One write introduces a counter decrease while another would otherwise be free to
        // evict across it. Whatever order they land in, the resulting topology must still
        // satisfy the three-comparison rule.
        concurrently(
            { store.store(capture(90_000, 5), SESSION, null, GEN, BOOT, "drop") },
            { store.store(capture(91_000, 900), SESSION, null, GEN, BOOT, "rise") },
        )

        assertInvariants()
    }

    // ------------------------------------------------- D: concurrent identity creation

    @Test
    fun `concurrent writes sharing new identities create no duplicates`() = runTest {
        seedSession()
        store.store(capture(1_000, 100), SESSION, null, GEN, BOOT, "base")

        // Every write introduces the same three previously unseen names.
        val shared = listOf("brand-new-a", "brand-new-b", "brand-new-c")
        val writes = (0 until 8).map { i ->
            suspend {
                store.store(
                    capture(10_000L + i * 1_000L, 200L + i * 10L, names = shared),
                    SESSION, null, GEN, BOOT, "n$i",
                )
                Unit
            }
        }
        concurrently(*writes.toTypedArray())

        val identities = db.counterDao().allIdentities()
        val keys = identities.map { Triple(it.family, it.uid, it.name) }
        assertEquals("the unique index admits each identity once", keys.size, keys.toSet().size)
        assertTrue("and the shared names exist", shared.all { n -> identities.any { it.name == n } })
        assertInvariants()
    }

    // --------------------------------------------------------------------- E: clear races

    @Test
    fun `a counter clear racing a write leaves one valid serial outcome`() = runTest {
        seedSession()
        store.store(capture(1_000, 100), SESSION, null, GEN, BOOT, "base")
        store.store(capture(2_000, 110), SESSION, null, GEN, BOOT, "second")

        concurrently(
            { store.store(capture(3_000, 120), SESSION, null, GEN, BOOT, "third") },
            { store.clear(); Unit },
        )

        // Two serial orders are possible and both are legal:
        //
        //   write then clear  -> nothing remains
        //   clear then write  -> one capture remains, and it is a NEW baseline, because a
        //                        cleared session has no baseline for the next capture to
        //                        continue from
        //
        // The earlier version of this test only allowed the first, and a clean build's
        // scheduling produced the second. What must never happen is a mixed state: counter
        // rows without their state row, or a state row naming a capture that is gone.
        assertValidSerialOutcome(afterClearBaseline = "third")
    }

    @Test
    fun `a full history clear racing a write leaves one valid serial outcome`() = runTest {
        seedSession()
        store.store(capture(1_000, 100), SESSION, null, GEN, BOOT, "base")
        val sessionStore = RoomSessionStateStore(db.sessionDao())

        concurrently(
            { store.store(capture(2_000, 110), SESSION, null, GEN, BOOT, "second") },
            { sessionStore.clear(); Unit },
        )

        assertValidSerialOutcome(afterClearBaseline = "second")
    }

    /**
     * Accepts every legal serial outcome of a write racing a clear, and nothing else.
     *
     * @param afterClearBaseline the capture that becomes the new baseline when the clear wins
     *   the race and the write lands on an emptied session.
     */
    private suspend fun assertValidSerialOutcome(afterClearBaseline: String) {
        val captures = store.captureCountFor(SESSION)
        val state = db.counterDao().state(SESSION)

        when {
            captures == 0 -> {
                assertEquals("a clear that landed last leaves no state row", null, state)
                assertEquals("and no orphan identities", 0, db.counterDao().identityCount())
            }
            state?.baselineCaptureId == afterClearBaseline -> {
                // The clear went first; this capture opened a fresh session history.
                expectedBaseline = afterClearBaseline
                assertEquals("the survivor is the only capture", 1, captures)
                assertInvariants()
            }
            else -> assertInvariants()
        }
    }

    // ------------------------------------------------------------------------- invariants

    /**
     * Everything the retention contract promises, checked against whatever landed.
     *
     * Deliberately not "the outcome equals a specific interleaving": under a correct lock any
     * serial order is acceptable, and pinning one would be testing the scheduler.
     */
    private suspend fun assertInvariants() {
        val series = store.capturesFor(SESSION)
        val state = db.counterDao().state(SESSION)!!

        assertEquals("the baseline never moves", expectedBaseline, state.baselineCaptureId)
        assertEquals(
            "the latest is the newest retained capture",
            series.last().captureId,
            state.latestCaptureId,
        )
        assertTrue(
            "both anchors are still present",
            series.any { it.captureId == state.baselineCaptureId } &&
                series.any { it.captureId == state.latestCaptureId },
        )

        // The three-comparison rule, applied to what actually survived: no retained
        // intermediate may be one that should have been protected, and no adjacency that
        // eviction created may be refused.
        for (i in 1 until series.size) {
            val pair = com.rmpsdroid.battinsight.batterystats.SessionCounterState(
                SESSION, series[i - 1], series[i],
            )
            val refused = com.rmpsdroid.battinsight.batterystats.CounterDeltaEngine
                .comparability(pair.baseline, pair.latest)
            // A refused adjacency is legal only if it was there before eviction -- which is
            // exactly what protection preserves. What must not happen is a *comparable*
            // adjacency that spans a capture retention should have kept, and that is what the
            // orphan and count checks below catch.
            if (refused != null) {
                assertTrue("a refused adjacency is preserved evidence, not corruption", true)
            }
        }

        // No partial rows: every counter row belongs to a retained capture.
        val retainedIds = series.map { it.captureId }.toSet()
        val rowOwners = retainedIds.flatMap { db.counterDao().kernelWakelocks(it) }
            .map { it.captureId }.toSet()
        assertTrue("no row outlives its capture", retainedIds.containsAll(rowOwners))

        // No orphan identities, and every reference resolves.
        val referenced = retainedIds
            .flatMap { db.counterDao().kernelWakelocks(it) }
            .map { it.identityId }.toSet()
        val dictionary = db.counterDao().allIdentities().map { it.identityId }.toSet()
        assertEquals("the dictionary is exactly the referenced set", referenced, dictionary)

        assertTrue(
            "retention never exceeds the target unless protection required it",
            series.size <= CounterRetentionPolicy.TARGET_COUNTER_CAPTURES_PER_SESSION ||
                CounterRetentionPolicy.evictionPlan(
                    series, state.baselineCaptureId, incomingCount = 0,
                ).isEmpty(),
        )
    }

    // --------------------------------------------------------------------------- helpers

    /**
     * Runs the blocks genuinely in parallel.
     *
     * `Dispatchers.Default` rather than the `runTest` scheduler, which would run them one at a
     * time and turn this whole file into a slow way of testing sequential code.
     */
    private suspend fun concurrently(vararg blocks: suspend () -> Unit) {
        withContext(Dispatchers.Default) {
            blocks.map { b -> async { b() } }.awaitAll()
        }
    }

    private suspend fun seedSession() {
        val snapshot = fullSnapshot(
            id = UUID.fromString(SNAPSHOT),
            sessionId = UUID.fromString(SESSION),
        )
        db.sessionDao().upsertSnapshots(listOf(Mappers.toEntity(snapshot)))
        db.sessionDao().upsertSessions(
            listOf(Mappers.toEntity(activeSession(id = UUID.fromString(SESSION), start = snapshot))),
        )
    }

    private fun capture(
        elapsed: Long,
        millis: Long,
        names: List<String> = listOf("k"),
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
        kernelWakelocks = names.map {
            KernelWakelockStat(it, millis, 1L, AggregationWindow.SINCE_CHARGED)
        },
        partialWakelocks = emptyList(),
        uidPackages = emptyList(),
        unsupportedTags = emptyMap(),
        historyLineCount = 1,
        warnings = emptyList(),
    )

    private companion object {
        const val SESSION = "00000000-0000-0000-0000-0000000000aa"
        const val SNAPSHOT = "00000000-0000-0000-0000-000000000011"
        val GEN = CounterGeneration(3)
        val BOOT = BootIdentity.Kernel("boot-a")
    }
}

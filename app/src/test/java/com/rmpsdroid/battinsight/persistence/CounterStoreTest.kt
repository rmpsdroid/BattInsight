package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture
import com.rmpsdroid.battinsight.batterystats.CaptureMetadata
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.CounterDeltaEngine
import com.rmpsdroid.battinsight.batterystats.CounterDeltaResult
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.batterystats.PartialWakelockStat
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
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
 * Durable counter storage: what is kept, what is refused, and what stays bounded.
 *
 * Runs against the real schema and real SQLite under Robolectric, so the foreign keys and the
 * cascade behaviour are the ones that ship rather than a fake's approximation of them.
 *
 * The invariant these exist to protect: **a baseline, once established, does not move.** Every
 * delta in a session is measured from it, so a baseline that quietly advanced would make the
 * session's accumulated totals shrink toward zero while looking entirely healthy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CounterStoreTest {

    private lateinit var db: BattInsightDatabase
    private lateinit var store: RoomCounterStore

    @Before
    fun setUp() {
        db = testDatabase()
        store = RoomCounterStore(db.counterDao())
    }

    @After
    fun tearDown() = db.close()

    // ------------------------------------------------------------------- round trip

    @Test
    fun `a capture round-trips with its metadata intact`() = runTest {
        seedSession(SESSION_A)

        val result = store.store(
            capture = capture(kwl = listOf(kwl("bt", 1_000L, 10L))),
            batterySessionId = SESSION_A,
            batterySnapshotId = SNAPSHOT_A,
            counterGeneration = CounterGeneration(3),
            bootIdentity = BootIdentity.Kernel("boot-x"),
            newCaptureId = "cap-1",
        )

        assertEquals(CounterPersistResult.Role.BASELINE, (result as CounterPersistResult.Stored).role)
        val state = store.state(SESSION_A)!!
        val stored = state.baseline
        assertEquals("cap-1", stored.captureId)
        assertEquals(SNAPSHOT_A, stored.batterySnapshotId)
        assertEquals(SourceFormat.CHECKIN, stored.sourceFormat)
        assertEquals(BackendIdentity.Kind.SHELL, stored.backendKind)
        assertEquals(36, stored.version.checkinVersion)
        assertEquals(215L, stored.version.parcelVersion)
        assertEquals(CounterGeneration(3), stored.counterGeneration)
        assertEquals(BootIdentity.Kernel("boot-x"), stored.bootIdentity)
        assertTrue(stored.checkinVersionVerified)
    }

    @Test
    fun `kernel and partial wakelock rows round-trip exactly`() = runTest {
        seedSession(SESSION_A)
        store.store(
            capture(
                kwl = listOf(kwl("", 0L, 0L), kwl("bt,read", 681_038L, 678L)),
                pwl = listOf(pwl(1000, "WindowManager", 7_713L, 3L)),
            ),
            SESSION_A, null, CounterGeneration(1), BOOT, "cap-1",
        )

        val stored = store.state(SESSION_A)!!.baseline
        assertEquals(2, stored.kernelWakelocks.size)
        assertTrue("the empty name survives", stored.kernelWakelocks.any { it.name.isEmpty() })
        val bt = stored.kernelWakelocks.first { it.name == "bt,read" }
        assertEquals("a comma in a name survives storage", 681_038L, bt.totalTimeMillis)
        assertEquals(678L, bt.count)
        assertEquals(AggregationWindow.SINCE_CHARGED, bt.window)

        val wl = stored.partialWakelocks.single()
        assertEquals(1000, wl.uid)
        assertEquals(7_713L, wl.totalTimeMillis)
    }

    /**
     * A stored `DERIVED` boot identity never comes back as a `Kernel`.
     *
     * The same rule Phase 6 established for snapshots. Evidence strength decides every
     * comparison, and promoting a diagnostic estimate to proof on the way out of the database
     * would let a delta span a reboot.
     */
    @Test
    fun `boot identity strength survives a round trip`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(), SESSION_A, null, CounterGeneration(1), BootIdentity.Derived(999L), "c")

        val restored = store.state(SESSION_A)!!.baseline.bootIdentity
        assertEquals(BootIdentity.Derived(999L), restored)
        assertTrue(restored !is BootIdentity.Kernel)
    }

    // ------------------------------------------------------- baseline and latest roles

    @Test
    fun `the first capture becomes both baseline and latest, as one row`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(), SESSION_A, null, CounterGeneration(1), BOOT, "cap-1")

        val state = store.state(SESSION_A)!!
        assertEquals("cap-1", state.baseline.captureId)
        assertEquals("cap-1", state.latest.captureId)
        assertTrue(state.baselineIsLatest)
        assertEquals("one capture, not two identical ones", 1, store.captureCount())
    }

    @Test
    fun `a second capture updates latest and leaves the baseline alone`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(kwl = listOf(kwl("k", 100L, 1L))), SESSION_A, null, GEN, BOOT, "cap-1")
        store.store(
            capture(elapsed = 61_000L, kwl = listOf(kwl("k", 400L, 4L))),
            SESSION_A, null, GEN, BOOT, "cap-2",
        )

        val state = store.state(SESSION_A)!!
        assertEquals("the baseline is immutable", "cap-1", state.baseline.captureId)
        assertEquals("cap-2", state.latest.captureId)
        assertTrue(!state.baselineIsLatest)
        assertEquals(100L, state.baseline.kernelWakelocks.single().totalTimeMillis)
        assertEquals(400L, state.latest.kernelWakelocks.single().totalTimeMillis)
    }

    /**
     * The mutation check the whole design turns on.
     *
     * Ten further captures, each with a larger counter. If any of them moved the baseline, the
     * session's accumulated total would shrink toward zero and nothing would look wrong.
     */
    @Test
    fun `the baseline never moves however many captures follow`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(kwl = listOf(kwl("k", 100L, 1L))), SESSION_A, null, GEN, BOOT, "cap-0")

        repeat(10) { i ->
            store.store(
                capture(elapsed = 2_000L + i * 1_000L, kwl = listOf(kwl("k", 200L + i * 100L, 2L))),
                SESSION_A, null, GEN, BOOT, "cap-${i + 1}",
            )
            val state = store.state(SESSION_A)!!
            assertEquals("baseline moved on capture $i", "cap-0", state.baseline.captureId)
            assertEquals(100L, state.baseline.kernelWakelocks.single().totalTimeMillis)
        }

        val delta = CounterDeltaEngine.kernelWakelockDelta(
            store.state(SESSION_A)!!, AggregationWindow.SINCE_CHARGED, "k",
        )
        assertEquals(
            "the accumulated total is measured from the original baseline",
            1_000L,
            (delta as CounterDeltaResult.Success).value.durationDeltaMillis,
        )
    }

    // ------------------------------------------------------------- bounded retention

    /**
     * A hundred refreshes leave two captures, not a hundred.
     *
     * This is the storage contract for this phase, stated as a test so it cannot quietly
     * become a time series by accident.
     */
    @Test
    fun `a hundred refreshes leave exactly one baseline and one latest`() = runTest {
        seedSession(SESSION_A)
        repeat(100) { i ->
            val result = store.store(
                capture(elapsed = 1_000L + i * 1_000L, kwl = listOf(kwl("k", i * 10L, i.toLong()))),
                SESSION_A, null, GEN, BOOT, "cap-$i",
            )
            assertTrue("refresh $i failed: $result", result.succeeded)
        }

        assertEquals("two logical captures", 2, store.captureCount())
        val (kernelRows, partialRows) = store.counterRowCounts()
        assertEquals("one counter row per capture", 2, kernelRows)
        assertEquals(0, partialRows)

        val state = store.state(SESSION_A)!!
        assertEquals("cap-0", state.baseline.captureId)
        assertEquals("cap-99", state.latest.captureId)
    }

    @Test
    fun `superseded counter rows are removed, not orphaned`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(kwl = List(50) { kwl("k$it", 1L, 1L) }), SESSION_A, null, GEN, BOOT, "c0")
        store.store(
            capture(elapsed = 5_000L, kwl = List(50) { kwl("k$it", 2L, 2L) }),
            SESSION_A, null, GEN, BOOT, "c1",
        )
        store.store(
            capture(elapsed = 9_000L, kwl = List(50) { kwl("k$it", 3L, 3L) }),
            SESSION_A, null, GEN, BOOT, "c2",
        )

        // Baseline c0 and latest c2 survive; c1 and its 50 rows are gone.
        assertEquals(2, store.captureCount())
        assertEquals(100, store.counterRowCounts().first)
    }

    @Test
    fun `sessions do not share counter state`() = runTest {
        seedSession(SESSION_A)
        seedSession(SESSION_B)
        store.store(capture(kwl = listOf(kwl("a", 10L, 1L))), SESSION_A, null, GEN, BOOT, "a1")
        store.store(capture(kwl = listOf(kwl("b", 20L, 2L))), SESSION_B, null, GEN, BOOT, "b1")

        assertEquals("a1", store.state(SESSION_A)!!.baseline.captureId)
        assertEquals("b1", store.state(SESSION_B)!!.baseline.captureId)
        assertEquals(1, store.captureCountFor(SESSION_A))
        assertEquals(1, store.captureCountFor(SESSION_B))
    }

    @Test
    fun `a session with no captures has no state`() = runTest {
        seedSession(SESSION_A)
        assertNull(store.state(SESSION_A))
    }

    // ------------------------------------------------------------------- refusals

    @Test
    fun `a truncated capture is refused as a baseline`() = runTest {
        seedSession(SESSION_A)
        val result = store.store(
            capture().let { it.copy(metadata = it.metadata.copy(truncated = true)) },
            SESSION_A, null, GEN, BOOT, "c",
        )

        assertEquals(
            CounterRejection.NOT_A_COMPLETE_CAPTURE,
            (result as CounterPersistResult.Rejected).reason,
        )
        assertNull("nothing was written", store.state(SESSION_A))
    }

    /**
     * An unverified checkin version may be displayed but not stored.
     *
     * Deliberately stricter than the decoder, which tolerates an unknown version with a
     * warning so a future Android release does not simply fail. A baseline is subtracted from
     * for the rest of the session, so an assumed layout would contaminate every later delta.
     */
    @Test
    fun `an unverified checkin version is refused as a baseline`() = runTest {
        seedSession(SESSION_A)
        val result = store.store(
            capture(version = version(checkin = 99)), SESSION_A, null, GEN, BOOT, "c",
        )

        assertEquals(
            CounterRejection.UNVERIFIED_CHECKIN_VERSION,
            (result as CounterPersistResult.Rejected).reason,
        )
        assertNull(store.state(SESSION_A))
    }

    @Test
    fun `a capture spanning an OS update is refused`() = runTest {
        seedSession(SESSION_A)
        val result = store.store(
            capture(version = version(start = "BUILD.OLD", end = "BUILD.NEW")),
            SESSION_A, null, GEN, BOOT, "c",
        )

        assertEquals(CounterRejection.PLATFORM_CHANGED, (result as CounterPersistResult.Rejected).reason)
    }

    /**
     * Duplicate counter identities are refused, not deduplicated.
     *
     * Measured across both real captures there are no duplicates on `(window, name)`, so a
     * duplicate means something unmodelled. Letting the primary key silently keep the last row
     * would discard a real measurement to satisfy a constraint.
     */
    @Test
    fun `duplicate counter identities are refused rather than collapsed`() = runTest {
        seedSession(SESSION_A)
        val result = store.store(
            capture(kwl = listOf(kwl("dup", 1L, 1L), kwl("dup", 2L, 2L))),
            SESSION_A, null, GEN, BOOT, "c",
        )

        assertEquals(
            CounterRejection.DUPLICATE_COUNTER_IDENTITY,
            (result as CounterPersistResult.Rejected).reason,
        )
        assertNull("and nothing partial was written", store.state(SESSION_A))
    }

    @Test
    fun `the same name under two windows is not a duplicate`() = runTest {
        seedSession(SESSION_A)
        val result = store.store(
            capture(
                kwl = listOf(
                    kwl("k", 1L, 1L, AggregationWindow.SINCE_CHARGED),
                    kwl("k", 2L, 2L, AggregationWindow.SINCE_UNPLUGGED),
                ),
            ),
            SESSION_A, null, GEN, BOOT, "c",
        )

        assertTrue("different windows are different counters", result.succeeded)
        assertEquals(2, store.state(SESSION_A)!!.baseline.kernelWakelocks.size)
    }

    // ------------------------------------------------------------------ transactions

    /**
     * A failed write leaves the previous baseline and latest exactly as they were.
     *
     * The failure is provoked by naming a session that does not exist, which the foreign key
     * refuses. Nothing partial may survive it.
     */
    @Test
    fun `a failed capture leaves existing state untouched`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(kwl = listOf(kwl("k", 100L, 1L))), SESSION_A, null, GEN, BOOT, "cap-1")
        val before = store.state(SESSION_A)!!
        val countsBefore = store.counterRowCounts()

        val failed = store.store(capture(), "no-such-session", null, GEN, BOOT, "cap-bad")

        assertTrue("the write must fail", failed is CounterPersistResult.Failed)
        val after = store.state(SESSION_A)!!
        assertEquals(before.baseline.captureId, after.baseline.captureId)
        assertEquals(before.latest.captureId, after.latest.captureId)
        assertEquals(countsBefore, store.counterRowCounts())
        assertNull("no partial capture row survives", db.counterDao().capture("cap-bad"))
    }

    @Test
    fun `a failed first capture leaves no partial baseline`() = runTest {
        val failed = store.store(capture(), "no-such-session", null, GEN, BOOT, "cap-bad")

        assertTrue(failed is CounterPersistResult.Failed)
        assertEquals(0, store.captureCount())
        assertEquals(0 to 0, store.counterRowCounts())
    }

    @Test
    fun `clearing counters leaves session history intact`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(kwl = listOf(kwl("k", 1L, 1L))), SESSION_A, null, GEN, BOOT, "c")

        assertTrue(store.clear().succeeded)

        assertEquals(0, store.captureCount())
        assertEquals(0 to 0, store.counterRowCounts())
        assertNotNull("the battery session survives", db.sessionDao().session(SESSION_A))
    }

    // ------------------------------------------------------- backend independence

    @Test
    fun `the backend does not change what is persisted`() = runTest {
        seedSession(SESSION_A)
        seedSession(SESSION_B)
        store.store(capture(kwl = listOf(kwl("k", 5L, 1L))), SESSION_A, null, GEN, BOOT, "s1")
        store.store(
            capture(backend = BackendIdentity.Kind.APP_UID, kwl = listOf(kwl("k", 5L, 1L))),
            SESSION_B, null, GEN, BOOT, "a1",
        )

        val viaShell = store.state(SESSION_A)!!.baseline
        val viaApp = store.state(SESSION_B)!!.baseline
        assertEquals(viaShell.kernelWakelocks, viaApp.kernelWakelocks)
        assertNotEquals("only the recorded provenance differs", viaShell.backendKind, viaApp.backendKind)
    }

    // -------------------------------------------------- cross-session ownership

    /**
     * A session's state row cannot point at another session's capture.
     *
     * Phase 7B enforced this only by construction: the store always wrote a capture and a
     * state row for the same session, so nothing wrong ever happened. But the schema permitted
     * it -- three independent single-column foreign keys prove a capture *exists* and say
     * nothing about whose it is -- and a delta computed across two battery sessions is exactly
     * the kind of confidently-wrong answer this project exists to prevent.
     *
     * The keys are now composite, carrying the session id alongside the capture id, so the
     * database refuses it. These tests go around the store and write through the DAO directly,
     * because the point is that the *constraint* holds, not that the store is careful.
     */
    @Test
    fun `a baseline pointer into another session is refused by the database`() = runTest {
        seedSession(SESSION_A)
        seedSession(SESSION_B)
        store.store(capture(kwl = listOf(kwl("a", 10L, 1L))), SESSION_A, null, GEN, BOOT, "cap-a")
        store.store(capture(kwl = listOf(kwl("b", 20L, 2L))), SESSION_B, null, GEN, BOOT, "cap-b")
        val beforeA = store.state(SESSION_A)!!
        val beforeB = store.state(SESSION_B)!!
        val rowsBefore = store.counterRowCounts()

        val refused = runCatching {
            db.counterDao().upsertState(
                SessionCounterStateEntity(
                    batterySessionId = SESSION_A,
                    baselineCaptureId = "cap-b", // belongs to session B
                    latestCaptureId = "cap-a",
                ),
            )
        }

        assertTrue("the database must refuse it", refused.isFailure)
        val afterA = store.state(SESSION_A)!!
        assertEquals("session A's baseline is untouched", beforeA.baseline.captureId, afterA.baseline.captureId)
        assertEquals(beforeA.latest.captureId, afterA.latest.captureId)
        assertEquals("session B is untouched", beforeB.baseline.captureId, store.state(SESSION_B)!!.baseline.captureId)
        assertNotNull("and B's capture still exists", db.counterDao().capture("cap-b"))
        assertEquals("no counter rows were disturbed", rowsBefore, store.counterRowCounts())
    }

    @Test
    fun `a latest pointer into another session is refused by the database`() = runTest {
        seedSession(SESSION_A)
        seedSession(SESSION_B)
        store.store(capture(kwl = listOf(kwl("a", 10L, 1L))), SESSION_A, null, GEN, BOOT, "cap-a")
        store.store(capture(kwl = listOf(kwl("b", 20L, 2L))), SESSION_B, null, GEN, BOOT, "cap-b")
        val beforeA = store.state(SESSION_A)!!

        val refused = runCatching {
            db.counterDao().upsertState(
                SessionCounterStateEntity(
                    batterySessionId = SESSION_A,
                    baselineCaptureId = "cap-a",
                    latestCaptureId = "cap-b", // belongs to session B
                ),
            )
        }

        assertTrue("the database must refuse it", refused.isFailure)
        assertEquals(beforeA.latest.captureId, store.state(SESSION_A)!!.latest.captureId)
        assertNotNull(db.counterDao().capture("cap-b"))
    }

    @Test
    fun `a state row naming a capture that does not exist at all is refused`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(), SESSION_A, null, GEN, BOOT, "cap-a")

        val refused = runCatching {
            db.counterDao().upsertState(
                SessionCounterStateEntity(SESSION_A, "no-such-capture", "cap-a"),
            )
        }
        assertTrue(refused.isFailure)
    }

    /** The legitimate case still works, so the constraint is not simply refusing everything. */
    @Test
    fun `pointers within the same session are accepted`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(), SESSION_A, null, GEN, BOOT, "cap-1")
        store.store(capture(elapsed = 61_000L), SESSION_A, null, GEN, BOOT, "cap-2")

        val state = store.state(SESSION_A)!!
        assertEquals("cap-1", state.baseline.captureId)
        assertEquals("cap-2", state.latest.captureId)
    }

    // ------------------------------------------------------------------------ helpers

    /** A battery session must exist before counters can reference it. */
    private suspend fun seedSession(sessionId: String) {
        val snapshot = Mappers.toEntity(
            fullSnapshot(id = java.util.UUID.fromString(SNAPSHOT_A), sessionId = java.util.UUID.fromString(sessionId)),
        )
        db.sessionDao().upsertSnapshots(listOf(snapshot))
        db.sessionDao().upsertSessions(
            listOf(
                Mappers.toEntity(
                    activeSession(
                        id = java.util.UUID.fromString(sessionId),
                        start = fullSnapshot(
                            id = java.util.UUID.fromString(SNAPSHOT_A),
                            sessionId = java.util.UUID.fromString(sessionId),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun version(checkin: Int = 36, start: String = "BUILD.A", end: String = "BUILD.A") =
        CheckinVersionBlock(9, checkin, 215L, start, end)

    private fun kwl(
        name: String,
        millis: Long,
        count: Long,
        window: AggregationWindow = AggregationWindow.SINCE_CHARGED,
    ) = KernelWakelockStat(name, millis, count, window)

    private fun pwl(uid: Int, name: String, millis: Long, count: Long) =
        PartialWakelockStat(uid, name, millis, count, AggregationWindow.SINCE_CHARGED)

    private fun capture(
        elapsed: Long = 1_000L,
        version: CheckinVersionBlock = version(),
        backend: BackendIdentity.Kind = BackendIdentity.Kind.SHELL,
        kwl: List<KernelWakelockStat> = emptyList(),
        pwl: List<PartialWakelockStat> = emptyList(),
    ) = BatteryStatsCapture(
        metadata = CaptureMetadata(
            sourceFormat = SourceFormat.CHECKIN,
            sourceFormatVersion = 9,
            captureElapsedRealtimeMillis = elapsed,
            captureWallClockMillis = 1_700_000_000_000L + elapsed,
            backendKind = backend,
            platformVersion = "16",
            payloadByteCount = 900_000,
            payloadHash = null,
            truncated = false,
        ),
        version = version,
        kernelWakelocks = kwl,
        partialWakelocks = pwl,
        uidPackages = emptyList(),
        unsupportedTags = emptyMap(),
        historyLineCount = 38_921,
        warnings = emptyList(),
    )

    private companion object {
        const val SESSION_A = "00000000-0000-0000-0000-0000000000aa"
        const val SESSION_B = "00000000-0000-0000-0000-0000000000bb"
        const val SNAPSHOT_A = "00000000-0000-0000-0000-000000000011"
        val GEN = CounterGeneration(1)
        val BOOT = BootIdentity.Kernel("boot-under-test")
    }
}

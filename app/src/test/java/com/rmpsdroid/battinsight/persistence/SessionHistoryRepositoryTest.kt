package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture
import com.rmpsdroid.battinsight.batterystats.CaptureMetadata
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.history.CounterAvailability
import com.rmpsdroid.battinsight.history.SessionHistoryRepository
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.SessionType
import java.util.UUID
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
 * History as it comes out of the database.
 *
 * Real schema, real SQLite. The ordering and paging rules are only meaningful against a real
 * query planner, and the counter-availability states are only meaningful against real stored
 * captures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SessionHistoryRepositoryTest {

    private lateinit var db: BattInsightDatabase
    private lateinit var repo: RoomSessionHistoryRepository
    private lateinit var counters: RoomCounterStore

    @Before
    fun setUp() {
        db = testDatabase()
        repo = RoomSessionHistoryRepository(db.sessionDao(), db.counterDao())
        counters = RoomCounterStore(db.counterDao())
    }

    @After
    fun tearDown() = db.close()

    // -------------------------------------------------------------------- ordering

    @Test
    fun `history is newest first by wall clock`() = runTest {
        seed(1, wallClock = 3_000L)
        seed(2, wallClock = 1_000L)
        seed(3, wallClock = 2_000L)

        val rows = repo.recentSessions()

        assertEquals(listOf(3_000L, 2_000L, 1_000L), rows.map { it.startWallClockMillis })
    }

    /**
     * Identical wall clocks order deterministically.
     *
     * Two sessions can share a millisecond. Without a stable secondary sort the same query
     * returns them in different orders on different runs, and paging then skips or repeats
     * rows as the boundary shifts underneath it.
     */
    @Test
    fun `sessions sharing a wall clock keep a stable order`() = runTest {
        seed(1, wallClock = 5_000L)
        seed(2, wallClock = 5_000L)
        seed(3, wallClock = 5_000L)

        val first = repo.recentSessions().map { it.sessionId }
        repeat(4) { assertEquals(first, repo.recentSessions().map { it.sessionId }) }
    }

    // ---------------------------------------------------------------------- paging

    @Test
    fun `a bounded page returns at most the limit`() = runTest {
        repeat(12) { seed(it + 1, wallClock = (it + 1) * 1_000L) }

        assertEquals(5, repo.recentSessions(limit = 5).size)
        assertEquals(12, repo.sessionCount())
    }

    @Test
    fun `paging back continues where the previous page stopped`() = runTest {
        repeat(10) { seed(it + 1, wallClock = (it + 1) * 1_000L) }

        val page1 = repo.recentSessions(limit = 4)
        val page2 = repo.recentSessions(limit = 4, before = page1.last().startWallClockMillis)

        assertEquals(4, page2.size)
        assertTrue(
            "pages must not overlap",
            page1.map { it.sessionId }.intersect(page2.map { it.sessionId }.toSet()).isEmpty(),
        )
        assertTrue(
            "and must continue downward",
            page2.first().startWallClockMillis < page1.last().startWallClockMillis,
        )
    }

    @Test
    fun `an empty database yields no rows and no current session`() = runTest {
        assertEquals(emptyList<Any>(), repo.recentSessions())
        assertNull(repo.currentSession())
        assertEquals(0, repo.sessionCount())
    }

    // -------------------------------------------------------------------- row state

    @Test
    fun `an active session is marked active and has no end time`() = runTest {
        seed(1, wallClock = 1_000L, active = true)

        val row = repo.recentSessions().single()
        assertTrue(row.isActive)
        assertNull(row.endWallClockMillis)
        assertEquals(SessionType.DISCHARGE, row.type)
    }

    @Test
    fun `a completed session carries an end time and a reason`() = runTest {
        seed(1, wallClock = 1_000L, active = false)

        val row = repo.recentSessions().single()
        assertTrue(!row.isActive)
        assertNotNull(row.endWallClockMillis)
    }

    /**
     * Duration comes from the monotonic clock, not the wall clocks.
     *
     * Seeded here with a wall clock that jumps backwards between the two snapshots -- exactly
     * what a time-zone change or an NTP correction does. The duration must be unaffected.
     */
    @Test
    fun `duration ignores a wall clock that moved backwards`() = runTest {
        seedWithClocks(
            n = 1,
            startWall = 10_000L, startElapsed = 1_000L,
            endWall = 4_000L, endElapsed = 61_000L,
        )

        val row = repo.recentSessions().single()
        assertEquals("60 seconds of monotonic time", 60_000L, row.durationMillis)
    }

    @Test
    fun `the current session comes from engine state, not from a scan`() = runTest {
        seed(1, wallClock = 1_000L, active = true)
        seed(2, wallClock = 2_000L, active = true)
        db.sessionDao().upsertEngineState(
            EngineStateEntity(
                sessionId = sessionId(2).toString(),
                lastAcceptedSnapshotId = null,
                counterGeneration = 1,
            ),
        )

        assertEquals(sessionId(2).toString(), repo.currentSession()!!.sessionId)
    }

    // ------------------------------------------------------- counter availability

    @Test
    fun `a session with no capture reports no capture`() = runTest {
        seed(1, wallClock = 1_000L)
        assertEquals(CounterAvailability.NoCapture, repo.recentSessions().single().counters)
    }

    @Test
    fun `one capture reports baseline only`() = runTest {
        seed(1, wallClock = 1_000L)
        counters.store(capture(kwl = listOf(kwl("k", 100L, 1L))), id(1), null, GEN, BOOT, "c1")

        assertEquals(CounterAvailability.BaselineOnly, repo.recentSessions().single().counters)
    }

    @Test
    fun `two comparable captures report an available delta`() = runTest {
        seed(1, wallClock = 1_000L)
        counters.store(capture(kwl = listOf(kwl("k", 100L, 1L))), id(1), null, GEN, BOOT, "c1")
        counters.store(
            capture(elapsed = 61_000L, kwl = listOf(kwl("k", 400L, 4L))),
            id(1), null, GEN, BOOT, "c2",
        )

        val availability = repo.recentSessions().single().counters
        assertTrue(availability is CounterAvailability.DeltaAvailable)
        assertEquals(1, (availability as CounterAvailability.DeltaAvailable).kernelWakelockCount)
        assertTrue("something changed", !availability.allZero)
    }

    /** Two captures that agree entirely are a measurement of no activity. */
    @Test
    fun `an unchanged pair reports a delta that is all zero`() = runTest {
        seed(1, wallClock = 1_000L)
        counters.store(capture(kwl = listOf(kwl("k", 100L, 1L))), id(1), null, GEN, BOOT, "c1")
        counters.store(
            capture(elapsed = 61_000L, kwl = listOf(kwl("k", 100L, 1L))),
            id(1), null, GEN, BOOT, "c2",
        )

        val availability = repo.recentSessions().single().counters as CounterAvailability.DeltaAvailable
        assertTrue("a measured zero, not missing data", availability.allZero)
    }

    /**
     * A decreased counter refuses the whole pair, and the detail screen gets no numbers.
     *
     * The Phase 7B.1 rule has to survive the trip through the repository. Showing "the
     * counters that still look positive" would be showing figures already established as
     * untrustworthy.
     */
    @Test
    fun `a decreased counter reports unavailable and yields no delta lists`() = runTest {
        seed(1, wallClock = 1_000L)
        counters.store(
            capture(kwl = listOf(kwl("A", 100L, 10L), kwl("B", 5L, 1L))),
            id(1), null, GEN, BOOT, "c1",
        )
        counters.store(
            capture(elapsed = 61_000L, kwl = listOf(kwl("A", 50L, 5L), kwl("B", 10L, 2L))),
            id(1), null, GEN, BOOT, "c2",
        )

        val availability = repo.recentSessions().single().counters
        assertEquals(
            CounterAvailability.DeltaUnavailable(CounterDeltaReason.COUNTER_DECREASED),
            availability,
        )

        val detail = repo.sessionDetail(id(1))!!
        assertEquals(CounterDeltaReason.COUNTER_DECREASED, detail.unavailableReason)
        assertEquals("no kernel figures may escape", emptyList<Any>(), detail.kernelDeltas)
        assertEquals("and no app figures either", emptyList<Any>(), detail.partialDeltas)
        assertNotNull("the diagnostic names where it was noticed", detail.continuityDetail)
    }

    // --------------------------------------------------------------------- detail

    @Test
    fun `session detail carries provenance and capture summary`() = runTest {
        seed(1, wallClock = 1_000L)
        counters.store(capture(kwl = listOf(kwl("k", 100L, 1L))), id(1), null, GEN, BOOT, "c1")

        val detail = repo.sessionDetail(id(1))!!
        assertEquals(1, detail.provenance.snapshotSchemaVersion)
        assertNotNull(detail.captures)
        assertEquals(36, detail.captures!!.checkinVersion)
        assertTrue("one capture means nothing to compare", detail.captures!!.baselineIsLatest)
        assertEquals(SourceFormat.CHECKIN, detail.captures!!.sourceFormat)
    }

    @Test
    fun `a session with no captures still produces a detail`() = runTest {
        seed(1, wallClock = 1_000L)

        val detail = repo.sessionDetail(id(1))!!
        assertNull(detail.captures)
        assertNull(detail.unavailableReason)
        assertEquals(emptyList<Any>(), detail.kernelDeltas)
    }

    @Test
    fun `an unknown session id yields no detail`() = runTest {
        assertNull(repo.sessionDetail(UUID(9, 9).toString()))
    }

    /**
     * History needs no privileged access at all.
     *
     * The repository touches only BattInsight's own database. Nothing here constructs a
     * backend, so browsing saved periods keeps working when Shizuku is not running and when no
     * permission was ever granted -- only a live capture needs one.
     */
    @Test
    fun `history reads without any access backend present`() = runTest {
        seed(1, wallClock = 1_000L)
        counters.store(capture(kwl = listOf(kwl("k", 100L, 1L))), id(1), null, GEN, BOOT, "c1")

        // No runner, no capability report, no Shizuku -- just the repository.
        val rows = RoomSessionHistoryRepository(db.sessionDao(), db.counterDao()).recentSessions()

        assertEquals(1, rows.size)
        assertEquals(CounterAvailability.BaselineOnly, rows.single().counters)
    }

    @Test
    fun `the default page size is the documented one`() {
        assertEquals(50, SessionHistoryRepository.DEFAULT_PAGE)
    }

    // ------------------------------------------------------------------------ helpers

    private fun sessionId(n: Int) = UUID(0L, n.toLong())
    private fun id(n: Int) = sessionId(n).toString()

    private suspend fun seed(n: Int, wallClock: Long, active: Boolean = true) =
        seedWithClocks(n, wallClock, 1_000L, wallClock + 60_000L, 61_000L, active)

    private suspend fun seedWithClocks(
        n: Int,
        startWall: Long,
        startElapsed: Long,
        endWall: Long,
        endElapsed: Long,
        active: Boolean = false,
    ) {
        val sid = sessionId(n)
        val startId = UUID(1L, n.toLong())
        val endId = UUID(2L, n.toLong())
        val start = Mappers.toEntity(
            fullSnapshot(id = startId, sessionId = sid, elapsedMillis = startElapsed, wallClockMillis = startWall),
        )
        val end = Mappers.toEntity(
            fullSnapshot(id = endId, sessionId = sid, elapsedMillis = endElapsed, wallClockMillis = endWall),
        )
        db.sessionDao().upsertSnapshots(listOf(start, end))
        db.sessionDao().upsertSessions(
            listOf(
                SessionEntity(
                    sessionId = sid.toString(),
                    sessionType = SessionType.DISCHARGE.name,
                    startSnapshotId = startId.toString(),
                    latestSnapshotId = endId.toString(),
                    endSnapshotId = if (active) null else endId.toString(),
                    endReason = if (active) "NONE" else "POWER_TRANSITION",
                    counterGeneration = 1L,
                ),
            ),
        )
    }

    private fun kwl(name: String, millis: Long, count: Long) =
        KernelWakelockStat(name, millis, count, AggregationWindow.SINCE_CHARGED)

    private fun capture(elapsed: Long = 1_000L, kwl: List<KernelWakelockStat> = emptyList()) =
        BatteryStatsCapture(
            metadata = CaptureMetadata(
                sourceFormat = SourceFormat.CHECKIN,
                sourceFormatVersion = 9,
                captureElapsedRealtimeMillis = elapsed,
                captureWallClockMillis = 1_700_000_000_000L + elapsed,
                backendKind = BackendIdentity.Kind.SHELL,
                platformVersion = "16",
                payloadByteCount = 900_000,
                payloadHash = null,
                truncated = false,
            ),
            version = CheckinVersionBlock(9, 36, 215L, "BUILD.A", "BUILD.A"),
            kernelWakelocks = kwl,
            partialWakelocks = emptyList(),
            uidPackages = emptyList(),
            unsupportedTags = emptyMap(),
            historyLineCount = 100,
            warnings = emptyList(),
        )

    private companion object {
        val GEN = CounterGeneration(1)
        val BOOT = BootIdentity.Kernel("boot-under-test")
    }
}

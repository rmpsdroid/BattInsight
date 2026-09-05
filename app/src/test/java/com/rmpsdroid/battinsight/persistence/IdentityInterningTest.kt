package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture
import com.rmpsdroid.battinsight.batterystats.CaptureMetadata
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wakelock identities: interned once, resolved exactly, and swept when nothing needs them.
 *
 * Interning is a storage decision with a privacy consequence. Phase 9A measured that **60.3%
 * of partial wakelock names contain a dotted package-style token**, with 63 distinct package
 * prefixes recoverable from the names alone, so this dictionary is in effect an inventory of
 * what runs on the device. v2 already held that text, but only for as long as a counter row
 * referenced it; interning would extend its life indefinitely unless it is swept, which is why
 * the sweep is tested as carefully as the round trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class IdentityInterningTest {

    private lateinit var db: BattInsightDatabase
    private lateinit var store: RoomCounterStore

    @Before
    fun setUp() {
        db = testDatabase()
        store = RoomCounterStore(db.counterDao())
    }

    @After
    fun tearDown() = db.close()

    // ------------------------------------------------------------------------ round trip

    @Test
    fun `a kernel identity round-trips through interning`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(kwl = listOf(kwl("bluetooth", 1_000L, 10L))), SESSION_A, null, GEN, BOOT, "c0")

        val stored = store.state(SESSION_A)!!.baseline.kernelWakelocks.single()
        assertEquals("bluetooth", stored.name)
        assertEquals(1_000L, stored.totalTimeMillis)

        val identity = db.counterDao().allIdentities().single()
        assertEquals(WakelockIdentityEntity.FAMILY_KERNEL, identity.family)
        assertEquals("kernel wakelocks belong to no uid", -1, identity.uid)
    }

    @Test
    fun `a partial identity keeps its uid`() = runTest {
        seedSession(SESSION_A)
        store.store(
            capture(pwl = listOf(pwl(10_123, "*job*/com.x", 500L, 2L))),
            SESSION_A, null, GEN, BOOT, "c0",
        )

        val stored = store.state(SESSION_A)!!.baseline.partialWakelocks.single()
        assertEquals(10_123, stored.uid)
        assertEquals("*job*/com.x", stored.name)
    }

    @Test
    fun `the same identity across many captures is stored once`() = runTest {
        seedSession(SESSION_A)
        repeat(5) { i ->
            store.store(
                capture(elapsed = 1_000L + i * 1_000L, kwl = listOf(kwl("bluetooth", i * 100L, 1L))),
                SESSION_A, null, GEN, BOOT, "c$i",
            )
        }

        assertEquals("one dictionary row, five captures", 1, db.counterDao().identityCount())
        assertEquals(5, store.captureCount())
    }

    @Test
    fun `family distinguishes identities that share a name`() = runTest {
        seedSession(SESSION_A)
        store.store(
            capture(kwl = listOf(kwl("alarm", 1L, 1L)), pwl = listOf(pwl(-1, "alarm", 1L, 1L))),
            SESSION_A, null, GEN, BOOT, "c0",
        )

        // Same name, same uid sentinel, different family. Collapsing them would merge a kernel
        // wakelock with an application one and add their durations together.
        assertEquals(2, db.counterDao().identityCount())
    }

    @Test
    fun `uid distinguishes identities that share a name`() = runTest {
        seedSession(SESSION_A)
        store.store(
            capture(pwl = listOf(pwl(1000, "*job*/x", 1L, 1L), pwl(1001, "*job*/x", 2L, 2L))),
            SESSION_A, null, GEN, BOOT, "c0",
        )

        assertEquals(2, db.counterDao().identityCount())
        val uids = store.state(SESSION_A)!!.baseline.partialWakelocks.map { it.uid }.sorted()
        assertEquals(listOf(1000, 1001), uids)
    }

    // ------------------------------------------------------- names are stored as written

    @Test
    fun `an empty kernel name round-trips`() = runTest {
        // Android 16 emits exactly one kernel wakelock with an empty name. Normalising it away
        // would silently drop a real counter.
        seedSession(SESSION_A)
        store.store(capture(kwl = listOf(kwl("", 5L, 1L))), SESSION_A, null, GEN, BOOT, "c0")

        assertEquals("", store.state(SESSION_A)!!.baseline.kernelWakelocks.single().name)
    }

    @Test
    fun `a 423-character name round-trips`() = runTest {
        // The longest name measured in a real Android 16 capture. These are call chains, not
        // labels, and truncating one would make two different wakelocks share an identity.
        val long = "WorkManager:TikTokListenableWorker startWork -> " + "a.b.c.Component".repeat(25)
        seedSession(SESSION_A)
        store.store(capture(pwl = listOf(pwl(10_001, long, 1L, 1L))), SESSION_A, null, GEN, BOOT, "c0")

        assertEquals(long, store.state(SESSION_A)!!.baseline.partialWakelocks.single().name)
    }

    @Test
    fun `a name containing commas round-trips`() = runTest {
        seedSession(SESSION_A)
        store.store(
            capture(kwl = listOf(kwl("has,commas,inside", 1L, 1L))),
            SESSION_A, null, GEN, BOOT, "c0",
        )

        assertEquals("has,commas,inside", store.state(SESSION_A)!!.baseline.kernelWakelocks.single().name)
    }

    @Test
    fun `names are matched exactly, not case-insensitively`() = runTest {
        seedSession(SESSION_A)
        store.store(
            capture(kwl = listOf(kwl("Bluetooth", 1L, 1L), kwl("bluetooth", 2L, 2L))),
            SESSION_A, null, GEN, BOOT, "c0",
        )

        assertEquals("case is part of the identity", 2, db.counterDao().identityCount())
    }

    // ----------------------------------------------------------------------- the sweep

    @Test
    fun `an identity is swept once no counter row references it`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(kwl = listOf(kwl("only-here", 1L, 1L))), SESSION_A, null, GEN, BOOT, "c0")
        assertEquals(1, db.counterDao().identityCount())

        store.clear()

        assertEquals("forgetting the counters forgets who they belonged to", 0, db.counterDao().identityCount())
    }

    @Test
    fun `an identity still referenced elsewhere survives the sweep`() = runTest {
        seedSession(SESSION_A)
        seedSession(SESSION_B)
        // Three captures in A so the middle one is neither baseline nor latest, and can
        // therefore actually be removed -- the composite foreign keys refuse to let the two
        // anchors be deleted while session_counter_state still names them, which is exactly
        // the protection Phase 7B.1 added.
        store.store(capture(kwl = listOf(kwl("shared", 1L, 1L))), SESSION_A, null, GEN, BOOT, "a0")
        store.store(
            capture(elapsed = 2_000L, kwl = listOf(kwl("shared", 2L, 2L))),
            SESSION_A, null, GEN, BOOT, "a1",
        )
        store.store(
            capture(elapsed = 3_000L, kwl = listOf(kwl("shared", 3L, 3L))),
            SESSION_A, null, GEN, BOOT, "a2",
        )
        store.store(capture(kwl = listOf(kwl("shared", 1L, 1L))), SESSION_B, null, GEN, BOOT, "b0")
        assertEquals("one dictionary row for the shared name", 1, db.counterDao().identityCount())

        db.counterDao().deleteCapture("a1")
        db.counterDao().sweepOrphanIdentities()

        assertEquals("still referenced by a0, a2 and session B", 1, db.counterDao().identityCount())
    }

    @Test
    fun `no retained counter row ever references a swept identity`() = runTest {
        seedSession(SESSION_A)
        store.store(capture(kwl = listOf(kwl("kept", 1L, 1L))), SESSION_A, null, GEN, BOOT, "c0")
        // "doomed" exists only in the middle capture, so removing that capture should take
        // its identity with it while leaving "kept" alone.
        store.store(
            capture(elapsed = 3_000L, kwl = listOf(kwl("kept", 2L, 2L), kwl("doomed", 1L, 1L))),
            SESSION_A, null, GEN, BOOT, "c1",
        )
        store.store(
            capture(elapsed = 5_000L, kwl = listOf(kwl("kept", 3L, 3L))),
            SESSION_A, null, GEN, BOOT, "c2",
        )
        assertEquals(2, db.counterDao().identityCount())

        db.counterDao().deleteCapture("c1")
        db.counterDao().sweepOrphanIdentities()

        val identities = db.counterDao().allIdentities()
        assertEquals(listOf("kept"), identities.map { it.name })

        val referenced = db.counterDao().capturesFor(SESSION_A)
            .flatMap { db.counterDao().kernelWakelocks(it.captureId) }
            .map { it.identityId }
            .toSet()
        assertEquals(
            "the surviving dictionary is exactly the referenced set",
            referenced,
            identities.map { it.identityId }.toSet(),
        )
    }

    @Test
    fun `a swept identity id is never reused for a different wakelock`() = runTest {
        // AUTOINCREMENT is load-bearing rather than decorative. Without it SQLite reuses the
        // rowid of a deleted row, so a swept identity's id could be handed to a completely
        // different wakelock and silently relabel any retained reference to it.
        seedSession(SESSION_A)
        store.store(capture(kwl = listOf(kwl("first", 1L, 1L))), SESSION_A, null, GEN, BOOT, "c0")
        val firstId = db.counterDao().allIdentities().single().identityId

        store.clear()
        assertEquals(0, db.counterDao().identityCount())

        seedSession(SESSION_B)
        store.store(capture(kwl = listOf(kwl("second", 1L, 1L))), SESSION_B, null, GEN, BOOT, "d0")
        val secondId = db.counterDao().allIdentities().single().identityId

        assertNotEquals("a reused id would relabel history", firstId, secondId)
        assertTrue("ids only move forward", secondId > firstId)
    }

    @Test
    fun `interning is idempotent under repeated identical writes`() = runTest {
        val dao = db.counterDao()
        val a = dao.internIdentity(WakelockIdentityEntity.FAMILY_KERNEL, -1, "same")
        val b = dao.internIdentity(WakelockIdentityEntity.FAMILY_KERNEL, -1, "same")

        assertEquals("the unique index arbitrates, not the caller", a, b)
        assertEquals(1, dao.identityCount())
    }

    @Test
    fun `a nonexistent identity resolves to null rather than a default`() = runTest {
        assertNull(db.counterDao().identity(9_999L))
    }

    // --------------------------------------------------------------------------- helpers

    private suspend fun seedSession(sessionId: String) {
        val snapshotId = if (sessionId == SESSION_A) SNAPSHOT_A else SNAPSHOT_B
        val snapshot = fullSnapshot(
            id = java.util.UUID.fromString(snapshotId),
            sessionId = java.util.UUID.fromString(sessionId),
        )
        db.sessionDao().upsertSnapshots(listOf(Mappers.toEntity(snapshot)))
        db.sessionDao().upsertSessions(
            listOf(
                Mappers.toEntity(
                    activeSession(id = java.util.UUID.fromString(sessionId), start = snapshot),
                ),
            ),
        )
    }

    private fun kwl(name: String, millis: Long, count: Long) =
        KernelWakelockStat(name, millis, count, AggregationWindow.SINCE_CHARGED)

    private fun pwl(uid: Int, name: String, millis: Long, count: Long) =
        PartialWakelockStat(uid, name, millis, count, AggregationWindow.SINCE_CHARGED)

    private fun capture(
        elapsed: Long = 1_000L,
        kwl: List<KernelWakelockStat> = emptyList(),
        pwl: List<PartialWakelockStat> = emptyList(),
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
        const val SESSION_A = "00000000-0000-0000-0000-0000000000aa"
        const val SESSION_B = "00000000-0000-0000-0000-0000000000bb"
        const val SNAPSHOT_A = "00000000-0000-0000-0000-000000000011"
        const val SNAPSHOT_B = "00000000-0000-0000-0000-000000000022"
        val GEN = CounterGeneration(3)
        val BOOT = BootIdentity.Kernel("boot-under-test")
    }
}

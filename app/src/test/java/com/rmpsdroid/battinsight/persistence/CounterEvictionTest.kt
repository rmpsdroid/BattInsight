package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture
import com.rmpsdroid.battinsight.batterystats.CaptureMetadata
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import java.util.UUID
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
 * Which counter captures may safely be evicted, and which are protecting evidence.
 *
 * ## The rule, and the defect it replaced
 *
 * A candidate `c` between `prev` and `next` may be removed only when **all three** of
 * `(prev, c)`, `(c, next)` and `(prev, next)` are comparable.
 *
 * Phase 9A.1 specified only the third. That permits exactly the deletion it was written to
 * prevent:
 *
 * ```
 * prev = 100   c = 50   next = 120
 * ```
 *
 * `prev -> c` is a counter decrease and is refused, but `prev -> next` reads 100 -> 120 and
 * computes a clean **+20** -- a number that looks like a measurement and spans a counter
 * reset. Four further sequences behave the same way for metadata reasons, because a
 * round-tripped value leaves the outer pair looking untouched. That is why nothing here is
 * special-cased by reason.
 *
 * To keep the target reachable in these tests the store's soft target is exercised by pushing
 * past it; a session that cannot evict safely simply keeps more captures, which is the design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CounterEvictionTest {

    private lateinit var db: BattInsightDatabase
    private lateinit var store: RoomCounterStore

    @Before
    fun setUp() {
        db = testDatabase()
        store = RoomCounterStore(db.counterDao())
    }

    @After
    fun tearDown() = db.close()

    // ------------------------------------------------------------------- the truth table

    @Test
    fun `all rising - the middle capture is eligible`() = runTest {
        val retained = runSequence(
            cap(0, 100), cap(1, 110), cap(2, 120),
        )
        assertEvicted(retained, "the middle of a clean rising run may go", expectEvicted = true)
    }

    @Test
    fun `100 to 50 to 120 - the middle capture is protected`() = runTest {
        // The named case. Deleting c1 would leave 100 -> 120 = a clean, wrong +20.
        val retained = runSequence(cap(0, 100), cap(1, 50), cap(2, 120))
        assertEvicted(retained, "a decrease must protect its own capture", expectEvicted = false)
    }

    @Test
    fun `100 to 110 to 90 - the middle capture is protected`() = runTest {
        val retained = runSequence(cap(0, 100), cap(1, 110), cap(2, 90))
        assertEvicted(retained, "a later decrease refuses B and C", expectEvicted = false)
    }

    @Test
    fun `boot b1 b2 b1 - the middle capture is protected`() = runTest {
        // A round trip: A and B are both refused for DIFFERENT_BOOT, but C compares b1 to b1
        // and looks fine. Caught only because A and B are asked.
        val retained = runSequence(
            cap(0, 100, boot = "b1"), cap(1, 110, boot = "b2"), cap(2, 120, boot = "b1"),
        )
        assertEvicted(retained, "a boot round trip hides the refusal from C", expectEvicted = false)
    }

    @Test
    fun `counter generation 3 4 3 - the middle capture is protected`() = runTest {
        val retained = runSequence(
            cap(0, 100, generation = 3), cap(1, 110, generation = 4), cap(2, 120, generation = 3),
        )
        assertEvicted(retained, "a generation round trip hides the refusal from C", expectEvicted = false)
    }

    @Test
    fun `an unverified checkin version never reaches storage at all`() = runTest {
        // A stronger guarantee than eviction protection, and the reason the checkin round-trip
        // case cannot arise through this path: the store refuses to persist a capture whose
        // record layout has not been verified against a real device, so the sequence
        // 36 -> 37 -> 36 is impossible in the database rather than merely protected once there.
        seedSession()
        val result = store.store(
            capture(elapsed = 1_000L, millis = 100L).copy(
                version = CheckinVersionBlock(9, 37, 215L, "BUILD.A", "BUILD.A"),
            ),
            SESSION, null, GEN, BOOT, "c0",
        )

        assertTrue(result is CounterPersistResult.Rejected)
        assertEquals(
            CounterRejection.UNVERIFIED_CHECKIN_VERSION,
            (result as CounterPersistResult.Rejected).reason,
        )
        assertEquals(0, store.captureCountFor(SESSION))
    }

    @Test
    fun `a capture spanning a platform change never reaches storage at all`() = runTest {
        seedSession()
        val result = store.store(
            capture(elapsed = 1_000L, millis = 100L).copy(
                version = CheckinVersionBlock(9, 36, 215L, "BUILD.A", "BUILD.B"),
            ),
            SESSION, null, GEN, BOOT, "c0",
        )

        assertEquals(
            CounterRejection.PLATFORM_CHANGED,
            (result as CounterPersistResult.Rejected).reason,
        )
        assertEquals(0, store.captureCountFor(SESSION))
    }

    @Test
    fun `derived boot identities throughout - nothing is eligible`() = runTest {
        // Every comparison is UNKNOWN, because a derived value is an estimate and two equal
        // estimates are still not evidence of the same boot.
        val retained = runSequence(
            cap(0, 100, derivedBoot = true),
            cap(1, 110, derivedBoot = true),
            cap(2, 120, derivedBoot = true),
        )
        assertEvicted(retained, "unproven continuity protects everything", expectEvicted = false)
    }

    // -------------------------------------------------------------- overflow is permitted

    @Test
    fun `a session keeps more than the target when nothing can safely go`() = runTest {
        seedSession()
        // Alternating decreases mean every intermediate is adjacent to a refusal, so no
        // candidate ever passes all three comparisons. Truth wins over the storage target.
        var value = 1_000L
        repeat(14) { i ->
            value = if (i % 2 == 0) value - 500L else value + 900L
            store.store(capture(elapsed = 1_000L + i * 1_000L, millis = value), SESSION, null, GEN, BOOT, "c$i")
        }

        val retained = store.captureCountFor(SESSION)
        assertTrue(
            "overflow above the target is expected here, saw $retained",
            retained > RoomCounterStore.TARGET_COUNTER_CAPTURES_PER_SESSION,
        )
    }

    @Test
    fun `overflow is deterministic`() = runTest {
        suspend fun run(): Int {
            val fresh = testDatabase()
            val s = RoomCounterStore(fresh.counterDao())
            seedSession(fresh)
            var value = 1_000L
            repeat(14) { i ->
                value = if (i % 2 == 0) value - 500L else value + 900L
                s.store(capture(elapsed = 1_000L + i * 1_000L, millis = value), SESSION, null, GEN, BOOT, "c$i")
            }
            val n = s.captureCountFor(SESSION)
            fresh.close()
            return n
        }
        assertEquals("the same input always retains the same set", run(), run())
    }

    // --------------------------------------------------------------- anchors are untouchable

    @Test
    fun `the baseline is never evicted`() = runTest {
        seedSession()
        repeat(20) { i ->
            store.store(
                capture(elapsed = 1_000L + i * 1_000L, millis = i * 100L),
                SESSION, null, GEN, BOOT, "c$i",
            )
        }

        assertEquals("the baseline never moves", "c0", store.state(SESSION)!!.baseline.captureId)
    }

    @Test
    fun `the latest is always the newest capture stored`() = runTest {
        seedSession()
        repeat(20) { i ->
            store.store(
                capture(elapsed = 1_000L + i * 1_000L, millis = i * 100L),
                SESSION, null, GEN, BOOT, "c$i",
            )
        }

        assertEquals("c19", store.state(SESSION)!!.latest.captureId)
        val ordered = store.capturesFor(SESSION)
        assertEquals("and it is genuinely last in the series", "c19", ordered.last().captureId)
    }

    @Test
    fun `intermediate captures are retained up to the target`() = runTest {
        seedSession()
        repeat(20) { i ->
            store.store(
                capture(elapsed = 1_000L + i * 1_000L, millis = i * 100L),
                SESSION, null, GEN, BOOT, "c$i",
            )
        }

        assertEquals(
            RoomCounterStore.TARGET_COUNTER_CAPTURES_PER_SESSION,
            store.captureCountFor(SESSION),
        )
        assertTrue("the series has a middle now", store.capturesFor(SESSION).size > 2)
    }

    @Test
    fun `eviction leaves no orphan counter rows or identities`() = runTest {
        seedSession()
        repeat(20) { i ->
            store.store(
                capture(elapsed = 1_000L + i * 1_000L, millis = i * 100L, name = "k$i"),
                SESSION, null, GEN, BOOT, "c$i",
            )
        }

        val retainedCaptures = store.capturesFor(SESSION).map { it.captureId }.toSet()
        val rowCaptureIds = retainedCaptures.flatMap { db.counterDao().kernelWakelocks(it) }
            .map { it.captureId }.toSet()
        assertTrue("no row outlives its capture", retainedCaptures.containsAll(rowCaptureIds))

        val referenced = retainedCaptures
            .flatMap { db.counterDao().kernelWakelocks(it) }
            .map { it.identityId }.toSet()
        val dictionary = db.counterDao().allIdentities().map { it.identityId }.toSet()
        assertEquals("the dictionary holds exactly what is referenced", referenced, dictionary)
    }

    // --------------------------------------------------------------------------- helpers

    /**
     * Stores three captures and then pushes past the target so retention actually runs,
     * returning the retained capture ids.
     */
    private suspend fun runSequence(vararg specs: CaptureSpec): List<String> {
        seedSession()
        specs.forEach { store.store(it.toCapture(), SESSION, null, it.generation, it.boot, it.id) }
        // Fill to the target with clean captures that are themselves always evictable, so the
        // only interesting question is whether the seeded middle survived.
        repeat(RoomCounterStore.TARGET_COUNTER_CAPTURES_PER_SESSION + 2) { i ->
            store.store(
                capture(elapsed = 100_000L + i * 1_000L, millis = 10_000L + i * 100L),
                SESSION, null, GEN, BOOT, "filler$i",
            )
        }
        return store.capturesFor(SESSION).map { it.captureId }
    }

    private fun assertEvicted(retained: List<String>, why: String, expectEvicted: Boolean) {
        val middleGone = "c1" !in retained
        assertEquals(why, expectEvicted, middleGone)
    }

    private data class CaptureSpec(
        val id: String,
        val index: Int,
        val counters: Map<String, Long>,
        val boot: BootIdentity,
        val generation: CounterGeneration,
        val checkin: Int,
        val platform: String,
    ) {
        fun toCapture() = BatteryStatsCapture(
            metadata = CaptureMetadata(
                sourceFormat = SourceFormat.CHECKIN,
                sourceFormatVersion = 9,
                captureElapsedRealtimeMillis = 1_000L + index * 1_000L,
                captureWallClockMillis = 1_700_000_000_000L + index * 1_000L,
                backendKind = BackendIdentity.Kind.SHELL,
                platformVersion = "16",
                payloadByteCount = 900_000,
                payloadHash = null,
                truncated = false,
            ),
            version = CheckinVersionBlock(9, checkin, 215L, platform, platform),
            kernelWakelocks = counters.map {
                KernelWakelockStat(it.key, it.value, 1L, AggregationWindow.SINCE_CHARGED)
            },
            partialWakelocks = emptyList(),
            uidPackages = emptyList(),
            unsupportedTags = emptyMap(),
            historyLineCount = 1,
            warnings = emptyList(),
        )
    }

    private fun cap(
        index: Int,
        millis: Long,
        boot: String = "boot-a",
        derivedBoot: Boolean = false,
        generation: Long = 3,
        checkin: Int = 36,
        platform: String = "BUILD.A",
    ) = CaptureSpec(
        id = "c$index",
        index = index,
        counters = mapOf("k" to millis),
        boot = if (derivedBoot) BootIdentity.Derived(1_700_000_000_000L) else BootIdentity.Kernel(boot),
        generation = CounterGeneration(generation),
        checkin = checkin,
        platform = platform,
    )

    private fun capMulti(index: Int, counters: Map<String, Long>) = CaptureSpec(
        id = "c$index",
        index = index,
        counters = counters,
        boot = BootIdentity.Kernel("boot-a"),
        generation = CounterGeneration(3),
        checkin = 36,
        platform = "BUILD.A",
    )

    private fun capture(
        elapsed: Long,
        millis: Long,
        name: String = "k",
    ) = BatteryStatsCapture(
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
        kernelWakelocks = listOf(KernelWakelockStat(name, millis, 1L, AggregationWindow.SINCE_CHARGED)),
        partialWakelocks = emptyList(),
        uidPackages = emptyList(),
        unsupportedTags = emptyMap(),
        historyLineCount = 1,
        warnings = emptyList(),
    )

    private suspend fun seedSession(target: BattInsightDatabase = db) {
        val snapshot = fullSnapshot(
            id = UUID.fromString(SNAPSHOT),
            sessionId = UUID.fromString(SESSION),
        )
        target.sessionDao().upsertSnapshots(listOf(Mappers.toEntity(snapshot)))
        target.sessionDao().upsertSessions(
            listOf(Mappers.toEntity(activeSession(id = UUID.fromString(SESSION), start = snapshot))),
        )
    }

    private companion object {
        const val SESSION = "00000000-0000-0000-0000-0000000000aa"
        const val SNAPSHOT = "00000000-0000-0000-0000-000000000011"
        val GEN = CounterGeneration(3)
        val BOOT = BootIdentity.Kernel("boot-a")
    }
}

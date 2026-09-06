package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.series.BatterySampleStore
import com.rmpsdroid.battinsight.series.SampleResult
import com.rmpsdroid.battinsight.series.SeriesGapReason
import com.rmpsdroid.battinsight.session.BatteryHealth
import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CaptureTime
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.ElapsedRealtime
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionTrigger
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The battery sample hard cap, and the watermark that keeps its consequences honest.
 *
 * The cap is **hard**, unlike the counter target: samples come from a timer and each one is
 * individually disposable, so an unbounded count is a runaway risk with no compensating value.
 *
 * The watermark is the subtle half. It records the greatest elapsed time **actually deleted**,
 * never the oldest surviving row -- a distinction Phase 9A.1 got wrong, where the mark landed
 * exactly where the retained series begins and the comparison meant to reveal the gap could
 * never fire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class BatterySampleRetentionTest {

    private lateinit var db: BattInsightDatabase
    private lateinit var store: RoomBatterySampleStore

    /** A small cap, so the boundary is exercised without inserting three hundred rows. */
    private val cap = 10

    @Before
    fun setUp() {
        db = testDatabase()
        store = RoomBatterySampleStore(db.batterySampleDao(), cap = cap, cadenceMillis = CADENCE)
    }

    @After
    fun tearDown() = db.close()

    // ------------------------------------------------------------------------ round trip

    @Test
    fun `a sample round-trips with every field intact`() = runTest {
        seedSession(SESSION_A)
        val result = store.record(SESSION_A, observation(1_000), SessionTrigger.PERIODIC, GEN)

        assertTrue(result is SampleResult.Stored)
        val point = store.samplesFor(SESSION_A).single()
        assertEquals(1_000L, point.elapsedRealtimeMillis)
        assertEquals(73, point.level)
        assertEquals(100, point.scale)
        assertEquals(BatteryStatus.DISCHARGING, point.status)
        assertEquals(PlugSource.NONE, point.plug)
        assertEquals(SessionTrigger.PERIODIC, point.trigger)
        assertEquals(BootIdentity.Kernel("boot-a"), point.bootIdentity)
    }

    @Test
    fun `absent measurements stay absent and are never zero-filled`() = runTest {
        seedSession(SESSION_A)
        store.record(
            SESSION_A,
            observation(1_000).copy(
                level = null, scale = null, temperatureDeciCelsius = null,
                voltageMilliVolts = null, chargeCounterMicroAmpHours = null,
            ),
            SessionTrigger.PERIODIC, GEN,
        )

        val point = store.samplesFor(SESSION_A).single()
        assertNull("a missing level is not 0%", point.level)
        assertNull(point.scale)
        assertNull(point.temperatureDeciCelsius)
        assertNull(point.voltageMilliVolts)
        assertNull(point.chargeCounterMicroAmpHours)
        assertNull("and no percentage is invented from nothing", point.percent)
    }

    @Test
    fun `a derived boot identity does not come back as a kernel one`() = runTest {
        // The Phase 6 rule: evidence strength must survive the round trip, because every
        // continuity decision downstream rests on how strong it actually was.
        seedSession(SESSION_A)
        store.record(
            SESSION_A,
            observation(1_000).copy(bootIdentity = BootIdentity.Derived(1_700_000_000_000L)),
            SessionTrigger.PERIODIC, GEN,
        )

        assertEquals(
            BootIdentity.Derived(1_700_000_000_000L),
            store.samplesFor(SESSION_A).single().bootIdentity,
        )
    }

    // -------------------------------------------------------------------------- the cap

    @Test
    fun `the cap is never exceeded by any committed state`() = runTest {
        seedSession(SESSION_A)
        repeat(cap * 3) { i ->
            store.record(SESSION_A, observation(1_000L + i * 1_000L), SessionTrigger.PERIODIC, GEN)
            // Checked after **every** commit, not once at the end: the promise is that no
            // observable state ever shows cap+1, not merely that it settles there.
            assertTrue(
                "count must never exceed the cap, saw ${store.countFor(SESSION_A)}",
                store.countFor(SESSION_A) <= cap,
            )
        }
        assertEquals(cap, store.countFor(SESSION_A))
    }

    @Test
    fun `eviction removes the oldest and keeps the newest`() = runTest {
        seedSession(SESSION_A)
        repeat(cap + 5) { i ->
            store.record(SESSION_A, observation(1_000L + i * 1_000L), SessionTrigger.PERIODIC, GEN)
        }

        val retained = store.samplesFor(SESSION_A).map { it.elapsedRealtimeMillis }
        assertEquals(cap, retained.size)
        assertEquals("the newest survive", 15_000L, retained.last())
        assertEquals("the oldest are gone", 6_000L, retained.first())
        assertEquals("and they are contiguous", (6..15).map { it * 1_000L }, retained)
    }

    @Test
    fun `sessions retain independently`() = runTest {
        seedSession(SESSION_A)
        seedSession(SESSION_B)
        repeat(cap + 3) { i ->
            store.record(SESSION_A, observation(1_000L + i * 1_000L), SessionTrigger.PERIODIC, GEN)
        }
        repeat(2) { i ->
            store.record(SESSION_B, observation(1_000L + i * 1_000L), SessionTrigger.PERIODIC, GEN)
        }

        assertEquals(cap, store.countFor(SESSION_A))
        assertEquals("B is nowhere near the cap and is untouched", 2, store.countFor(SESSION_B))
        assertNull("and B has evicted nothing", store.evictedThroughElapsedMillis(SESSION_B))
    }

    // --------------------------------------------------------------------- the watermark

    @Test
    fun `no eviction means no watermark`() = runTest {
        seedSession(SESSION_A)
        repeat(cap) { i ->
            store.record(SESSION_A, observation(1_000L + i * 1_000L), SessionTrigger.PERIODIC, GEN)
        }

        assertEquals(cap, store.countFor(SESSION_A))
        assertNull("exactly at the cap is not eviction", store.evictedThroughElapsedMillis(SESSION_A))
    }

    @Test
    fun `the watermark records what was deleted, not what survived`() = runTest {
        seedSession(SESSION_A)
        repeat(cap + 1) { i ->
            store.record(SESSION_A, observation(1_000L + i * 1_000L), SessionTrigger.PERIODIC, GEN)
        }

        val watermark = store.evictedThroughElapsedMillis(SESSION_A)
        val oldestRetained = store.samplesFor(SESSION_A).first().elapsedRealtimeMillis
        assertEquals("the one row actually removed", 1_000L, watermark)
        assertEquals(2_000L, oldestRetained)
        // The Phase 9A.1 defect, pinned: had the watermark been set to the oldest retained
        // row, it would equal 2000 here and the read model's gap test would never fire.
        assertTrue("the mark sits below the retained series", watermark!! < oldestRetained)
    }

    @Test
    fun `the watermark only ever advances`() = runTest {
        seedSession(SESSION_A)
        repeat(cap + 1) { i ->
            store.record(SESSION_A, observation(1_000L + i * 1_000L), SessionTrigger.PERIODIC, GEN)
        }
        val first = store.evictedThroughElapsedMillis(SESSION_A)!!

        repeat(5) { i ->
            store.record(SESSION_A, observation(100_000L + i * 1_000L), SessionTrigger.PERIODIC, GEN)
        }
        val second = store.evictedThroughElapsedMillis(SESSION_A)!!

        assertTrue("monotonic across cycles: $first -> $second", second > first)
    }

    @Test
    fun `a watermark makes the series open with a not-retained gap`() = runTest {
        seedSession(SESSION_A)
        repeat(cap + 2) { i ->
            store.record(SESSION_A, observation(1_000L + i * 1_000L), SessionTrigger.PERIODIC, GEN)
        }

        val series = store.seriesFor(SESSION_A)
        val first = series.elements.first()
        assertEquals(SeriesGapReason.NOT_RETAINED, (first as com.rmpsdroid.battinsight.series.SeriesGap).reason)
    }

    @Test
    fun `an untouched session produces no leading gap`() = runTest {
        seedSession(SESSION_A)
        store.record(SESSION_A, observation(1_000), SessionTrigger.PERIODIC, GEN)
        store.record(SESSION_A, observation(1_000 + CADENCE), SessionTrigger.PERIODIC, GEN)

        val series = store.seriesFor(SESSION_A)
        assertTrue("nothing was evicted, so nothing is declared", series.gaps.isEmpty())
        assertEquals(1, series.segments.size)
    }

    // ------------------------------------------------------------------ no orphan rows

    @Test
    fun `a sample without a session is refused rather than orphaned`() = runTest {
        val result = store.record("", observation(1_000), SessionTrigger.PERIODIC, GEN)

        assertEquals(SampleResult.NoActiveSession, result)
        assertEquals(0, db.batterySampleDao().totalCount())
    }

    @Test
    fun `the last sample is the newest, not merely the last inserted`() = runTest {
        seedSession(SESSION_A)
        store.record(SESSION_A, observation(5_000), SessionTrigger.PERIODIC, GEN)
        store.record(SESSION_A, observation(1_000), SessionTrigger.PERIODIC, GEN)

        assertEquals(5_000L, store.lastSampleFor(SESSION_A)!!.elapsedRealtimeMillis)
    }

    // --------------------------------------------------------------------------- helpers

    private suspend fun seedSession(sessionId: String) {
        val snapshotId = if (sessionId == SESSION_A) SNAPSHOT_A else SNAPSHOT_B
        val snapshot = fullSnapshot(
            id = UUID.fromString(snapshotId),
            sessionId = UUID.fromString(sessionId),
        )
        db.sessionDao().upsertSnapshots(listOf(Mappers.toEntity(snapshot)))
        db.sessionDao().upsertSessions(
            listOf(Mappers.toEntity(activeSession(id = UUID.fromString(sessionId), start = snapshot))),
        )
    }

    private fun observation(elapsed: Long) = BatteryObservation(
        time = CaptureTime(ElapsedRealtime(elapsed), EPOCH + elapsed, 330),
        bootIdentity = BootIdentity.Kernel("boot-a"),
        status = BatteryStatus.DISCHARGING,
        plug = PlugSource.NONE,
        level = 73,
        scale = 100,
        present = true,
        temperatureDeciCelsius = 251,
        voltageMilliVolts = 4123,
        chargeCounterMicroAmpHours = 3_210_000L,
        health = BatteryHealth.GOOD,
        trigger = SessionTrigger.BATTERY_CHANGED,
    )

    private companion object {
        const val SESSION_A = "00000000-0000-0000-0000-0000000000aa"
        const val SESSION_B = "00000000-0000-0000-0000-0000000000bb"
        const val SNAPSHOT_A = "00000000-0000-0000-0000-000000000011"
        const val SNAPSHOT_B = "00000000-0000-0000-0000-000000000022"
        const val CADENCE = 5L * 60_000L
        val GEN = CounterGeneration(3)
    }
}

/** The production cap is a hard 300; the tests above use a smaller one for speed. */
class BatterySampleCapConstantTest {
    @Test
    fun `the shipped cap is three hundred`() {
        assertEquals(300, BatterySampleStore.MAX_BATTERY_SAMPLES_PER_SESSION)
    }

    @Test
    fun `the shipped cadence is five minutes`() {
        assertEquals(5L * 60L * 1000L, BatterySampleStore.BATTERY_SAMPLE_CADENCE_MILLIS)
    }
}

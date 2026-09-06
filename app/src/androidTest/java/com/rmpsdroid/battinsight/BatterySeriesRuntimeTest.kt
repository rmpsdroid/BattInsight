package com.rmpsdroid.battinsight

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import com.rmpsdroid.battinsight.persistence.RoomBatterySampleStore
import com.rmpsdroid.battinsight.persistence.RoomSessionStateStore
import com.rmpsdroid.battinsight.platform.AndroidBatterySource
import com.rmpsdroid.battinsight.platform.AndroidBootIdentitySource
import com.rmpsdroid.battinsight.series.BatterySampler
import com.rmpsdroid.battinsight.series.SampleResult
import com.rmpsdroid.battinsight.series.SeriesGapReason
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.SessionCoordinator
import com.rmpsdroid.battinsight.session.SessionTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sampled series against the real database, on a real device.
 *
 * Robolectric proves the rules; this proves the wiring. It uses the production database at its
 * real path, the real battery source and the real session coordinator, because the failures
 * worth catching here are the ones that only exist when those are real: a foreign key that
 * refuses because the session is not committed yet, an enum that does not round-trip through
 * SQLite, a sample attributed to a session that no longer exists.
 */
@RunWith(AndroidJUnit4::class)
class BatterySeriesRuntimeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val db get() = BattInsightDatabase.get(context)

    @Before
    fun startFromACleanDatabase() = runBlocking {
        db.sessionDao().clearAll()
    }

    @Test
    fun aSampleIsStoredAgainstTheSessionTheObservationBelongsTo() = runBlocking {
        val sessionId = establishSession()
        val store = RoomBatterySampleStore(db.batterySampleDao())
        val sampler = BatterySampler(store)

        val observation = AndroidBatterySource(context, AndroidBootIdentitySource())
            .readCurrent(SessionTrigger.PERIODIC)!!

        val result = sampler.onObservation(sessionId, observation, CounterGeneration(1))

        // Asserted rather than assumed. The production path deliberately does not surface this
        // result to the UI, so a foreign key refusal here would otherwise be silent.
        assertTrue("the sample must be stored, was $result", result is SampleResult.Stored)
        assertEquals(1, store.countFor(sessionId))

        val point = store.samplesFor(sessionId).single()
        assertEquals(observation.time.elapsedRealtime.millis, point.elapsedRealtimeMillis)
        assertEquals(observation.status, point.status)
        assertEquals(observation.bootIdentity, point.bootIdentity)
    }

    @Test
    fun aSampleWithoutASessionIsRefusedRatherThanOrphaned() = runBlocking {
        val store = RoomBatterySampleStore(db.batterySampleDao())
        val sampler = BatterySampler(store)
        val observation = AndroidBatterySource(context, AndroidBootIdentitySource())
            .readCurrent(SessionTrigger.PERIODIC)!!

        assertEquals(
            SampleResult.NoActiveSession,
            sampler.onObservation(null, observation, CounterGeneration(1)),
        )
        assertEquals("nothing was written", 0, db.batterySampleDao().totalCount())
    }

    @Test
    fun aCadenceTickIsCoalescedWhenAnObservationAlreadyCoversTheWindow() = runBlocking {
        val sessionId = establishSession()
        val store = RoomBatterySampleStore(db.batterySampleDao())
        val sampler = BatterySampler(store)
        val source = AndroidBatterySource(context, AndroidBootIdentitySource())

        val first = source.readCurrent(SessionTrigger.BATTERY_CHANGED)!!
        assertTrue(sampler.onObservation(sessionId, first, CounterGeneration(1)).succeeded)

        // Immediately afterwards, so it falls well inside the cadence.
        val tick = source.readCurrent(SessionTrigger.PERIODIC)!!
        val result = sampler.onCadenceTick(sessionId, tick, CounterGeneration(1))

        assertEquals(SampleResult.Coalesced, result)
        assertEquals("still one sample, not two near-identical rows", 1, store.countFor(sessionId))
    }

    @Test
    fun theSeriesReportsAProcessRestartRatherThanConnectingAcrossIt() = runBlocking {
        val sessionId = establishSession()
        val store = RoomBatterySampleStore(db.batterySampleDao())
        val sampler = BatterySampler(store)
        val source = AndroidBatterySource(context, AndroidBootIdentitySource())

        // A sample taken normally, then one that announces itself as a fresh start -- which is
        // what the first sample after a process death looks like.
        sampler.onObservation(sessionId, source.readCurrent(SessionTrigger.PERIODIC)!!, GEN)
        sampler.onObservation(sessionId, source.readCurrent(SessionTrigger.APP_START)!!, GEN)

        val series = store.seriesFor(sessionId)

        assertEquals("both samples survive", 2, store.countFor(sessionId))
        assertEquals(
            "and they are not one connected run",
            SeriesGapReason.PROCESS_RESTART,
            series.gaps.single().reason,
        )
        assertEquals("two segments, one either side of the gap", 2, series.segments.size)
    }

    @Test
    fun samplesSurviveBeingReadBackThroughTheProductionDatabase() = runBlocking {
        val sessionId = establishSession()
        val store = RoomBatterySampleStore(db.batterySampleDao())
        val source = AndroidBatterySource(context, AndroidBootIdentitySource())

        repeat(3) {
            store.record(
                sessionId,
                source.readCurrent(SessionTrigger.PERIODIC)!!,
                SessionTrigger.PERIODIC,
                GEN,
            )
        }

        // A second store over the same database, to be sure nothing is cached in the first.
        val reader = RoomBatterySampleStore(db.batterySampleDao())
        assertEquals(3, reader.countFor(sessionId))
        assertTrue(
            "every sample carries a usable boot identity",
            reader.samplesFor(sessionId).all { it.bootIdentity.toString().isNotEmpty() },
        )
    }

    /** Drives the real coordinator so the session is committed exactly as production does. */
    private suspend fun establishSession(): String {
        val store = RoomSessionStateStore(db.sessionDao())
        val coordinator = SessionCoordinator(store = store, scope = CoroutineScope(SupervisorJob()))
        val observation = AndroidBatterySource(context, AndroidBootIdentitySource())
            .readCurrent(SessionTrigger.APP_START)!!
        coordinator.begin(observation)
        return coordinator.status.value.session!!.id.toString()
    }

    private companion object {
        val GEN = CounterGeneration(1)
    }
}

package com.rmpsdroid.battinsight

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.chart.SessionChartLoader
import com.rmpsdroid.battinsight.history.HistoryPresentation
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import com.rmpsdroid.battinsight.persistence.Mappers
import com.rmpsdroid.battinsight.persistence.RoomBatterySampleStore
import com.rmpsdroid.battinsight.persistence.RoomCounterStore
import com.rmpsdroid.battinsight.series.SeriesGapReason
import com.rmpsdroid.battinsight.session.BatteryHealth
import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CaptureTime
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.ElapsedRealtime
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionCoordinator
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.persistence.RoomSessionStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stored charts open without any privileged access.
 *
 * The guarantee Phase 8 made for the history list has to hold for the charts too: browsing
 * saved periods touches BattInsight's own database and nothing else. It works with Shizuku not
 * running, with no permission ever granted, and with the chosen access route broken.
 *
 * This builds the whole chart path — sample store, counter store, loader — from the production
 * database with **no runner, no backend and no capability report**, and asserts it produces a
 * chart. If any part of the path reached for a privileged capture it could not be constructed
 * this way at all.
 *
 * The series is a **synthetic fixture** written directly to the database. It exercises the
 * loader; it says nothing about real battery behaviour.
 */
@RunWith(AndroidJUnit4::class)
class ChartNoAccessRuntimeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val db get() = BattInsightDatabase.get(context)

    @Before
    fun startClean() = runBlocking { db.sessionDao().clearAll() }

    @Test
    fun aStoredSeriesBecomesAChartWithNoBackendInvolved() = runBlocking {
        val sessionId = seedSession()
        val sampleStore = RoomBatterySampleStore(db.batterySampleDao())
        val counterStore = RoomCounterStore(db.counterDao())

        // Five readings within cadence, then a long silence, then two more: one connected run,
        // a break, and a second run.
        listOf(0L, 1L, 2L, 3L, 4L).forEach { i ->
            sampleStore.record(sessionId, observation(i * 300_000L, 80 - i.toInt()), SessionTrigger.PERIODIC, GEN)
        }
        listOf(40L, 41L).forEach { i ->
            sampleStore.record(sessionId, observation(i * 300_000L, 60 - (i - 40).toInt()), SessionTrigger.PERIODIC, GEN)
        }

        // Constructed with nothing privileged: no runner, no Shizuku, no capability report.
        val loader = SessionChartLoader(
            sampleStore = sampleStore,
            counterCaptures = { counterStore.capturesFor(it) },
            refusalCopy = { HistoryPresentation.unavailableReason(it) },
        )

        val charts = loader.load(sessionId)

        assertEquals("seven readings survived the round trip", 7, charts.battery.summary.observationCount)
        assertEquals("two connected runs", 2, charts.battery.summary.connectedSegmentCount)
        assertEquals(1, charts.battery.gaps.size)
        assertEquals(SeriesGapReason.NOT_OBSERVED, charts.battery.gaps.single().reason)
        assertEquals(80, charts.battery.summary.firstPercent)
        assertEquals(59, charts.battery.summary.lastPercent)
        assertTrue(
            "the gap explains itself in words",
            charts.battery.gaps.single().description.contains("No readings were taken"),
        )
    }

    @Test
    fun aSessionWithNoCountersStillProducesABatteryChart() = runBlocking {
        // The common case: samples exist, no one pressed refresh. The battery chart must not
        // depend on privileged data being present.
        val sessionId = seedSession()
        val sampleStore = RoomBatterySampleStore(db.batterySampleDao())
        val counterStore = RoomCounterStore(db.counterDao())
        sampleStore.record(sessionId, observation(0, 80), SessionTrigger.PERIODIC, GEN)
        sampleStore.record(sessionId, observation(300_000L, 79), SessionTrigger.PERIODIC, GEN)

        val charts = SessionChartLoader(
            sampleStore, { counterStore.capturesFor(it) }, { HistoryPresentation.unavailableReason(it) },
        ).load(sessionId)

        assertEquals(1, charts.battery.summary.connectedSegmentCount)
        assertTrue("no captures means no counter intervals", charts.kernel.isEmpty)
        assertTrue(charts.application.isEmpty)
    }

    @Test
    fun aSessionWithNoSamplesProducesAnEmptyChartRatherThanFailing() = runBlocking {
        val sessionId = seedSession()
        val counterStore = RoomCounterStore(db.counterDao())

        val charts = SessionChartLoader(
            RoomBatterySampleStore(db.batterySampleDao()),
            { counterStore.capturesFor(it) },
            { HistoryPresentation.unavailableReason(it) },
        ).load(sessionId)

        assertTrue(charts.battery.isEmpty)
        assertEquals(0, charts.battery.summary.observationCount)
    }

    // --------------------------------------------------------------------------- helpers

    private suspend fun seedSession(): String {
        val store = RoomSessionStateStore(db.sessionDao())
        val coordinator = SessionCoordinator(store = store, scope = CoroutineScope(SupervisorJob()))
        coordinator.begin(observation(0, 80).copy(trigger = SessionTrigger.APP_START))
        return coordinator.status.value.session!!.id.toString()
    }

    private fun observation(elapsed: Long, level: Int) = BatteryObservation(
        time = CaptureTime(ElapsedRealtime(elapsed), 1_700_000_000_000L + elapsed, 330),
        bootIdentity = BootIdentity.Kernel("boot-under-test"),
        status = BatteryStatus.DISCHARGING,
        plug = PlugSource.NONE,
        level = level,
        scale = 100,
        present = true,
        temperatureDeciCelsius = 251,
        voltageMilliVolts = 4123,
        chargeCounterMicroAmpHours = 3_210_000L,
        health = BatteryHealth.GOOD,
        trigger = SessionTrigger.PERIODIC,
    )

    private companion object {
        val GEN = CounterGeneration(1)
    }
}

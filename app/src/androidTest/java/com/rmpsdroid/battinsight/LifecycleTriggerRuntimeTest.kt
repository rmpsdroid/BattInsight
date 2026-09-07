package com.rmpsdroid.battinsight

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import com.rmpsdroid.battinsight.persistence.RoomBatterySampleStore
import com.rmpsdroid.battinsight.persistence.RoomSessionStateStore
import com.rmpsdroid.battinsight.chart.GapCopy
import com.rmpsdroid.battinsight.series.SeriesGapReason
import com.rmpsdroid.battinsight.session.BatteryHealth
import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CaptureTime
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.ElapsedRealtime
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.ProcessStartGate
import com.rmpsdroid.battinsight.session.SessionCoordinator
import com.rmpsdroid.battinsight.session.SessionTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A backgrounded application is not a crashed one — through the real storage and series path.
 *
 * The JVM tests in `LifecycleGapCausalityTest` pin the rule. This runs the same distinction
 * through the **production Room database and the production series builder** on a device, so
 * the guarantee is not merely a property of hand-built domain objects.
 *
 * Both cases record `Process.myPid()`, because the whole claim under test is about process
 * identity. A same-process resume that reported a restart is the Phase 10A.1 defect (P1-1),
 * measured on a Samsung SM-M156B that held pid 31963 for an entire session while the UI said
 * "BattInsight stopped running and started again".
 *
 * The genuinely-destructive half — killing the process and proving a new one still reports
 * `PROCESS_RESTART` — cannot run inside a single instrumentation invocation, because the
 * instrumentation lives in the process being killed. That proof is the existing
 * `tools/process-death-proof.sh` pattern and the physical-device run recorded in the Phase 10A
 * report. What is proved here is that a *fresh process instance* restores start semantics,
 * modelled by a fresh [ProcessStartGate] — which is exactly what process death produces.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleTriggerRuntimeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val db get() = BattInsightDatabase.get(context)

    @Before
    fun startClean() = runBlocking { db.sessionDao().clearAll() }

    @Test
    fun aSameProcessResumeIsNotReportedAsAProcessRestart() = runBlocking {
        val pidBefore = Process.myPid()
        val gate = ProcessStartGate()
        val sessionId = seedSession()
        val store = RoomBatterySampleStore(db.batterySampleDao())

        // Launch: the first sampling call in this process claims the start.
        val first = triggerFor(gate)
        assertEquals(SessionTrigger.APP_START, first)
        store.record(sessionId, observation(0, 80), first, GEN)

        // Hidden for well over the cadence, then the UI returns. Same process throughout.
        val second = triggerFor(gate)
        assertEquals("a living process must not announce a start again", SessionTrigger.APP_VISIBLE, second)
        store.record(sessionId, observation(HIDDEN_MILLIS, 78), second, GEN)

        val pidAfter = Process.myPid()
        assertEquals("the process must not have changed for this test to mean anything", pidBefore, pidAfter)

        val series = store.seriesFor(sessionId)
        val gap = series.gaps.single()

        assertEquals(
            "same pid $pidBefore throughout, so this is an absence of observation, not a death",
            SeriesGapReason.NOT_OBSERVED,
            gap.reason,
        )
        // The user-facing sentence for this reason must not claim the app died.
        val copy = GapCopy.description(gap.reason)
        assertFalse(
            "the explanation must not tell the user the app stopped running: \"$copy\"",
            copy.contains("stopped running"),
        )
        assertTrue(
            "and it must say what actually happened",
            copy.contains("No readings were taken"),
        )
    }

    @Test
    fun aFreshProcessInstanceStillReportsAProcessRestart() = runBlocking {
        val sessionId = seedSession()
        val store = RoomBatterySampleStore(db.batterySampleDao())

        // A reading from the process that came before.
        val old = ProcessStartGate()
        store.record(sessionId, observation(0, 80), triggerFor(old), GEN)

        // The process dies. A new one starts, and its gate is unclaimed — that is what process
        // death does, and it is the only thing that restores the start announcement.
        val fresh = ProcessStartGate()
        val afterDeath = triggerFor(fresh)
        assertEquals(SessionTrigger.APP_START, afterDeath)
        store.record(sessionId, observation(HIDDEN_MILLIS, 78), afterDeath, GEN)

        assertEquals(
            "process death must still be reported as a restart",
            SeriesGapReason.PROCESS_RESTART,
            store.seriesFor(sessionId).gaps.single().reason,
        )
    }

    @Test
    fun repeatedVisibilityCyclesInOneProcessNeverAccumulateRestarts() = runBlocking {
        val pid = Process.myPid()
        val gate = ProcessStartGate()
        val sessionId = seedSession()
        val store = RoomBatterySampleStore(db.batterySampleDao())

        store.record(sessionId, observation(0, 80), triggerFor(gate), GEN)
        for (i in 1..3) {
            store.record(sessionId, observation(HIDDEN_MILLIS * i, 80 - i, ), triggerFor(gate), GEN)
        }

        assertEquals("still one process", pid, Process.myPid())
        val series = store.seriesFor(sessionId)
        assertEquals(3, series.gaps.size)
        assertTrue(
            "no cycle may claim the process died",
            series.gaps.all { it.reason == SeriesGapReason.NOT_OBSERVED },
        )
    }

    // --------------------------------------------------------------------------- helpers

    /** The production rule from `sampleOnBecomingVisible`. */
    private fun triggerFor(gate: ProcessStartGate): SessionTrigger =
        if (gate.claimStart()) SessionTrigger.APP_START else SessionTrigger.APP_VISIBLE

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

        /** Comfortably beyond the cadence tolerance, so a gap is genuinely required. */
        const val HIDDEN_MILLIS = 30L * 60_000L
    }
}

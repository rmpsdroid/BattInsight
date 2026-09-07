package com.rmpsdroid.battinsight

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import com.rmpsdroid.battinsight.persistence.RoomSessionStateStore
import com.rmpsdroid.battinsight.session.BatteryHealth
import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CaptureTime
import com.rmpsdroid.battinsight.session.ElapsedRealtime
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.ProcessStartGate
import com.rmpsdroid.battinsight.session.SessionCoordinator
import com.rmpsdroid.battinsight.session.SessionTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repeated cold starts against a continuous interval must not multiply sessions.
 *
 * ## Why this test exists, and why the old suite missed the defect
 *
 * Phase 10A.2 found a Samsung SM-M156B splitting one continuous discharge interval into two
 * open sessions after an ordinary restart. Every existing test called `begin()` first and then
 * `observe()`, honouring the precondition that *production could not honour* -- so the suite
 * validated the contract rather than the caller. A scan of every test method found **zero**
 * where `.observe(` preceded `.begin(`.
 *
 * This runs against the **real Room store on a device**, and models the production start-up
 * relationship rather than a coordinator called politely in order: each simulated process
 * launches the reconciliation reading and the lifecycle-visible sampling reading
 * **concurrently**, exactly as `viewModelScope` and `lifecycleScope` do, and does so
 * repeatedly so that either ordering is exercised.
 *
 * The invariant is that the outcome does not depend on which one wins.
 */
@RunWith(AndroidJUnit4::class)
class SessionStartupOrderingRuntimeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val db get() = BattInsightDatabase.get(context)

    @Before
    fun startClean() = runBlocking { db.sessionDao().clearAll() }

    @Test
    fun repeatedColdStartsAgainstOneIntervalDoNotMultiplySessions() = runBlocking {
        val store = RoomSessionStateStore(db.sessionDao())

        // A previous process establishes a discharge interval.
        val first = SessionCoordinator(store = store, scope = CoroutineScope(SupervisorJob()))
        first.begin(observation(0, SessionTrigger.APP_START))
        first.observe(observation(MINUTE, SessionTrigger.BATTERY_CHANGED))
        val original = first.status.value.session!!.id
        assertEquals("one interval to begin with", 1, db.sessionDao().sessionCount())

        // Eight fresh "processes", each racing its two start-up readings, as production does.
        var elapsed = 2 * MINUTE
        repeat(COLD_STARTS) { i ->
            val scope = CoroutineScope(SupervisorJob())
            val coordinator = SessionCoordinator(store = store, scope = scope)
            val gate = ProcessStartGate() // a fresh process gets a fresh gate

            val startupReading = elapsed
            val samplerReading = elapsed + 2   // the 2 ms separation measured on the Samsung
            elapsed += MINUTE

            // Path A (viewModelScope) and Path B (lifecycleScope) with no ordering between them.
            listOf(
                async {
                    coordinator.begin(observation(startupReading, SessionTrigger.APP_START))
                },
                async {
                    val trigger = if (gate.claimStart()) {
                        SessionTrigger.APP_START
                    } else {
                        SessionTrigger.APP_VISIBLE
                    }
                    coordinator.observe(observation(samplerReading, trigger))
                },
            ).awaitAll()

            assertEquals(
                "cold start ${i + 1} must not create a session",
                1,
                db.sessionDao().sessionCount(),
            )
            assertEquals(
                "cold start ${i + 1} must continue the original interval",
                original,
                coordinator.status.value.session?.id,
            )
        }

        assertEquals("still exactly one interval after $COLD_STARTS cold starts", 1, db.sessionDao().sessionCount())
    }

    @Test
    fun theSamplerWinningTheRaceStillAdoptsTheStoredInterval() = runBlocking {
        val store = RoomSessionStateStore(db.sessionDao())

        val first = SessionCoordinator(store = store, scope = CoroutineScope(SupervisorJob()))
        first.begin(observation(0, SessionTrigger.APP_START))
        val original = first.status.value.session!!.id

        // The exact losing order from the physical defect: the sampler reaches the
        // coordinator first, and the reconciliation reading arrives afterwards.
        val next = SessionCoordinator(store = store, scope = CoroutineScope(SupervisorJob()))
        next.observe(observation(10 * MINUTE, SessionTrigger.APP_VISIBLE))
        next.begin(observation(10 * MINUTE + 2, SessionTrigger.APP_START))

        assertEquals("the stored interval must be adopted, not replaced", original, next.status.value.session?.id)
        assertEquals("no second session", 1, db.sessionDao().sessionCount())
    }

    @Test
    fun aGenuinePowerChangeAcrossTheRestartIsStillABoundary() = runBlocking {
        val store = RoomSessionStateStore(db.sessionDao())

        val first = SessionCoordinator(store = store, scope = CoroutineScope(SupervisorJob()))
        first.begin(observation(0, SessionTrigger.APP_START))
        val original = first.status.value.session!!.id

        // Came back plugged in, and the sampler wins the race. A real boundary must survive
        // the fix -- the point is to stop *fabricated* splits, not to stop real ones.
        val next = SessionCoordinator(store = store, scope = CoroutineScope(SupervisorJob()))
        next.observe(charging(20 * MINUTE, SessionTrigger.APP_VISIBLE))

        assertEquals("a real transition still opens an interval", 2, db.sessionDao().sessionCount())
        assertTrue("and it is a different interval", original != next.status.value.session?.id)
    }

    // --------------------------------------------------------------------------- helpers

    private fun observation(elapsed: Long, trigger: SessionTrigger) = BatteryObservation(
        time = CaptureTime(ElapsedRealtime(elapsed), EPOCH + elapsed, 0),
        bootIdentity = BootIdentity.Kernel("boot-under-test"),
        status = BatteryStatus.DISCHARGING,
        plug = PlugSource.NONE,
        level = 60,
        scale = 100,
        present = true,
        temperatureDeciCelsius = 251,
        voltageMilliVolts = 4123,
        chargeCounterMicroAmpHours = 3_210_000L,
        health = BatteryHealth.GOOD,
        trigger = trigger,
    )

    private fun charging(elapsed: Long, trigger: SessionTrigger) =
        observation(elapsed, trigger).copy(status = BatteryStatus.CHARGING, plug = PlugSource.AC)

    private companion object {
        const val MINUTE = 60_000L
        const val EPOCH = 1_700_000_000_000L
        const val COLD_STARTS = 8
    }
}

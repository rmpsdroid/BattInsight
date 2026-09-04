package com.rmpsdroid.battinsight

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import com.rmpsdroid.battinsight.persistence.RoomSessionStateStore
import com.rmpsdroid.battinsight.platform.AndroidBatterySource
import com.rmpsdroid.battinsight.platform.AndroidBootIdentitySource
import com.rmpsdroid.battinsight.session.SessionCoordinator
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.StoredState
import com.rmpsdroid.battinsight.session.TransitionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proof that a session survives the death of the process that created it.
 *
 * This is the claim Phase 6 exists to make, and it cannot be made on the JVM: an in-memory
 * database that a test closes and reopens has not survived anything. The proof needs a real
 * process, really killed.
 *
 * So it runs as **two separate instrumentation invocations** with `am force-stop` between
 * them, driven by `tools/process-death-proof.sh`:
 *
 *   1. [recordSession] opens the production database at its real path, drives the real
 *      coordinator with a real battery reading, and prints the session id it stored.
 *   2. The harness force-stops the application. Instrumentation runs inside the app's own
 *      process, so this genuinely destroys the process that did step 1 -- nothing is
 *      handed over in memory.
 *   3. [resumeSession] runs in a brand new process and is given that id as an argument. It
 *      must find the same interval, not start a fresh one.
 *
 * Running the two halves as ordinary sequential test methods would prove nothing at all:
 * they would share a process, a Room singleton and a warm page cache.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun coordinator(): Pair<SessionCoordinator, RoomSessionStateStore> {
        val store = RoomSessionStateStore(BattInsightDatabase.get(context).sessionDao())
        return SessionCoordinator(store = store, scope = CoroutineScope(SupervisorJob())) to store
    }

    private suspend fun currentObservation(trigger: SessionTrigger) =
        AndroidBatterySource(context, AndroidBootIdentitySource())
            .readCurrent(trigger)
            ?: error("no battery reading available; ACTION_BATTERY_CHANGED is sticky and should never be absent")

    /**
     * Step 1: establish and store a session, and report its identity to the harness.
     *
     * Starts from a cleared database so the assertion in step 3 is about this run and not
     * about whatever a previous one left behind.
     */
    @Test
    fun recordSession(): Unit = runBlocking {
        val (coordinator, store) = coordinator()
        assertTrue("the database must be clearable", store.clear().succeeded)

        // From a cleared database the engine has nothing to reconcile against, so the first
        // observation opens an interval outright rather than closing one.
        val result = coordinator.begin(currentObservation(SessionTrigger.APP_START))
        assertTrue(
            "a first observation must open an interval, was $result",
            result is TransitionResult.Started,
        )

        val status = coordinator.status.value
        val session = assertNotNull("a session must exist", status.session).let { status.session!! }
        assertTrue(
            "and it must have been stored: ${status.persistence}",
            status.persistence!!.succeeded,
        )

        val counts = store.counts()!!
        assertEquals("exactly one interval", 1, counts.sessions)
        assertTrue("with at least its start snapshot", counts.snapshots >= 1)

        // Handed to the harness through logcat rather than stdout: an instrumented test's
        // println goes nowhere the runner reports. The harness clears the buffer first, so
        // this tag carries exactly one line.
        Log.i(TAG, "$MARKER${session.id}")
        Log.i(TAG, "$PID_MARKER${android.os.Process.myPid()}")
    }

    /**
     * Step 3: a new process must recover the same interval.
     *
     * The `expectedSessionId` argument comes from step 1, in a process that no longer
     * exists. Recovering it here means the identity came out of the database.
     */
    @Test
    fun resumeSession(): Unit = runBlocking {
        val expected = InstrumentationRegistry.getArguments().getString("expectedSessionId")
            ?: error("expectedSessionId must be supplied; run tools/process-death-proof.sh")

        Log.i(TAG, "$PID_MARKER${android.os.Process.myPid()}")
        val (coordinator, store) = coordinator()

        // Read the raw stored state first, before anything reconciles: this is the database
        // speaking, with no engine logic in between.
        val stored = store.load()
        assertTrue("stored state must load, was $stored", stored is StoredState.Loaded)
        assertEquals(
            "the stored session must be the one the dead process created",
            expected,
            (stored as StoredState.Loaded).state.session!!.id.toString(),
        )

        // Then the whole path the application actually takes at start-up.
        coordinator.begin(currentObservation(SessionTrigger.APP_START))
        val status = coordinator.status.value

        assertEquals(
            "start-up must continue the stored interval, not open a new one",
            expected,
            status.session!!.id.toString(),
        )
        assertTrue("and loading must not have failed", status.loadFailure == null)
        assertEquals(
            "no second interval may have been created",
            1,
            store.counts()!!.sessions,
        )
    }

    private companion object {
        const val TAG = "BattInsightProof"
        const val MARKER = "BATTINSIGHT_SESSION_ID="

        /**
         * Each run reports its own pid, and the harness requires the two to differ.
         *
         * Without it the proof has a hole: if both halves somehow ran in one surviving
         * process, step 3 could be reading a warm Room singleton and would pass having
         * demonstrated nothing about durability.
         */
        const val PID_MARKER = "BATTINSIGHT_PID="
    }
}

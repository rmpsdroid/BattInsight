package com.rmpsdroid.battinsight

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.platform.AndroidShizukuGateway
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

/**
 * Asks Shizuku to authorise BattInsight, so the backend can be validated at all.
 *
 * Deliberately a separate class from [CapabilityRuntimeTest], and never run as part of the
 * suite: it is the one thing in the instrumented tests that *changes* state. Everything in
 * [CapabilityRuntimeTest] observes. Keeping the state change in its own class, invoked by
 * name, means no ordinary test run can quietly alter the device.
 *
 * ```
 * am instrument -w -e class com.rmpsdroid.battinsight.ShizukuAuthorisationHarness ...
 * ```
 *
 * What it changes is narrow: Shizuku's own client list gains BattInsight. It grants none of
 * the three Android permissions -- that is Phase 4's job and is forbidden here. Shizuku's
 * authorisation is separate from the Android permission model, which is exactly the point
 * measured earlier: granting `moe.shizuku.manager.permission.API_V23` with `pm grant`
 * reported success while Shizuku still refused, because Shizuku keeps its own list.
 *
 * The confirmation dialog belongs to Shizuku, so it is dismissed from outside by the
 * harness driving this. This test raises the request and waits for the result.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class ShizukuAuthorisationHarness {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gateway = AndroidShizukuGateway(context)

    @Test
    fun requestAuthorisationAndWaitForTheDecision() = runBlocking {
        // Two locks, not one. Being in its own class is not enough on its own: a plain suite
        // run would still reach this method once Shizuku happened to be running, and would
        // then raise a consent dialog nobody asked for. The explicit argument means the only
        // way to change device state is to say so.
        assumeTrue(
            "opt-in required: pass -e " + ARG_AUTHORISE + " true",
            InstrumentationRegistry.getArguments().getString(ARG_AUTHORISE) == "true",
        )

        val before = gateway.state()
        Log.i(TAG, "state before request: " + before)
        assumeTrue(
            "Shizuku must be running to be asked (state: " + before + ")",
            before is ShizukuState.RunningNotAuthorised || before is ShizukuState.RunningAuthorised,
        )
        if (before is ShizukuState.RunningAuthorised) {
            Log.i(TAG, "already authorised; nothing to change")
            return@runBlocking
        }

        Shizuku.requestPermission(REQUEST_CODE)

        var state = gateway.state()
        var waited = 0L
        while (state !is ShizukuState.RunningAuthorised && waited < TIMEOUT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            state = gateway.state()
        }
        Log.i(TAG, "state after " + waited + "ms: " + state)
        assertTrue(
            "Shizuku did not authorise BattInsight within " + TIMEOUT_MS + "ms (state: " + state + ")",
            state is ShizukuState.RunningAuthorised,
        )
    }

    private companion object {
        const val TAG = "BattInsightRuntime"
        const val ARG_AUTHORISE = "authoriseShizuku"
        const val REQUEST_CODE = 4919
        const val TIMEOUT_MS = 90_000L
        const val POLL_MS = 1_000L
    }
}

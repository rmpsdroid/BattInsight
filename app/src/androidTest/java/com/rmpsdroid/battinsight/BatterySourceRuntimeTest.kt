package com.rmpsdroid.battinsight

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.platform.AndroidBatterySource
import com.rmpsdroid.battinsight.platform.AndroidBootIdentitySource
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.BootRelation
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.PowerAttachment
import com.rmpsdroid.battinsight.session.SessionCoordinator
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.TransitionResult
import com.rmpsdroid.battinsight.session.relationTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The handful of session facts that only a real platform can settle.
 *
 * The engine itself is pure and is tested on the JVM; nothing here re-tests a transition
 * rule. What is measured here is what Android actually provides: whether the boot
 * identifier is readable from an ordinary application, whether the sticky battery intent
 * can be read without registering anything, and whether a context-registered receiver
 * behaves as the adapter assumes.
 *
 * **Read-only.** Nothing here changes battery state. `dumpsys battery set`, `unplug` and
 * their equivalents are deliberately not used: simulating a charge transition would need
 * separate approval, and none of these tests requires it.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class BatterySourceRuntimeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bootSource = AndroidBootIdentitySource()
    private val source = AndroidBatterySource(context, bootSource)

    // ------------------------------------------------------------------- boot identity

    /**
     * Whether `/proc/sys/kernel/random/boot_id` is readable by an ordinary application.
     *
     * This is a measurement, not an assertion of a preferred answer. It decides whether the
     * product can reason about boots at all: with the kernel identifier the engine can prove
     * two readings share a boot or do not, and without it the fallback proves neither, so
     * every monotonic comparison is refused rather than guessed.
     */
    @Test
    fun bootIdentifierReadabilityIsMeasured() {
        val file = File("/proc/sys/kernel/random/boot_id")
        val exists = file.exists()
        val canRead = file.canRead()
        val content = if (canRead) runCatching { file.readText().trim() }.getOrNull() else null

        Log.i(TAG, "boot_id exists=$exists canRead=$canRead length=${content?.length ?: 0}")

        val identity = bootSource.read()
        Log.i(TAG, "resolved boot identity: ${identity::class.simpleName} (${identity.abbreviated})")

        // Whichever answer the platform gives, the source must produce a usable identity
        // and must be honest about how strong it is.
        assertTrue(
            "the source must never return Unknown; it has a fallback",
            identity !is BootIdentity.Unknown,
        )
        if (canRead && !content.isNullOrEmpty()) {
            assertTrue("a readable boot_id must produce a Kernel identity", identity is BootIdentity.Kernel)
            assertTrue("and that identity can establish a boot relation", identity.canProveBootRelation)
        } else {
            assertTrue("an unreadable boot_id must degrade to Derived", identity is BootIdentity.Derived)
            assertTrue(
                "and must not claim it can establish a boot relation",
                !identity.canProveBootRelation,
            )
        }
    }

    @Test
    fun theBootIdentityIsStableWithinOneProcess() {
        val a = bootSource.read()
        val b = AndroidBootIdentitySource().read()
        Log.i(TAG, "two reads: ${a.abbreviated} and ${b.abbreviated}")

        // A boot identity cannot change without a reboot, and a reboot ends this process.
        // Two independently constructed sources must therefore agree, or at minimum must
        // not claim to disagree.
        assertTrue(
            "independent reads must not report different boots",
            a.relationTo(b) != BootRelation.DIFFERENT,
        )
    }

    // --------------------------------------------------------------------- sticky intent

    @Test
    fun theStickyBatteryIntentCanBeReadWithoutRegisteringAReceiver() {
        val intent = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        assertNotNull("ACTION_BATTERY_CHANGED must be sticky", intent)
        Log.i(TAG, "sticky extras: ${intent!!.extras?.keySet()}")
    }

    @Test
    fun currentStateMapsToACoherentObservation() = runBlocking {
        val observation = source.readCurrent(SessionTrigger.APP_START)
        assertNotNull("a current reading must be obtainable", observation)
        val o = observation!!

        Log.i(
            TAG,
            "observation: status=${o.status} plug=${o.plug} attachment=${o.powerAttachment} " +
                "level=${o.levelPercent} present=${o.present} " +
                "temperature=${o.temperatureDeciCelsius} voltage=${o.voltageMilliVolts} " +
                "chargeCounter=${o.chargeCounterMicroAmpHours} health=${o.health}",
        )
        Log.i(
            TAG,
            "time: elapsed=${o.time.elapsedRealtime.millis} " +
                "utcOffsetMinutes=${o.time.utcOffsetMinutes} boot=${o.bootIdentity.abbreviated}",
        )

        assertTrue("the monotonic reading must be positive", o.time.elapsedRealtime.millis > 0)
        assertTrue("a wall clock must be present", o.time.wallClockMillis > 0)
        o.levelPercent?.let {
            assertTrue("a percentage must be in range, was $it", it in 0..100)
        }
        // The mapping must be total: an unrecognised platform value maps to a named case,
        // never to a crash or a silently wrong one.
        assertNotNull(o.status)
        assertNotNull(o.plug)

        // Attachment must agree with the plug whenever the plug says anything.
        if (o.plug.isAttached) {
            assertEquals(PowerAttachment.ATTACHED, o.powerAttachment)
        } else if (o.plug == PlugSource.NONE) {
            assertEquals(PowerAttachment.DETACHED, o.powerAttachment)
        }
    }

    @Test
    fun theEmulatorReportsAPlausibleBatteryState() = runBlocking {
        val o = source.readCurrent()!!
        // Recorded rather than asserted to a particular value: the point is to know what
        // this platform actually says, not to require it to say something in particular.
        Log.i(TAG, "platform battery state: ${o.status} on ${o.plug}")
        assertTrue(
            "status must be one of the modelled values",
            o.status in BatteryStatus.entries,
        )
    }

    // ------------------------------------------------------------------ receiver lifecycle

    /**
     * Registering and unregistering must be symmetric.
     *
     * A receiver left registered outlives the screen that wanted it and keeps a process
     * alive for no reason; unregistering one that was never registered throws. The flow
     * built into the adapter has to get both right, and doing it repeatedly is where an
     * asymmetry shows up.
     */
    @Test
    fun registeringAndUnregisteringRepeatedlyIsClean() = runBlocking {
        repeat(5) {
            val job = CoroutineScope(Dispatchers.Default).launch {
                source.observations().collect { }
            }
            kotlinx.coroutines.delay(120)
            job.cancel()
            job.join()
        }
        // Reaching here without an IllegalArgumentException is the assertion. A leaked or
        // double-unregistered receiver throws from the framework.
        assertTrue(true)
    }

    @Test
    fun aLiveObservationReachesTheEngineAndOpensASession() = runBlocking {
        val coordinator = SessionCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val observation = source.readCurrent(SessionTrigger.APP_START)!!
        val result = coordinator.begin(observation)

        Log.i(TAG, "engine result on a real observation: $result")
        Log.i(
            TAG,
            "session: type=${coordinator.status.value.session?.type} " +
                "generation=${coordinator.status.value.counterGeneration}",
        )

        assertTrue("a real reading must open a session", result is TransitionResult.Started)
        assertNotNull(coordinator.status.value.session)
        assertTrue(coordinator.status.value.isActive)
    }

    @Test
    fun elapsedRealtimeAdvancesAndIsUsedForDurations() = runBlocking {
        val before = SystemClock.elapsedRealtime()
        val first = source.readCurrent()!!
        kotlinx.coroutines.delay(250)
        val second = source.readCurrent()!!
        val after = SystemClock.elapsedRealtime()

        Log.i(
            TAG,
            "elapsed: $before -> ${first.time.elapsedRealtime.millis} -> " +
                "${second.time.elapsedRealtime.millis} -> $after",
        )

        assertTrue(
            "the monotonic clock must advance between readings",
            second.time.elapsedRealtime > first.time.elapsedRealtime,
        )
        assertTrue(
            "and both must fall inside the window we measured",
            first.time.elapsedRealtime.millis >= before && second.time.elapsedRealtime.millis <= after,
        )
    }

    /**
     * BattInsight declares no receiver of its own, and grows no unguarded one from a library.
     *
     * The battery broadcasts are system-sent, so nothing else has any business delivering to
     * us: the adapter registers `RECEIVER_NOT_EXPORTED` at runtime and the manifest declares
     * nothing. Phase 5 adds no manifest receiver deliberately -- correctness after process
     * death comes from cold-start reconciliation instead.
     *
     * The merged manifest does contain one receiver, and it is worth naming rather than
     * asserting away: `androidx.profileinstaller.ProfileInstallReceiver`, arriving
     * transitively with Compose. It is exported by design so `adb shell cmd package compile`
     * can reach it, and it is guarded by `android.permission.DUMP`, which only shell and
     * system hold. That guard is the part that matters, so it is what this pins.
     */
    @Test
    fun theApplicationDeclaresNoReceiverOfItsOwnAndNoUnguardedOne() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_RECEIVERS,
        )
        val receivers = packageInfo.receivers?.toList() ?: emptyList()
        Log.i(
            TAG,
            "declared receivers: " + receivers.map {
                Triple(it.name, it.exported, it.permission)
            },
        )

        val ours = receivers.filter { it.name.startsWith(context.packageName) }
        assertTrue(
            "BattInsight must declare no manifest receiver of its own. Found: " +
                ours.map { it.name },
            ours.isEmpty(),
        )

        receivers.filter { it.exported }.forEach {
            assertNotNull(
                "an exported receiver must be permission-guarded: ${it.name}",
                it.permission,
            )
        }

        assertEquals(
            "the only receiver present should be AndroidX's profile installer",
            listOf("androidx.profileinstaller.ProfileInstallReceiver"),
            receivers.map { it.name },
        )
        assertEquals(
            "and it must stay guarded by a permission ordinary apps cannot hold",
            "android.permission.DUMP",
            receivers.single().permission,
        )
    }

    private companion object {
        const val TAG = "BattInsightSession"
    }
}

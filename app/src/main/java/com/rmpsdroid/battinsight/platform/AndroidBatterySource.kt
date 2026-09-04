package com.rmpsdroid.battinsight.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.rmpsdroid.battinsight.session.BatteryHealth
import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.BatteryObservationSource
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.BootIdentitySource
import com.rmpsdroid.battinsight.session.CaptureTime
import com.rmpsdroid.battinsight.session.ElapsedRealtime
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

/**
 * Reads battery state from Android and turns it into [BatteryObservation].
 *
 * An adapter, and only an adapter. It maps platform constants to the domain vocabulary and
 * holds no session logic whatsoever, so every lifecycle rule stays testable on the JVM.
 *
 * ## Why context-registered receivers, and what that costs
 *
 * All three broadcasts are registered on a [Context] while something is collecting, and
 * unregistered when nothing is. The consequence is stated plainly rather than glossed:
 * **a context-registered receiver observes nothing while the process does not exist.** A
 * user who unplugs their phone with BattInsight not running produces no broadcast anyone
 * hears.
 *
 * Correctness after process death therefore comes from cold-start reconciliation, not from
 * having witnessed every transition, and the engine marks such boundaries
 * [SessionTrigger.RECOVERY] so an inference is never presented as an observation.
 *
 * A manifest-declared receiver would survive process death, and `ACTION_POWER_CONNECTED` is
 * one of the few implicit broadcasts still exempt from the Android 8 background limits. It
 * is deliberately not used yet, because it would have nowhere to record what it saw: Phase
 * 5 has no durable storage, so a receiver waking the process, observing a transition and
 * exiting would lose it immediately. It becomes worth adding in Phase 6, alongside the
 * persistence that would give it a point.
 *
 * A foreground service would also solve it, and is refused. Running permanently, with a
 * permanent notification, to avoid an occasional inference is a bad trade for a diagnostics
 * tool, and it is the kind of thing that gets an application removed from a user's device.
 */
class AndroidBatterySource(
    context: Context,
    private val bootIdentitySource: BootIdentitySource,
) : BatteryObservationSource {

    private val appContext = context.applicationContext

    override suspend fun readCurrent(trigger: SessionTrigger): BatteryObservation? =
        withContext(Dispatchers.Default) {
            // ACTION_BATTERY_CHANGED is sticky, so a null receiver returns the last value
            // without registering anything. Reading it costs nothing and leaks nothing.
            val intent = try {
                ContextCompat.registerReceiver(
                    appContext,
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                null
            } ?: return@withContext null

            observationFrom(intent, trigger)
        }

    override fun observations(): Flow<BatteryObservation> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val trigger = when (action) {
                    Intent.ACTION_POWER_CONNECTED -> SessionTrigger.POWER_CONNECTED
                    Intent.ACTION_POWER_DISCONNECTED -> SessionTrigger.POWER_DISCONNECTED
                    Intent.ACTION_BATTERY_CHANGED -> SessionTrigger.BATTERY_CHANGED
                    else -> SessionTrigger.UNKNOWN
                }

                // The power broadcasts carry no battery extras, so the sticky value is read
                // for them. The engine is idempotent on state, so the BATTERY_CHANGED that
                // follows describing the same physical event does not start a second
                // session.
                val observation = if (action == Intent.ACTION_BATTERY_CHANGED) {
                    observationFrom(intent, trigger)
                } else {
                    stickyObservation(trigger)
                }
                observation?.let { trySend(it) }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        // NOT_EXPORTED: these are system broadcasts and nothing else may deliver to us.
        // Required from Android 13 and correct on every version.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }.flowOn(Dispatchers.Default)

    private fun stickyObservation(trigger: SessionTrigger): BatteryObservation? = try {
        ContextCompat.registerReceiver(
            appContext,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { observationFrom(it, trigger) }
    } catch (t: Throwable) {
        null
    }

    /**
     * Maps one `ACTION_BATTERY_CHANGED` intent to the domain.
     *
     * Both clocks are read as close together as possible, and the monotonic one first: it
     * is the one durations depend on.
     */
    private fun observationFrom(intent: Intent, trigger: SessionTrigger): BatteryObservation {
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val offsetMinutes = TimeZone.getDefault().getOffset(wall) / 60_000

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, MISSING)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, MISSING)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, MISSING)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, MISSING)

        return BatteryObservation(
            time = CaptureTime(ElapsedRealtime(elapsed), wall, offsetMinutes),
            bootIdentity = bootIdentitySource.read(),
            status = mapStatus(intent.getIntExtra(BatteryManager.EXTRA_STATUS, MISSING)),
            plug = mapPlug(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, MISSING)),
            level = level.takeIf { it != MISSING },
            scale = scale.takeIf { it != MISSING },
            present = if (intent.hasExtra(BatteryManager.EXTRA_PRESENT)) {
                intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
            } else {
                null
            },
            temperatureDeciCelsius = temperature.takeIf { it != MISSING },
            voltageMilliVolts = voltage.takeIf { it != MISSING },
            chargeCounterMicroAmpHours = readChargeCounter(),
            health = mapHealth(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, MISSING)),
            trigger = trigger,
        )
    }

    /** `BATTERY_PROPERTY_CHARGE_COUNTER`, when the device implements it. Diagnostic only. */
    private fun readChargeCounter(): Long? = try {
        val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it != Long.MIN_VALUE && it != 0L }
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val MISSING = -1

        fun mapStatus(value: Int): BatteryStatus = when (value) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NOT_CHARGING
            else -> BatteryStatus.UNKNOWN
        }

        /**
         * Maps `EXTRA_PLUGGED`.
         *
         * Zero means nothing is attached, which is a measured answer and maps to
         * [PlugSource.NONE]. The sentinel for a missing extra maps to [PlugSource.UNKNOWN],
         * which is the absence of an answer. A non-zero value this build does not recognise
         * maps to [PlugSource.OTHER] rather than being discarded -- something is plugged in,
         * and that is the part that decides the session.
         */
        fun mapPlug(value: Int): PlugSource = when (value) {
            MISSING -> PlugSource.UNKNOWN
            0 -> PlugSource.NONE
            BatteryManager.BATTERY_PLUGGED_AC -> PlugSource.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugSource.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugSource.WIRELESS
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                value == BatteryManager.BATTERY_PLUGGED_DOCK
            ) {
                PlugSource.DOCK
            } else {
                PlugSource.OTHER
            }
        }

        fun mapHealth(value: Int): BatteryHealth = when (value) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.UNSPECIFIED_FAILURE
            else -> BatteryHealth.UNKNOWN
        }
    }
}

/**
 * Reads the kernel's boot identifier.
 *
 * `/proc/sys/kernel/random/boot_id` is a per-boot UUID the kernel generates. When it is
 * readable it is the only source that can *prove* two observations share a boot, which is
 * what every monotonic comparison depends on.
 *
 * Whether an ordinary application can read it is a platform question, not an assumption:
 * `proc` is subject to SELinux and the answer varies. It is measured at runtime, and when
 * the read fails the source degrades to [BootIdentity.Derived], which can establish that
 * two observations came from *different* boots but never that they came from the same one.
 * That is a real loss of capability and the comparability layer refuses accordingly, rather
 * than proceeding on a guess.
 *
 * The value is read once and cached: it cannot change without a reboot, and a reboot ends
 * this process.
 */
class AndroidBootIdentitySource : BootIdentitySource {

    @Volatile
    private var cached: BootIdentity? = null

    override fun read(): BootIdentity = cached ?: compute().also { cached = it }

    private fun compute(): BootIdentity {
        val fromKernel = try {
            val file = File(BOOT_ID_PATH)
            if (file.canRead()) file.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (t: Throwable) {
            null
        }

        if (fromKernel != null) return BootIdentity.Kernel(fromKernel)

        // Fallback: approximately when this boot began, on the wall clock. Weak by
        // construction, and typed so nothing downstream can mistake it for proof.
        val elapsed = SystemClock.elapsedRealtime()
        return BootIdentity.Derived(System.currentTimeMillis() - elapsed)
    }

    private companion object {
        const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
    }
}

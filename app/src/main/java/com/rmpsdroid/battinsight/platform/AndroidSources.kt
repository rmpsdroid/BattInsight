package com.rmpsdroid.battinsight.platform

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Process
import com.rmpsdroid.battinsight.permissions.AppOpMode
import com.rmpsdroid.battinsight.permissions.PermissionGrant
import com.rmpsdroid.battinsight.permissions.PermissionSnapshot
import com.rmpsdroid.battinsight.permissions.PermissionStateReader
import com.rmpsdroid.battinsight.permissions.PermissionStatus
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.shizuku.ShizukuGateway
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Reads permission and app-op state from the platform. */
class AndroidPermissionStateReader(private val context: Context) : PermissionStateReader {

    override suspend fun read(): PermissionSnapshot = withContext(Dispatchers.Default) {
        val statuses = RequiredPermission.minimumSet.map { required ->
            val granted = context.checkSelfPermission(required.manifestName) ==
                PackageManager.PERMISSION_GRANTED
            PermissionStatus(
                required,
                if (granted) PermissionGrant.GRANTED else PermissionGrant.DENIED,
            )
        }
        PermissionSnapshot(statuses, readUsageAppOp())
    }

    /**
     * Reads the usage-access app-op without asserting it.
     *
     * `unsafeCheckOpNoThrow` is the non-noting variant: it must not be `noteOp`, which
     * would record an access the application is not actually making.
     */
    private fun readUsageAppOp(): AppOpMode = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        when (ops?.unsafeCheckOpNoThrow(OP_GET_USAGE_STATS, Process.myUid(), context.packageName)) {
            AppOpsManager.MODE_ALLOWED -> AppOpMode.ALLOWED
            AppOpsManager.MODE_IGNORED -> AppOpMode.IGNORED
            AppOpsManager.MODE_ERRORED -> AppOpMode.ERRORED
            AppOpsManager.MODE_DEFAULT -> AppOpMode.DEFAULT
            else -> AppOpMode.UNKNOWN
        }
    } catch (t: Throwable) {
        AppOpMode.UNKNOWN
    }

    private companion object {
        const val OP_GET_USAGE_STATS = "android:get_usage_stats"
    }
}

/** Reads `BatteryManager` properties and the sticky battery broadcast. No permission needed. */
class AndroidBatteryPropertySource(private val context: Context) : BatteryPropertySource {

    override suspend fun read(): BatteryPropertyReading = withContext(Dispatchers.Default) {
        val bm = context.getSystemService(BatteryManager::class.java)
            ?: return@withContext BatteryPropertyReading.unavailable

        val ids = mapOf(
            BatteryProperty.CHARGE_COUNTER to BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
            BatteryProperty.CURRENT_NOW to BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
            BatteryProperty.CURRENT_AVERAGE to BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
            BatteryProperty.CAPACITY to BatteryManager.BATTERY_PROPERTY_CAPACITY,
            BatteryProperty.ENERGY_COUNTER to BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER,
            BatteryProperty.STATUS to BatteryManager.BATTERY_PROPERTY_STATUS,
        )

        val props = ids.mapValues { (_, id) ->
            try {
                when (val v = bm.getLongProperty(id)) {
                    // The documented "not supported" sentinel. Phase 1B measured
                    // ENERGY_COUNTER returning exactly this on Android 16; recording it as
                    // a measurement would be inventing data.
                    Long.MIN_VALUE -> PropertySupport.Sentinel(v)
                    Int.MIN_VALUE.toLong() -> PropertySupport.Sentinel(v)
                    else -> PropertySupport.Supported(v)
                }
            } catch (t: Throwable) {
                PropertySupport.Error(t.javaClass.simpleName)
            }
        }

        val sticky = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (t: Throwable) {
            null
        }

        BatteryPropertyReading(
            properties = props,
            stickyPresent = sticky != null,
            level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 },
            scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it >= 0 },
            status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it >= 0 },
            health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.takeIf { it >= 0 },
            plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 },
            technology = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            temperatureTenthsC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it != -1 },
            voltageMilliVolts = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it != -1 },
        )
    }
}

/**
 * Queries usage statistics for a short window.
 *
 * Returns a **count only**. Usage content is sensitive and never leaves this method.
 */
class AndroidUsageAccessSource(private val context: Context) : UsageAccessSource {

    override suspend fun query(windowMillis: Long): UsageQueryOutcome = withContext(Dispatchers.Default) {
        try {
            val usm = context.getSystemService(UsageStatsManager::class.java)
                ?: return@withContext UsageQueryOutcome.Threw("NoUsageStatsManager", null)
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - windowMillis, end)
            val count = stats?.size ?: 0
            if (count > 0) UsageQueryOutcome.Rows(count) else UsageQueryOutcome.Empty
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            UsageQueryOutcome.Threw(t.javaClass.simpleName, t.message)
        }
    }
}

/**
 * Characterises UID-to-name resolution using ordinary `PackageManager` APIs.
 *
 * Probes a handful of UIDs that exist on every Android device rather than enumerating
 * anything. `QUERY_ALL_PACKAGES` is deliberately **not** declared, so partial resolution is
 * an expected and legitimate outcome; how to improve it with targeted `<queries>` is future
 * work. Returns counts only -- never package names.
 */
class AndroidPackageResolutionSource(private val context: Context) : PackageResolutionSource {

    override suspend fun probe(): PackageResolutionReading = withContext(Dispatchers.Default) {
        try {
            val pm = context.packageManager
            val candidates = intArrayOf(
                android.os.Process.SYSTEM_UID,   // 1000, always present
                Process.myUid(),                 // ourselves, always resolvable
                PHONE_UID,
                SHELL_UID,
            )
            var resolved = 0
            candidates.forEach { uid ->
                val names = try {
                    pm.getPackagesForUid(uid)
                } catch (t: Throwable) {
                    null
                }
                if (!names.isNullOrEmpty()) resolved++
            }
            PackageResolutionReading(uidsProbed = candidates.size, uidsResolved = resolved)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            PackageResolutionReading(0, 0, threw = t.javaClass.simpleName)
        }
    }

    private companion object {
        const val PHONE_UID = 1001
        const val SHELL_UID = 2000
    }
}

/**
 * Reads Shizuku lifecycle state through the official API.
 *
 * Every distinction here was measured in Phase 1B. In particular, `pm grant` of Shizuku's
 * own `API_V23` permission reported success while Shizuku still refused, because it keeps a
 * separate client authorisation list -- so installed, running and authorised are checked
 * independently.
 */
class AndroidShizukuGateway(private val context: Context) : ShizukuGateway {

    override suspend fun state(): ShizukuState = withContext(Dispatchers.Default) {
        val installed = try {
            context.packageManager.getPackageInfo(ShizukuGateway.PACKAGE, 0)
        } catch (t: PackageManager.NameNotFoundException) {
            null
        } catch (t: Throwable) {
            return@withContext ShizukuState.Error(t.javaClass.simpleName)
        }
        if (installed == null) return@withContext ShizukuState.NotInstalled

        val alive = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        if (!alive) {
            return@withContext ShizukuState.InstalledNotRunning(installed.versionName)
        }

        try {
            val version = Shizuku.getVersion()
            val uid = try {
                Shizuku.getUid()
            } catch (t: Throwable) {
                -1
            }
            if (version < ShizukuGateway.MINIMUM_PROTOCOL_VERSION) {
                return@withContext ShizukuState.VersionUnsupported(
                    version, ShizukuGateway.MINIMUM_PROTOCOL_VERSION,
                )
            }
            val authorised = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (authorised) {
                ShizukuState.RunningAuthorised(version, uid)
            } else {
                ShizukuState.RunningNotAuthorised(version, uid)
            }
        } catch (t: Throwable) {
            ShizukuState.Error(t.javaClass.simpleName)
        }
    }

    /**
     * Starts Shizuku's own consent flow.
     *
     * Not called in Phase 3: this phase observes only. Declared so Phase 4 onboarding has
     * the seam. It grants no Android permission and changes no device state.
     */
    override suspend fun requestAuthorisation() {
        try {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (t: Throwable) {
            // Reported through state() on the next refresh rather than thrown at the caller.
        }
    }

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 8801
    }
}

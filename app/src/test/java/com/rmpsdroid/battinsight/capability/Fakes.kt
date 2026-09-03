package com.rmpsdroid.battinsight.capability

import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import com.rmpsdroid.battinsight.permissions.AppOpMode
import com.rmpsdroid.battinsight.permissions.PermissionGrant
import com.rmpsdroid.battinsight.permissions.PermissionSnapshot
import com.rmpsdroid.battinsight.permissions.PermissionStateReader
import com.rmpsdroid.battinsight.permissions.PermissionStatus
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.platform.BatteryProperty
import com.rmpsdroid.battinsight.platform.BatteryPropertyReading
import com.rmpsdroid.battinsight.platform.BatteryPropertySource
import com.rmpsdroid.battinsight.platform.PackageResolutionReading
import com.rmpsdroid.battinsight.platform.PackageResolutionSource
import com.rmpsdroid.battinsight.platform.PropertySupport
import com.rmpsdroid.battinsight.platform.UsageAccessSource
import com.rmpsdroid.battinsight.platform.UsageQueryOutcome
import com.rmpsdroid.battinsight.shizuku.ShizukuGateway
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import kotlinx.coroutines.delay

/**
 * Fakes for every platform seam.
 *
 * The point of the interfaces in the production code is exactly this: the whole capability
 * evaluation runs on the JVM, so every combination below is testable without a device,
 * without Shizuku installed, and without any permission granted.
 */

/** A runner whose response to each command is scripted. */
class FakeProcessRunner(
    private var ready: Boolean = true,
    private val responses: MutableMap<String, ExecutionOutput> = mutableMapOf(),
    /** Artificial latency, used to test that a stale refresh cannot win. */
    var delayMillis: Long = 0,
) : ProcessRunner {

    var invocations: MutableList<String> = mutableListOf()

    override suspend fun isReady(): Boolean = ready

    override suspend fun run(command: ProbeCommand, timeoutMillis: Long): ExecutionOutput {
        invocations.add(command.id)
        if (delayMillis > 0) delay(delayMillis)
        return responses[command.id] ?: ExecutionOutput(
            command, 0, ByteArray(0), ByteArray(0), 1,
        )
    }

    fun on(command: ProbeCommand, exitCode: Int?, stdout: ByteArray, stderr: ByteArray = ByteArray(0)) =
        apply { responses[command.id] = ExecutionOutput(command, exitCode, stdout, stderr, 1) }

    fun on(command: ProbeCommand, exitCode: Int?, stdout: String, stderr: String = "") =
        on(command, exitCode, stdout.toByteArray(), stderr.toByteArray())

    fun notReady() = apply { ready = false }

    /** Identity responses matching what Phase 1B actually measured. */
    fun withShellIdentity() = apply {
        on(ProbeCommand.Identity, 0, "uid=2000(shell) gid=2000(shell) context=u:r:shell:s0")
        on(ProbeCommand.SelinuxIdentity, 0, "u:r:shell:s0")
    }

    fun withAppIdentity(uid: Int = 10241) = apply {
        on(ProbeCommand.Identity, 0, "uid=$uid(u0_a241) gid=$uid context=u:r:untrusted_app:s0:c241")
        on(ProbeCommand.SelinuxIdentity, 0, "u:r:untrusted_app:s0:c241")
    }
}

class FakePermissionReader(private var snapshot: PermissionSnapshot) : PermissionStateReader {
    override suspend fun read(): PermissionSnapshot = snapshot

    companion object {
        fun of(
            granted: Set<RequiredPermission>,
            appOp: AppOpMode = AppOpMode.DEFAULT,
        ) = FakePermissionReader(
            PermissionSnapshot(
                statuses = RequiredPermission.minimumSet.map {
                    PermissionStatus(
                        it,
                        if (it in granted) PermissionGrant.GRANTED else PermissionGrant.DENIED,
                    )
                },
                usageStatsAppOp = appOp,
            ),
        )

        fun none() = of(emptySet())
        fun all(appOp: AppOpMode = AppOpMode.DEFAULT) = of(RequiredPermission.entries.toSet(), appOp)
    }
}

class FakeShizukuGateway(private var state: ShizukuState) : ShizukuGateway {
    var authorisationRequested = false
    override suspend fun state(): ShizukuState = state
    override suspend fun requestAuthorisation() { authorisationRequested = true }
}

class FakeBatterySource(private val reading: BatteryPropertyReading) : BatteryPropertySource {
    override suspend fun read(): BatteryPropertyReading = reading

    companion object {
        /** Matches the Android 16 measurement: five supported, ENERGY_COUNTER a sentinel. */
        fun measuredAndroid16() = FakeBatterySource(
            BatteryPropertyReading(
                properties = mapOf(
                    BatteryProperty.CHARGE_COUNTER to PropertySupport.Supported(10_000),
                    BatteryProperty.CURRENT_NOW to PropertySupport.Supported(900_000),
                    BatteryProperty.CURRENT_AVERAGE to PropertySupport.Supported(900_000),
                    BatteryProperty.CAPACITY to PropertySupport.Supported(100),
                    BatteryProperty.STATUS to PropertySupport.Supported(4),
                    BatteryProperty.ENERGY_COUNTER to PropertySupport.Sentinel(Long.MIN_VALUE),
                ),
                stickyPresent = true, level = 100, scale = 100,
            ),
        )

        fun allSupported() = FakeBatterySource(
            BatteryPropertyReading(
                properties = BatteryProperty.entries.associateWith { PropertySupport.Supported(1) },
                stickyPresent = true,
            ),
        )

        fun unavailable() = FakeBatterySource(BatteryPropertyReading.unavailable)
    }
}

class FakeUsageSource(private val outcome: UsageQueryOutcome) : UsageAccessSource {
    override suspend fun query(windowMillis: Long): UsageQueryOutcome = outcome
}

class FakePackageSource(private val reading: PackageResolutionReading) : PackageResolutionSource {
    override suspend fun probe(): PackageResolutionReading = reading
}

// --------------------------------------------------------------------------- payloads

/** Builds bytes shaped like real `dumpsys batterystats --proto` output. */
fun fakeProtoPayload(bodyBytes: Int = 4096): ByteArray {
    val body = ByteArray(bodyBytes) { (it % 251).toByte() }
    val out = java.io.ByteArrayOutputStream()
    out.write(0x0A) // field 1, wire type 2
    var len = body.size
    while (true) {
        val b = len and 0x7F
        len = len ushr 7
        if (len != 0) out.write(b or 0x80) else { out.write(b); break }
    }
    out.write(body)
    return out.toByteArray()
}

/** Checkin text with kernel wakelock records, mirroring the measured field layout. */
fun fakeCheckinWithKwl(total: Int, withValues: Int): String = buildString {
    appendLine("9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1")
    repeat(total) { i ->
        val active = i < withValues
        val time = if (active) 681038 else 0
        val count = if (active) 678 else 0
        appendLine("""9,0,l,kwl,"wakelock_$i",$time,$count,-1,-1""")
    }
}

/** Verbatim denial strings measured on Android 16 in Phase 1B. */
object MeasuredDenials {
    const val DUMP =
        "Permission Denial: can't dump BatteryStatsService from from pid=22446, uid=10241 " +
            "due to missing android.permission.DUMP permission"
    const val PACKAGE_USAGE_STATS =
        "Permission Denial: can't dump BatteryStatsService from from pid=22548, uid=10241 " +
            "due to missing android.permission.PACKAGE_USAGE_STATS permission"
    const val MATCH_ANY_USER =
        "Security exception: MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS permission: " +
            "UID 10241 requires android.permission.INTERACT_ACROSS_USERS_FULL or " +
            "android.permission.INTERACT_ACROSS_USERS to access user 0."
}

package com.rmpsdroid.battinsight.capability

import com.rmpsdroid.battinsight.collection.BackendAvailability
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.collection.BackendStatus
import com.rmpsdroid.battinsight.collection.CollectionOutcome
import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.permissions.PermissionGrant
import com.rmpsdroid.battinsight.permissions.PermissionSnapshot
import com.rmpsdroid.battinsight.permissions.PermissionStateReader
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.platform.BatteryPropertySource
import com.rmpsdroid.battinsight.platform.PackageResolutionSource
import com.rmpsdroid.battinsight.platform.PropertySupport
import com.rmpsdroid.battinsight.platform.UsageAccessSource
import com.rmpsdroid.battinsight.platform.UsageQueryOutcome
import com.rmpsdroid.battinsight.shizuku.ShizukuGateway
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Evaluates what BattInsight can currently do, and is the only component that talks to the
 * platform to find out.
 *
 * The UI observes [report] and calls [refresh]. It must not query `PackageManager`,
 * Shizuku, `BatteryManager`, `UsageStatsManager` or run commands itself -- keeping that in
 * one place is what makes the whole capability picture consistent and testable.
 *
 * Every dependency is an interface, so the entire evaluation runs on the JVM against fakes.
 *
 * ## What this class will never do
 *
 * It observes; it does not change anything. No `pm grant`, no app-op change, no setting.
 * Phase 4 owns setup. The commands it may run are limited to the [ProbeCommand] whitelist,
 * all read-only.
 */
class CapabilityCoordinator(
    private val grantedAppRunner: ProcessRunner,
    private val shizukuRunner: ProcessRunner,
    private val shizukuGateway: ShizukuGateway,
    private val permissionReader: PermissionStateReader,
    private val batterySource: BatteryPropertySource,
    private val usageSource: UsageAccessSource,
    private val packageSource: PackageResolutionSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _report = MutableStateFlow(CapabilityReport.unknown())
    val report: StateFlow<CapabilityReport> = _report.asStateFlow()

    private var inFlight: Job? = null

    /**
     * Re-evaluate everything.
     *
     * A refresh already running is cancelled first, so a stale evaluation can never
     * overwrite a newer one. There is no polling: capability changes only when the user
     * changes something, so refresh is explicit.
     */
    fun refresh() {
        inFlight?.cancel()
        inFlight = scope.launch {
            _report.value = _report.value.copy(refreshing = true)
            val fresh = evaluate()
            _report.value = fresh
        }
    }

    /** Evaluates without touching the flow. Directly testable. */
    suspend fun evaluate(): CapabilityReport {
        val permissions = permissionReader.read()
        val shizuku = shizukuGateway.state()

        val shizukuStatus = shizukuBackendStatus(shizuku)
        val grantedStatus = grantedAppBackendStatus(permissions)
        val backends = listOf(
            BackendStatus(BackendKind.GRANTED_APP, grantedStatus),
            BackendStatus(BackendKind.SHIZUKU_ADB, shizukuStatus),
            BackendStatus(
                BackendKind.SHIZUKU_ROOT,
                BackendAvailability.NotImplemented("Not implemented; no root environment has been measured"),
            ),
            BackendStatus(
                BackendKind.DIRECT_ROOT,
                BackendAvailability.NotImplemented("Not implemented; no root environment has been measured"),
            ),
        )

        // Prefer Shizuku when usable: measured faster and with better name resolution.
        val active: Pair<BackendKind, ProcessRunner>? = when {
            shizukuStatus is BackendAvailability.Ready -> BackendKind.SHIZUKU_ADB to shizukuRunner
            grantedStatus is BackendAvailability.Ready -> BackendKind.GRANTED_APP to grantedAppRunner
            else -> null
        }

        val findings = buildList {
            addAll(batteryStatsFindings(active, permissions))
            add(usageStatsFinding(permissions))
            add(batteryPropertiesFinding())
            add(uidResolutionFinding())
            // Every capability carries a finding, including the ones no probe reaches yet.
            // CapabilityReport.unknown() already promises exactly this before anything is
            // checked, and a refresh must not make capabilities disappear from the report:
            // a capability that vanishes reads to the UI as one that does not exist, which
            // is a claim we have not earned. Unprobed is Unknown, and says so.
            addAll(unprobedFindings(map { it.capability }.toSet()))
        }

        return CapabilityReport(
            timestampMillis = clock(),
            backends = backends,
            permissions = permissions,
            shizuku = shizuku,
            findings = findings,
            refreshing = false,
        )
    }

    /**
     * Fills in the capabilities Phase 3 has no probe for.
     *
     * These are not absent and not broken -- nobody has looked. Reporting them as
     * [CapabilityState.Unknown] with a reason keeps the report complete and keeps the gap
     * visible, rather than letting a missing row imply a missing capability.
     */
    private fun unprobedFindings(alreadyCovered: Set<Capability>): List<CapabilityFinding> =
        Capability.entries.filterNot { it in alreadyCovered }.map {
            CapabilityFinding(
                capability = it,
                state = CapabilityState.Unknown,
                reason = "No probe implemented yet",
            )
        }

    // ------------------------------------------------------------------ backend status

    private suspend fun grantedAppBackendStatus(permissions: PermissionSnapshot): BackendAvailability {
        val missing = permissions.missing
        if (missing.isNotEmpty()) {
            // Name the first one the platform will demand, so onboarding can act on it.
            return BackendAvailability.NotReady("Missing ${missing.first().manifestName}")
        }
        if (!grantedAppRunner.isReady()) {
            return BackendAvailability.NotReady("Cannot start a process")
        }
        return measureIdentity(grantedAppRunner, BackendIdentity.Kind.APP_UID)
    }

    private suspend fun shizukuBackendStatus(state: ShizukuState): BackendAvailability = when (state) {
        is ShizukuState.RunningAuthorised -> {
            if (shizukuRunner.isReady()) {
                measureIdentity(shizukuRunner, BackendIdentity.Kind.SHELL)
            } else {
                BackendAvailability.NotReady("Shizuku authorised but the binder is not usable")
            }
        }
        is ShizukuState.Error -> BackendAvailability.Failed(state.detail)
        ShizukuState.Unknown -> BackendAvailability.Unknown
        else -> BackendAvailability.NotReady(state.nextStep)
    }

    /**
     * Establishes what a backend actually runs as, by asking it.
     *
     * The expected answers (`uid=2000(shell)` for Shizuku, `untrusted_app` for the app) are
     * measured, not hard-coded. An unexpected identity is recorded and evaluation
     * continues behaviourally rather than rejecting the backend.
     */
    private suspend fun measureIdentity(
        runner: ProcessRunner,
        expectedKind: BackendIdentity.Kind,
    ): BackendAvailability = try {
        val id = runner.run(ProbeCommand.Identity)
        val ctx = runner.run(ProbeCommand.SelinuxIdentity)
        if (id.exitCode != 0) {
            BackendAvailability.Failed("identity probe exited ${id.exitCode}")
        } else {
            val uid = parseUid(id.stdoutHead())
            val context = ctx.stdoutHead().trim().ifEmpty { parseContext(id.stdoutHead()) }
            BackendAvailability.Ready(
                BackendIdentity(
                    uid = uid ?: -1,
                    selinuxContext = context,
                    kind = if (context.contains(":shell:")) BackendIdentity.Kind.SHELL else expectedKind,
                ),
            )
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        BackendAvailability.Failed(t.javaClass.simpleName)
    }

    internal fun parseUid(idOutput: String): Int? =
        Regex("uid=(\\d+)").find(idOutput)?.groupValues?.get(1)?.toIntOrNull()

    internal fun parseContext(idOutput: String): String =
        Regex("context=(\\S+)").find(idOutput)?.groupValues?.get(1) ?: ""

    // ------------------------------------------------------------- batterystats findings

    private suspend fun batteryStatsFindings(
        active: Pair<BackendKind, ProcessRunner>?,
        permissions: PermissionSnapshot,
    ): List<CapabilityFinding> {
        if (active == null) {
            val reason = if (permissions.missing.isNotEmpty()) {
                "Missing ${permissions.missing.first().manifestName}"
            } else {
                "No usable backend"
            }
            val state = if (permissions.missing.isNotEmpty()) {
                CapabilityState.PermissionMissing(permissions.missing.first().manifestName)
            } else {
                CapabilityState.Unknown
            }
            return listOf(
                CapabilityFinding(Capability.BATTERY_STATS_AGGREGATE, state, reason),
                CapabilityFinding(Capability.KERNEL_WAKELOCKS, state, reason),
            )
        }

        val (kind, runner) = active
        val identityKind =
            if (kind == BackendKind.SHIZUKU_ADB) BackendIdentity.Kind.SHELL else BackendIdentity.Kind.APP_UID

        val protoState = try {
            val out = runner.run(ProbeCommand.BatteryStatsProto)
            val result = BatteryStatsProbe.toCollectionResult(out, identityKind, SourceFormat.PROTO, clock())
            BatteryStatsProbe.evaluateProtoAcquisition(result, out.stdout, out.truncated)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            CapabilityState.ExecutionFailed(t.javaClass.simpleName)
        }

        val aggregate = CapabilityFinding(
            capability = Capability.BATTERY_STATS_AGGREGATE,
            state = protoState,
            reason = describe(protoState, "Protobuf acquisition"),
            viaBackend = kind,
        )

        // Kernel wakelocks need the checkin text, because `kwl` is greppable there and
        // reading it from protobuf would require the production decoder Phase 3 defers.
        val kwl = if (protoState !is CapabilityState.Available) {
            CapabilityFinding(
                Capability.KERNEL_WAKELOCKS,
                protoState,
                "Depends on battery statistics acquisition",
                kind,
            )
        } else {
            kernelWakelockFinding(runner, identityKind, kind)
        }

        return listOf(aggregate, kwl)
    }

    private suspend fun kernelWakelockFinding(
        runner: ProcessRunner,
        identityKind: BackendIdentity.Kind,
        kind: BackendKind,
    ): CapabilityFinding = try {
        val out = runner.run(ProbeCommand.BatteryStatsCheckinCurrent, CHECKIN_TIMEOUT_MS)
        val result = BatteryStatsProbe.toCollectionResult(out, identityKind, SourceFormat.CHECKIN, clock())
        val outcome = result.outcome()
        if (outcome !is CollectionOutcome.Data) {
            val s = CapabilityInterpreter.interpret(outcome)
            CapabilityFinding(Capability.KERNEL_WAKELOCKS, s, describe(s, "Checkin acquisition"), kind)
        } else {
            // Scan the whole capture, then discard it. A prefix will not do: the kwl block
            // sits at 84-88% of a real payload, so scanning the front finds nothing and
            // says nothing true. Memory stays bounded by the collection ceiling, and only
            // one decoded line exists at a time.
            //
            // If the capture itself was cut short, a negative reading is inconclusive
            // rather than evidence that the device has no kernel wakelocks.
            val reading = BatteryStatsProbe.scanKernelWakelocks(out.stdout, out.truncated)
            val state = CapabilityInterpreter.interpret(outcome, reading)
            CapabilityFinding(Capability.KERNEL_WAKELOCKS, state, describe(state, "Kernel wakelocks"), kind)
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        CapabilityFinding(
            Capability.KERNEL_WAKELOCKS,
            CapabilityState.ExecutionFailed(t.javaClass.simpleName),
            "Probe failed: ${t.javaClass.simpleName}",
            kind,
        )
    }

    // ------------------------------------------------------------------- other findings

    /**
     * Usage access, judged from permission, app-op and query result together.
     *
     * Zero rows alone proves nothing: Phase 1B measured `queryUsageStats` returning an
     * empty list without access *and* legitimately returning nothing for a quiet window.
     */
    private suspend fun usageStatsFinding(permissions: PermissionSnapshot): CapabilityFinding {
        val expected = permissions.usageAccessExpected
        val outcome = usageSource.query()
        val (state, reason) = when (outcome) {
            is UsageQueryOutcome.Rows ->
                CapabilityState.Available to "${outcome.count} records in the last 24 hours"
            UsageQueryOutcome.Empty -> if (expected) {
                CapabilityState.AvailableNoEvents("access granted, no records in window") to
                    "Access granted, no records in the last 24 hours"
            } else {
                CapabilityState.PermissionMissing(RequiredPermission.PACKAGE_USAGE_STATS.manifestName) to
                    "No usage access: query returned nothing and the permission is not granted"
            }
            is UsageQueryOutcome.Threw ->
                CapabilityState.ExecutionFailed(outcome.exception) to "Query failed: ${outcome.exception}"
            UsageQueryOutcome.NotAttempted ->
                CapabilityState.Unknown to "Not checked"
        }
        return CapabilityFinding(Capability.USAGE_STATS, state, reason)
    }

    /** Battery properties need no permission at all, so this is the reliable baseline. */
    private suspend fun batteryPropertiesFinding(): CapabilityFinding {
        val reading = batterySource.read()
        val supported = reading.supportedCount
        val probed = reading.probedCount
        return when {
            probed == 0 ->
                CapabilityFinding(
                    Capability.BATTERY_PROPERTIES,
                    CapabilityState.SourceUnavailable("BatteryManager unavailable"),
                    "BatteryManager could not be read",
                )
            supported == 0 ->
                CapabilityFinding(
                    Capability.BATTERY_PROPERTIES,
                    CapabilityState.AvailableNoEvents("no property returned a real value"),
                    "Readable, but no property returned a measurement",
                )
            supported < probed ->
                CapabilityFinding(
                    Capability.BATTERY_PROPERTIES,
                    CapabilityState.AvailableDegraded("$supported of $probed properties supported"),
                    "$supported of $probed properties supported" +
                        if (reading.stickyPresent) "; battery broadcast available" else "",
                )
            else ->
                CapabilityFinding(
                    Capability.BATTERY_PROPERTIES,
                    CapabilityState.Available,
                    "All $probed properties supported",
                )
        }
    }

    /** Characterises name resolution. Solving package visibility is future work. */
    private suspend fun uidResolutionFinding(): CapabilityFinding {
        val r = packageSource.probe()
        return when {
            r.threw != null -> CapabilityFinding(
                Capability.UID_NAME_RESOLUTION,
                CapabilityState.ExecutionFailed(r.threw!!),
                "Resolution failed: ${r.threw}",
            )
            r.uidsProbed == 0 -> CapabilityFinding(
                Capability.UID_NAME_RESOLUTION, CapabilityState.Unknown, "Not checked",
            )
            r.noneResolved -> CapabilityFinding(
                Capability.UID_NAME_RESOLUTION,
                CapabilityState.SourceUnavailable("no UID could be resolved"),
                "No UID could be resolved to a package name",
            )
            r.allResolved -> CapabilityFinding(
                Capability.UID_NAME_RESOLUTION,
                CapabilityState.Available,
                "Resolved ${r.uidsResolved} of ${r.uidsProbed} probed UIDs",
            )
            else -> CapabilityFinding(
                Capability.UID_NAME_RESOLUTION,
                CapabilityState.AvailableDegraded("${r.uidsResolved} of ${r.uidsProbed} UIDs resolved"),
                "Partial: ${r.uidsResolved} of ${r.uidsProbed} UIDs resolved (package visibility)",
            )
        }
    }

    private fun describe(state: CapabilityState, subject: String): String = when (state) {
        CapabilityState.Available -> "$subject working"
        is CapabilityState.AvailableNoEvents -> "Available: ${state.detail}"
        is CapabilityState.AvailableDegraded -> "Degraded: ${state.reason}"
        is CapabilityState.PermissionMissing -> "Missing ${state.permission}"
        is CapabilityState.NotSupported -> "Not supported: ${state.reason}"
        is CapabilityState.SourceUnavailable -> "Source unavailable: ${state.source}"
        is CapabilityState.ExecutionFailed -> "Failed: ${state.detail}"
        CapabilityState.Unknown -> "Not determined"
    }

    private companion object {
        const val CHECKIN_TIMEOUT_MS = 30_000L
        /** Bounded scan: enough to find kwl records without materialising ~800 KB. */
    }
}

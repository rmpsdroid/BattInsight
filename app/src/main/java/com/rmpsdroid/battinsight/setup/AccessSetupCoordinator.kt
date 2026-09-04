package com.rmpsdroid.battinsight.setup

import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.access.AccessPreferenceStore
import com.rmpsdroid.battinsight.capability.BatteryStatsProbe
import com.rmpsdroid.battinsight.capability.CapabilityState
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.permissions.PermissionGrant
import com.rmpsdroid.battinsight.permissions.PermissionStateReader
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.shizuku.ShizukuGateway
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives onboarding and access setup.
 *
 * Owns two things: the derivation of [SetupState] from current reality, and the small
 * number of actions that change device state. Everything it reports is re-derived from a
 * fresh reading; nothing about readiness is remembered.
 *
 * ## What it will and will not do without being asked
 *
 * It never requests Shizuku authorisation on its own — [requestShizukuAuthorisation] runs
 * only from an explicit button press. It never grants a permission without the user having
 * passed through the confirmation state. It changes no app-op, no setting, and nothing
 * belonging to any other application.
 *
 * ## Why a grant is not trusted to have worked
 *
 * `pm grant` reporting a clean exit is not proof. Phase 1B measured a grant of Shizuku's
 * own permission returning success while Shizuku still refused, because the thing that
 * mattered was kept elsewhere. So every step re-reads the permission afterwards, and the
 * whole sequence is followed by a behavioural acquisition probe: flags are evidence,
 * acquisition is proof.
 */
class AccessSetupCoordinator(
    private val preferences: AccessPreferenceStore,
    private val permissionReader: PermissionStateReader,
    private val shizukuGateway: ShizukuGateway,
    private val setupExecutor: SetupExecutor,
    /** BattInsight's own process, used for the behavioural check of the granted-app route. */
    private val grantedAppRunner: ProcessRunner,
    /** The Shizuku user service, used to verify the live route actually executes. */
    private val shizukuRunner: ProcessRunner,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<SetupState>(SetupState.Welcome)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private var inFlight: Job? = null

    // --------------------------------------------------------------------------- refresh

    /**
     * Re-derive the setup state from current reality.
     *
     * A refresh already running is cancelled first, so a slow evaluation can never overwrite
     * a newer one. A refresh arriving while a grant sequence or verification is running is
     * ignored: those states are the result of a user action in progress, and replacing them
     * mid-flight would make the screen jump backwards.
     */
    fun refresh() {
        if (_state.value.isTransient) return
        inFlight?.cancel()
        inFlight = scope.launch {
            _state.value = evaluate()
        }
    }

    /** Derives the state without touching the flow. Directly testable. */
    suspend fun evaluate(): SetupState {
        val mode = preferences.current()
        val shizuku = shizukuGateway.state()
        val permissions = permissionReader.read()

        return when (mode) {
            AccessMode.NOT_CHOSEN -> SetupState.Welcome

            AccessMode.LIMITED -> SetupState.Limited(
                reason = "You chose to continue without privileged access",
                mode = mode,
            )

            AccessMode.GRANTED_APP -> when {
                permissions.allRequiredGranted -> verifyGrantedApp(mode)
                else -> SetupState.Limited(
                    reason = "Independent access needs " +
                        permissions.missing.joinToString(", ") { it.shortName } +
                        ". Set it up again to restore it.",
                    mode = mode,
                )
            }

            AccessMode.SHIZUKU_LIVE -> when (shizuku) {
                is ShizukuState.RunningAuthorised -> verifyShizukuLive(mode, shizuku)
                ShizukuState.NotInstalled -> SetupState.ShizukuNotInstalled
                is ShizukuState.InstalledNotRunning -> SetupState.ShizukuStopped(shizuku.versionName)
                is ShizukuState.RunningNotAuthorised ->
                    SetupState.ShizukuUnauthorised(shizuku.serverVersion)
                is ShizukuState.VersionUnsupported ->
                    SetupState.ShizukuUnsupported(shizuku.serverVersion, shizuku.minimumSupported)
                is ShizukuState.Error -> SetupState.Error(shizuku.detail)
                ShizukuState.Unknown -> SetupState.Error("Shizuku state not checked")
            }
        }
    }

    /** Derives the Shizuku setup state alone, for the screens that walk that route. */
    suspend fun evaluateShizukuRoute(): SetupState = when (val s = shizukuGateway.state()) {
        ShizukuState.NotInstalled -> SetupState.ShizukuNotInstalled
        is ShizukuState.InstalledNotRunning -> SetupState.ShizukuStopped(s.versionName)
        is ShizukuState.RunningNotAuthorised -> SetupState.ShizukuUnauthorised(s.serverVersion)
        is ShizukuState.RunningAuthorised ->
            SetupState.ShizukuReady(s.serverVersion, verified = probeShizukuWorks())
        is ShizukuState.VersionUnsupported ->
            SetupState.ShizukuUnsupported(s.serverVersion, s.minimumSupported)
        is ShizukuState.Error -> SetupState.Error(s.detail)
        ShizukuState.Unknown -> SetupState.Error("Shizuku state not checked")
    }

    // ------------------------------------------------------------------- user decisions

    /** Records a choice and re-derives. Never itself changes device state. */
    fun choose(mode: AccessMode) {
        inFlight?.cancel()
        inFlight = scope.launch {
            preferences.setAccessMode(mode)
            _state.value = evaluate()
        }
    }

    /** Moves to the Shizuku route without committing the user to it. */
    fun openShizukuRoute() {
        inFlight?.cancel()
        inFlight = scope.launch { _state.value = evaluateShizukuRoute() }
    }

    fun openManualAdb() {
        inFlight?.cancel()
        _state.value = SetupState.ManualAdb
    }

    fun openAccessChoice() {
        inFlight?.cancel()
        _state.value = SetupState.ChoosingAccess
    }

    /** Shows what would be granted. Nothing is changed by entering this state. */
    fun openGrantConfirmation() {
        inFlight?.cancel()
        _state.value = SetupState.GrantConfirmation
    }

    /**
     * Asks Shizuku to authorise BattInsight.
     *
     * Only ever called from an explicit user action. Shizuku shows its own consent UI; this
     * grants no Android permission and changes nothing if the user declines.
     */
    fun requestShizukuAuthorisation() {
        inFlight?.cancel()
        inFlight = scope.launch {
            shizukuGateway.requestAuthorisation()
            _state.value = evaluateShizukuRoute()
        }
    }

    // ---------------------------------------------------------------------- grant route

    /**
     * Grants the three permissions through Shizuku, one at a time, verifying each.
     *
     * Requires the user to have reached [SetupState.GrantConfirmation] first: this cannot be
     * triggered by arriving on a screen. The sequence stops at the first failure, and what
     * already changed is reported rather than rolled back silently — the user's device is in
     * that state and should be told so.
     */
    fun grantIndependentAccess() {
        if (_state.value !is SetupState.GrantConfirmation) return
        inFlight?.cancel()
        inFlight = scope.launch { _state.value = runGrantSequence() }
    }

    /** The grant sequence, without the flow. Directly testable. */
    suspend fun runGrantSequence(): SetupState {
        val completed = mutableListOf<GrantStep>()

        for (permission in RequiredPermission.minimumSet) {
            _state.value = SetupState.GrantInProgress(permission, completed.toList())

            val before = permissionReader.read().grantOf(permission)
            if (before == PermissionGrant.GRANTED) {
                completed += GrantStep(
                    permission = permission,
                    before = before,
                    after = before,
                    outcome = null,
                    verdict = GrantStep.Verdict.ALREADY_HELD,
                    detail = "Already granted; nothing was changed",
                )
                continue
            }

            val outcome = setupExecutor.execute(SetupAction.grantFor(permission))
            val after = permissionReader.read().grantOf(permission)

            // The permission read is the authority, not the command's own account of itself.
            if (after == PermissionGrant.GRANTED) {
                completed += GrantStep(
                    permission, before, after, outcome,
                    GrantStep.Verdict.CHANGED, "Granted and verified",
                )
                continue
            }

            val step = GrantStep(
                permission = permission,
                before = before,
                after = after,
                outcome = outcome,
                verdict = GrantStep.Verdict.FAILED,
                detail = if (outcome.exitedCleanly) {
                    "The command reported success but the permission is still not held"
                } else {
                    outcome.detail
                },
            )
            return SetupState.GrantFailed(step, completed.toList())
        }

        return verifyAfterGrant(completed)
    }

    // ------------------------------------------------------------------- verification

    /**
     * Proves the granted-app route works, rather than assuming it from three flags.
     *
     * Runs a real acquisition through BattInsight's own process, then re-reads the
     * permissions to confirm they are still held. An inconsistency here is reported, never
     * smoothed over: permission flags saying yes while acquisition fails is precisely the
     * situation the predecessor applications hid behind "check your permissions".
     */
    fun verifySetup() {
        inFlight?.cancel()
        inFlight = scope.launch {
            _state.value = SetupState.Verifying
            _state.value = verifyAfterGrant(emptyList())
        }
    }

    private suspend fun verifyAfterGrant(completed: List<GrantStep>): SetupState {
        _state.value = SetupState.Verifying

        val permissions = permissionReader.read()
        if (!permissions.allRequiredGranted) {
            return SetupState.VerificationFailed(
                detail = "Still missing " + permissions.missing.joinToString(", ") { it.shortName },
                mode = AccessMode.GRANTED_APP,
            )
        }

        val acquisition = probeGrantedAppAcquisition()
        if (acquisition !is CapabilityState.Available) {
            return SetupState.VerificationFailed(
                detail = "All three permissions are granted, but reading battery statistics " +
                    "still failed: " + describe(acquisition),
                mode = AccessMode.GRANTED_APP,
            )
        }

        // Re-read afterwards: a grant that did not stick is worth catching here rather than
        // on the next launch.
        val after = permissionReader.read()
        if (!after.allRequiredGranted) {
            return SetupState.VerificationFailed(
                detail = "Permissions were lost during verification: " +
                    after.missing.joinToString(", ") { it.shortName },
                mode = AccessMode.GRANTED_APP,
            )
        }

        preferences.setAccessMode(AccessMode.GRANTED_APP)
        val changed = completed.count { it.verdict == GrantStep.Verdict.CHANGED }
        return SetupState.Ready(
            mode = AccessMode.GRANTED_APP,
            backend = BackendKind.GRANTED_APP,
            detail = if (changed > 0) {
                "Independent access is ready. $changed permission" +
                    (if (changed == 1) "" else "s") + " granted and verified."
            } else {
                "Independent access is ready and was verified by reading battery statistics."
            },
        )
    }

    private suspend fun verifyGrantedApp(mode: AccessMode): SetupState {
        val acquisition = probeGrantedAppAcquisition()
        return if (acquisition is CapabilityState.Available) {
            SetupState.Ready(
                mode = mode,
                backend = BackendKind.GRANTED_APP,
                detail = "Reading battery statistics directly, without Shizuku",
            )
        } else {
            SetupState.VerificationFailed(
                detail = "The three permissions are granted, but reading battery statistics " +
                    "failed: " + describe(acquisition),
                mode = mode,
            )
        }
    }

    private suspend fun verifyShizukuLive(
        mode: AccessMode,
        shizuku: ShizukuState.RunningAuthorised,
    ): SetupState {
        val acquisition = probeAcquisition(shizukuRunner, BackendIdentity.Kind.SHELL)
        return if (acquisition is CapabilityState.Available) {
            SetupState.Ready(
                mode = mode,
                backend = BackendKind.SHIZUKU_ADB,
                detail = "Reading battery statistics through Shizuku. BattInsight itself " +
                    "holds none of the three elevated permissions.",
            )
        } else {
            SetupState.VerificationFailed(
                detail = "Shizuku is authorised but reading battery statistics failed: " +
                    describe(acquisition),
                mode = mode,
            )
        }
    }

    private suspend fun probeShizukuWorks(): Boolean = try {
        shizukuRunner.run(ProbeCommand.Identity).exitCode == 0
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        false
    }

    private suspend fun probeGrantedAppAcquisition(): CapabilityState =
        probeAcquisition(grantedAppRunner, BackendIdentity.Kind.APP_UID)

    private suspend fun probeAcquisition(
        runner: ProcessRunner,
        kind: BackendIdentity.Kind,
    ): CapabilityState = try {
        val out = runner.run(ProbeCommand.BatteryStatsProto)
        val result = BatteryStatsProbe.toCollectionResult(out, kind, SourceFormat.PROTO, clock())
        BatteryStatsProbe.evaluateProtoAcquisition(result, out.stdout, out.truncated)
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        CapabilityState.ExecutionFailed(t.javaClass.simpleName)
    }

    // -------------------------------------------------------------------- access removal

    /**
     * Removes BattInsight's own elevated permissions through Shizuku.
     *
     * Only BattInsight's three permissions, only through the typed revoke actions, and only
     * after the caller has shown a confirmation. No app-op is touched, and no other package
     * can be named — [SetupAction] fixes the target at compile time.
     */
    suspend fun revokeIndependentAccess(): List<GrantStep> {
        val steps = mutableListOf<GrantStep>()

        for (action in SetupAction.revokes) {
            val permission = action.permission
            val before = permissionReader.read().grantOf(permission)
            if (before != PermissionGrant.GRANTED) {
                steps += GrantStep(
                    permission, before, before, null,
                    GrantStep.Verdict.ALREADY_HELD, "Was not granted; nothing was changed",
                )
                continue
            }

            val outcome = setupExecutor.execute(action)
            val after = permissionReader.read().grantOf(permission)
            steps += if (after != PermissionGrant.GRANTED) {
                GrantStep(permission, before, after, outcome, GrantStep.Verdict.REMOVED, "Removed")
            } else {
                GrantStep(
                    permission, before, after, outcome, GrantStep.Verdict.FAILED,
                    if (outcome.exitedCleanly) {
                        "The command reported success but the permission is still held"
                    } else {
                        outcome.detail
                    },
                )
            }
        }
        return steps
    }

    private fun describe(state: CapabilityState): String = when (state) {
        is CapabilityState.PermissionMissing -> "missing ${state.permission}"
        is CapabilityState.ExecutionFailed -> state.detail
        is CapabilityState.SourceUnavailable -> state.source
        is CapabilityState.AvailableNoEvents -> state.detail
        is CapabilityState.AvailableDegraded -> state.reason
        CapabilityState.Unknown -> "the result could not be interpreted"
        CapabilityState.Available -> "available"
        is CapabilityState.NotSupported -> state.reason
    }
}

/** Short display name, for messages that list several permissions. */
internal val RequiredPermission.shortName: String
    get() = manifestName.substringAfterLast('.')

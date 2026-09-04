package com.rmpsdroid.battinsight.setup

import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.permissions.RequiredPermission

/**
 * Where the user is in setting up access.
 *
 * One value, not a pile of booleans. The combinations that matter are genuinely exclusive —
 * Shizuku cannot be both absent and unauthorised, a grant cannot be both in progress and
 * failed — and expressing them as independent flags would allow states that cannot happen
 * and force every screen to re-derive the same conclusions, differently.
 *
 * ## This is derived, never stored
 *
 * Every value here is computed from the user's [AccessMode] plus a *current* capability
 * report. Nothing is remembered across a refresh. That is what keeps the application
 * honest when Shizuku stops after a reboot or a permission is revoked from Settings: the
 * state simply becomes what is true now, rather than what was true when setup finished.
 */
sealed interface SetupState {

    /** First launch. The access question has not been asked yet. */
    data object Welcome : SetupState

    /** The user is choosing between access methods. */
    data object ChoosingAccess : SetupState

    // ------------------------------------------------------------------ Shizuku route

    /** Shizuku is not installed. BattInsight cannot install it and does not try. */
    data object ShizukuNotInstalled : SetupState

    /** Installed, but its service is not running. Shizuku owns its own start flow. */
    data class ShizukuStopped(val versionName: String?) : SetupState

    /** Running, but it has not authorised BattInsight. Needs an explicit user action. */
    data class ShizukuUnauthorised(val serverVersion: Int) : SetupState

    /** Running and authorised. Both routes are now open to the user. */
    data class ShizukuReady(
        val serverVersion: Int,
        /** Whether a probe through the user service actually worked, not just that it bound. */
        val verified: Boolean,
    ) : SetupState

    /** Shizuku is running at a protocol version this build does not speak. */
    data class ShizukuUnsupported(val serverVersion: Int, val minimumSupported: Int) : SetupState

    // ------------------------------------------------------------ one-time grant route

    /** Showing exactly what will be granted, before anything is changed. */
    data object GrantConfirmation : SetupState

    /** A grant sequence is running. [completed] holds the steps already verified. */
    data class GrantInProgress(
        val current: RequiredPermission,
        val completed: List<GrantStep>,
    ) : SetupState

    /**
     * A grant step failed. The sequence stopped here.
     *
     * [completed] records what did change, so the user is told the truth about the state
     * their device is now in rather than a bare failure.
     */
    data class GrantFailed(
        val failed: GrantStep,
        val completed: List<GrantStep>,
    ) : SetupState

    /** The manual ADB instructions. */
    data object ManualAdb : SetupState

    // ------------------------------------------------------------------------ outcomes

    /** Running the behavioural check that decides whether setup really worked. */
    data object Verifying : SetupState

    /** Access works, proven behaviourally and not merely by permission flags. */
    data class Ready(
        val mode: AccessMode,
        val backend: BackendKind,
        val detail: String,
    ) : SetupState

    /**
     * The user chose to continue without privileged access, or their chosen route is not
     * currently working. Not an error state.
     */
    data class Limited(val reason: String, val mode: AccessMode) : SetupState

    /**
     * Setup appeared to succeed but the behavioural check disagreed.
     *
     * Kept distinct from [Limited] because it is a genuine inconsistency the user should
     * see rather than a state we quietly accept. Permission flags saying yes while
     * acquisition fails is exactly the kind of thing this application exists to surface.
     */
    data class VerificationFailed(val detail: String, val mode: AccessMode) : SetupState

    /** Something went wrong that is not a normal setup outcome. */
    data class Error(val detail: String) : SetupState

    /** Whether this state represents working privileged access. */
    val isReady: Boolean get() = this is Ready

    /** Whether the user is mid-flow and should not be interrupted by a refresh. */
    val isTransient: Boolean
        get() = this is GrantInProgress || this is Verifying
}

package com.rmpsdroid.battinsight.shizuku

/**
 * Where Shizuku actually is in its lifecycle.
 *
 * The states are separate because Phase 1B measured them behaving separately. Installing
 * Shizuku is not running it; running it is not being authorised by it. Granting the
 * `moe.shizuku.manager.permission.API_V23` permission through `pm grant` reported
 * `granted=true` and Shizuku **still** refused, because it maintains its own client
 * authorisation list and showed its own consent dialog.
 *
 * Collapsing any two of these would make the Capability Centre lie about what the user
 * needs to do next.
 */
sealed interface ShizukuState {

    /** The Shizuku application is not installed. */
    data object NotInstalled : ShizukuState

    /** Installed, but its service is not running. Needs starting via ADB or wireless debugging. */
    data class InstalledNotRunning(val versionName: String?) : ShizukuState

    /** Running, but this application has not been authorised by Shizuku. */
    data class RunningNotAuthorised(val serverVersion: Int, val serverUid: Int) : ShizukuState

    /** Running and authorised. Commands may be executed. */
    data class RunningAuthorised(val serverVersion: Int, val serverUid: Int) : ShizukuState

    /** Running but at a protocol version this build does not support. */
    data class VersionUnsupported(val serverVersion: Int, val minimumSupported: Int) : ShizukuState

    /** Querying Shizuku itself failed. */
    data class Error(val detail: String) : ShizukuState

    /** Not yet queried. */
    data object Unknown : ShizukuState

    val isUsable: Boolean get() = this is RunningAuthorised

    /** What the user would need to do next. Phase 4 turns these into actions; Phase 3 only states them. */
    val nextStep: String
        get() = when (this) {
            NotInstalled -> "Shizuku is not installed"
            is InstalledNotRunning -> "Shizuku is installed but not running"
            is RunningNotAuthorised -> "Shizuku is running but has not authorised BattInsight"
            is RunningAuthorised -> "Shizuku is running and authorised"
            is VersionUnsupported ->
                "Shizuku protocol v$serverVersion is below the supported minimum v$minimumSupported"
            is Error -> "Could not determine Shizuku state: $detail"
            Unknown -> "Shizuku state not checked"
        }
}

/**
 * Reads Shizuku state and executes whitelisted commands through it.
 *
 * An interface so the whole capability layer is testable without Shizuku installed. The
 * Android-backed implementation lives in the platform layer.
 */
interface ShizukuGateway {

    /** Current lifecycle state. Must be measured, never cached across a refresh. */
    suspend fun state(): ShizukuState

    /**
     * Ask Shizuku to authorise this application.
     *
     * Shizuku presents its own consent UI; this only starts that flow. **It grants no
     * Android permission** and changes no device state. Phase 3 does not call it -- it is
     * declared here so Phase 4 onboarding has the seam ready.
     */
    suspend fun requestAuthorisation()

    companion object {
        /** Shizuku package identifier, used for presence detection only. */
        const val PACKAGE: String = "moe.shizuku.privileged.api"

        /** Lowest binder protocol version this build knows how to talk to. */
        const val MINIMUM_PROTOCOL_VERSION: Int = 11
    }
}

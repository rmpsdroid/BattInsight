package com.rmpsdroid.battinsight.collection

/**
 * The ways BattInsight can attempt to acquire data.
 *
 * Distinct from [BackendIdentity.Kind], which describes what a process turned out to be
 * running as. This describes which route was taken; identity is measured afterwards.
 */
enum class BackendKind(
    val displayName: String,
    /** Whether an implementation exists at all. */
    val implemented: Boolean,
    /** Whether the route has ever been measured on real hardware or an emulator. */
    val measured: Boolean,
) {
    /**
     * Our own process, after being granted DUMP, PACKAGE_USAGE_STATS and
     * INTERACT_ACROSS_USERS. Measured working on Android 16 in Phase 1B.
     */
    GRANTED_APP("Granted app", implemented = true, measured = true),

    /**
     * An ADB-started Shizuku session. Measured at uid 2000 / `u:r:shell:s0` in Phase 1B,
     * producing output structurally identical to an ADB shell and needing none of our
     * privileged permissions.
     */
    SHIZUKU_ADB("Shizuku (ADB)", implemented = true, measured = true),

    /**
     * A root-started Shizuku session.
     *
     * **Not implemented and never measured.** No root environment existed in any phase, so
     * there is nothing to base an implementation on. Represented so the model is honest
     * about what is missing rather than silently omitting it.
     */
    SHIZUKU_ROOT("Shizuku (root)", implemented = false, measured = false),

    /**
     * Direct root execution.
     *
     * **Not implemented and never measured.** Phase 1B additionally found evidence against
     * assuming root helps: `/sys/class/wakeup` on Android 16 is world-readable by classic
     * permissions yet blocked by SELinux domain, so a root shell in the wrong domain can
     * fail where a shell session succeeds.
     */
    DIRECT_ROOT("Root", implemented = false, measured = false),
    ;

    val isAvailableForUse: Boolean get() = implemented
}

/**
 * Why a backend is or is not usable right now.
 *
 * A backend being unusable is a statement about the backend, not about any particular
 * capability. A capability can be unavailable while the backend is perfectly healthy.
 */
sealed interface BackendAvailability {

    /** Ready, with the identity it was measured to run as. */
    data class Ready(val identity: BackendIdentity) : BackendAvailability

    /** The route exists but its prerequisites are not met. */
    data class NotReady(val reason: String) : BackendAvailability

    /** No implementation exists. Distinct from a broken one. */
    data class NotImplemented(val reason: String) : BackendAvailability

    /** Probing the backend itself failed. */
    data class Failed(val detail: String) : BackendAvailability

    /** Not yet probed. */
    data object Unknown : BackendAvailability
}

/** One backend and its current status, as observed at a point in time. */
data class BackendStatus(
    val kind: BackendKind,
    val availability: BackendAvailability,
) {
    val isUsable: Boolean get() = availability is BackendAvailability.Ready

    val summary: String
        get() = when (val a = availability) {
            is BackendAvailability.Ready ->
                "Ready as uid ${a.identity.uid} (${a.identity.selinuxContext})"
            is BackendAvailability.NotReady -> a.reason
            is BackendAvailability.NotImplemented -> a.reason
            is BackendAvailability.Failed -> "Failed: ${a.detail}"
            BackendAvailability.Unknown -> "Not probed"
        }
}

package com.rmpsdroid.battinsight.capability

/**
 * What the application can actually do right now, for one [Capability].
 *
 * This model exists because Phase 1A/1B measured several outcomes that a naive
 * available/unavailable boolean collapses into the same value, producing the silent
 * empty screens that dominate both predecessor issue trackers:
 *
 *  - `dumpsys batterystats -c` from an ungranted app UID returns **exit status 0** with
 *    the denial text on **stdout** and nothing on stderr.
 *  - `UsageStatsManager.queryUsageStats` **does not throw** without access; it returns an
 *    empty list, indistinguishable from a genuinely empty window.
 *  - Kernel wakelocks on the Android 16 emulator returned 68 **named** entries whose
 *    counters were all zero, because that environment never suspends. Zero is the
 *    correct answer there, not a failure.
 *  - An app UID with the required permissions saw the same UIDs as an ADB shell but
 *    fewer UID-to-package-name mappings, because of package visibility filtering.
 *
 * A capability is therefore never inferred from a privilege level. It is established by
 * attempting the operation and classifying what came back.
 *
 * ## Which layer assigns these
 *
 * These are **semantic** states and are assigned by [CapabilityInterpreter], not by the
 * collection layer. `CollectionResult.outcome()` reports execution mechanics only
 * (`Data`, `Empty`, `PermissionDenied`, `SourceError`, `ExecutionFailed`, `Unrecognised`).
 *
 * The separation exists because [AvailableNoEvents] cannot be concluded from a generic
 * empty result. Deciding that a source is healthy-but-idle rather than absent requires
 * knowing what that source looks like when it has something to say, which is capability
 * knowledge the process layer does not have.
 *
 * Do not add a convenience `isAvailable` boolean to this type. Callers should handle the
 * cases they can act on; collapsing them is how the information gets lost.
 */
sealed interface CapabilityState {

    /** The source works and returned usable data. */
    data object Available : CapabilityState

    /**
     * The source works and is correctly reporting that nothing has happened yet.
     *
     * Distinct from [SourceUnavailable]: the data path is healthy. A device that has not
     * suspended has no kernel wakelock time; a fresh install has no history. Presenting
     * this as an error trains users to distrust correct output.
     */
    data class AvailableNoEvents(val detail: String) : CapabilityState

    /**
     * The source works but the result is known to be incomplete.
     *
     * Measured instance: UID-to-package-name resolution under an app UID without
     * broadened package visibility. Acquisition succeeded; naming did not.
     */
    data class AvailableDegraded(val reason: String) : CapabilityState

    /**
     * A permission we can obtain is missing.
     *
     * [permission] is the concrete manifest permission so the UI can name it and the
     * onboarding flow can offer the exact grant, rather than saying "check your permissions".
     */
    data class PermissionMissing(val permission: String) : CapabilityState

    /**
     * This device, ROM or kernel does not provide the source at all.
     *
     * No permission and no privilege level will change this. Saying "grant a permission"
     * here is the specific wrong answer both predecessors gave.
     */
    data class NotSupported(val reason: String) : CapabilityState

    /**
     * The source is expected to exist but was not found or not readable here.
     *
     * Distinct from [NotSupported]: the path may exist on other devices, or under another
     * backend or SELinux domain.
     */
    data class SourceUnavailable(val source: String) : CapabilityState

    /** The attempt itself failed -- process could not start, timed out, or produced garbage. */
    data class ExecutionFailed(val detail: String) : CapabilityState

    /** Not probed yet. Never treat as either available or unavailable. */
    data object Unknown : CapabilityState
}

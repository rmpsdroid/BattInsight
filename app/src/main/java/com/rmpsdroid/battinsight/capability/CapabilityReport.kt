package com.rmpsdroid.battinsight.capability

import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.collection.BackendSelection
import com.rmpsdroid.battinsight.collection.BackendStatus
import com.rmpsdroid.battinsight.permissions.PermissionSnapshot
import com.rmpsdroid.battinsight.shizuku.ShizukuState

/**
 * One capability, its state, and why.
 *
 * [reason] is required rather than optional. "Check your permissions" with no specifics is
 * the message both predecessor applications shipped, and it is the reason users could not
 * tell a missing permission from an unsupported kernel.
 */
data class CapabilityFinding(
    val capability: Capability,
    val state: CapabilityState,
    /** Short human-readable explanation, safe to display. Never contains collected data. */
    val reason: String,
    /** Which backend produced this, where one was involved. */
    val viaBackend: BackendKind? = null,
)

/**
 * The complete runtime capability picture at one instant.
 *
 * Named a *report*, deliberately not a snapshot: `Snapshot` is reserved for the future
 * battery session model, which captures counters and has boot identity and comparability
 * rules. This is transient environment state with none of those properties, and confusing
 * the two would be an expensive mistake later.
 */
data class CapabilityReport(
    val timestampMillis: Long,
    val backends: List<BackendStatus>,
    val permissions: PermissionSnapshot,
    val shizuku: ShizukuState,
    val findings: List<CapabilityFinding>,
    /**
     * Which backend was chosen, and why.
     *
     * Computed by the collection layer so every screen shows the same answer. A screen that
     * worked this out for itself could disagree with what actually ran.
     */
    val selection: BackendSelection = BackendSelection.unknown,
    /** True while a refresh is running, so the UI can show progress without a second flag. */
    val refreshing: Boolean = false,
) {
    fun finding(capability: Capability): CapabilityFinding? =
        findings.firstOrNull { it.capability == capability }

    fun backend(kind: BackendKind): BackendStatus? =
        backends.firstOrNull { it.kind == kind }

    /** Backends currently usable. Empty is a normal state, not an error. */
    val usableBackends: List<BackendKind>
        get() = backends.filter { it.isUsable }.map { it.kind }

    /**
     * The backend that should be preferred right now, if any.
     *
     * Shizuku first when usable: Phase 1B measured it 2-4x faster and resolving UID names
     * the app UID could not, while needing none of our privileged permissions.
     */
    val preferredBackend: BackendKind?
        get() = usableBackends.firstOrNull { it == BackendKind.SHIZUKU_ADB }
            ?: usableBackends.firstOrNull()

    companion object {
        /** Before anything has been probed. Every capability is Unknown, never assumed absent. */
        fun unknown(timestampMillis: Long = 0L): CapabilityReport = CapabilityReport(
            timestampMillis = timestampMillis,
            backends = BackendKind.entries.map {
                BackendStatus(it, com.rmpsdroid.battinsight.collection.BackendAvailability.Unknown)
            },
            permissions = PermissionSnapshot.unknown,
            shizuku = ShizukuState.Unknown,
            findings = Capability.entries.map {
                CapabilityFinding(it, CapabilityState.Unknown, "Not checked yet")
            },
        )
    }
}

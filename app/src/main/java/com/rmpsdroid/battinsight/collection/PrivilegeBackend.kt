package com.rmpsdroid.battinsight.collection

/**
 * A way of executing a battery-statistics acquisition.
 *
 * Two implementations are planned, and Phase 1B measured both producing structurally
 * identical output -- same version record, same 46 record tags, same 121 visible UIDs,
 * same kernel wakelock count -- which is what makes a single interface honest rather
 * than merely tidy:
 *
 *  - **Granted app backend.** Our own process, after being granted DUMP,
 *    PACKAGE_USAGE_STATS and INTERACT_ACROSS_USERS. Needs no third-party app.
 *  - **Shizuku shell backend.** An ADB-started Shizuku session, measured at uid 2000 /
 *    `u:r:shell:s0`. Needs none of our privileged permissions, runs faster, and resolves
 *    UID names the app UID cannot.
 *
 * Neither is implemented in Phase 2A. This interface exists to fix the boundary before
 * either is written, so that the session engine and persistence layers can be built and
 * tested against a fake.
 *
 * Implementations must not infer capability from privilege. They report what they are and
 * what happened; classification belongs to [CollectionResult.classify].
 */
interface PrivilegeBackend {

    /** Stable identifier for logging, diagnostics and snapshot provenance. */
    val id: String

    /**
     * The identity this backend actually executes as.
     *
     * Must be established by asking the running process, not assumed from how the backend
     * was configured. Returns [BackendIdentity.UNKNOWN] before that has been determined.
     */
    suspend fun identity(): BackendIdentity

    /**
     * Whether this backend can currently execute anything at all.
     *
     * A false result is a statement about the backend -- service not running, not
     * authorised, not installed -- not about any particular [SourceFormat].
     */
    suspend fun isReady(): Boolean

    /**
     * Attempt to acquire battery statistics in [format].
     *
     * Returns metadata only in this phase. Implementations must never issue a
     * state-changing batterystats argument; see [SourceFormat] and docs/data-sources.md.
     */
    suspend fun collect(format: SourceFormat): CollectionResult
}

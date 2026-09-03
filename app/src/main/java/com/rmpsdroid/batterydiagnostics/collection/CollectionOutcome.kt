package com.rmpsdroid.batterydiagnostics.collection

/**
 * What happened when a collection was attempted, at the transport and process level.
 *
 * This is deliberately **not** a capability state. It describes the mechanics of one
 * execution -- did the process run, was it refused, did anything come back -- and stops
 * there. Deciding what an outcome *means* for a particular data source requires knowing
 * what that source looks like when it is healthy, which is capability-specific knowledge
 * this layer does not have.
 *
 * The distinction matters most for emptiness. An empty result from a successful process is
 * simply [Empty]; whether that is a correctly-idle source, a missing section, or a silent
 * refusal is not knowable here. `com.rmpsdroid.batterydiagnostics.capability.CapabilityInterpreter`
 * makes that call with the extra evidence needed to make it honestly.
 */
sealed interface CollectionOutcome {

    /** The process ran and produced output that looks like the format we asked for. */
    data class Data(val bytes: Int) : CollectionOutcome

    /**
     * The process ran, exited cleanly, and produced nothing.
     *
     * **Not a success claim and not a failure claim.** A healthy source with nothing to
     * report and a source that quietly returned nothing are indistinguishable here.
     */
    data object Empty : CollectionOutcome

    /**
     * The platform refused, and named a permission.
     *
     * @param permission the permission we should actually ask for -- one we can grant.
     * @param alternatives other permissions the platform mentioned. Android names
     *   `INTERACT_ACROSS_USERS_FULL` alongside `INTERACT_ACROSS_USERS`, but only the
     *   latter is grantable to us, so the former belongs here rather than in [permission].
     * @param rawDetail the platform's message, preserved verbatim for diagnostics.
     */
    data class PermissionDenied(
        val permission: String,
        val alternatives: List<String>,
        val rawDetail: String,
    ) : CollectionOutcome

    /**
     * The process ran but the source itself reported a problem -- an unknown argument,
     * a service that could not be reached, output that is not the requested format.
     */
    data class SourceError(val detail: String) : CollectionOutcome

    /** The process failed to run, timed out, or exited non-zero. */
    data class ExecutionFailed(val exitCode: Int?, val detail: String) : CollectionOutcome

    /**
     * Something came back that none of the above describes.
     *
     * Exists so that unrecognised output is never silently reported as success. If this
     * appears in the field it is a signal to extend the classifier, not to ignore it.
     */
    data class Unrecognised(val detail: String) : CollectionOutcome
}

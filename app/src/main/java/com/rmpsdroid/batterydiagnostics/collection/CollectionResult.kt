package com.rmpsdroid.batterydiagnostics.collection

import com.rmpsdroid.batterydiagnostics.capability.CapabilityState

/**
 * Metadata describing one acquisition attempt.
 *
 * Deliberately carries no payload. Phase 2A does not store or parse batterystats output;
 * this type exists so the classification rules can be written and tested before any
 * collector exists.
 *
 * The single most important rule it encodes: **a zero exit status does not mean success.**
 * Every denial measured in Phase 1B returned exit 0 with the error written to stdout and
 * an empty stderr:
 *
 * ```
 * exit 0, stdout 131 B: "Permission Denial: can't dump BatteryStatsService ...
 *                        due to missing android.permission.DUMP permission"
 * exit 0, stdout 146 B: "... due to missing android.permission.PACKAGE_USAGE_STATS permission"
 * exit 0, stdout 210 B: "Security exception: MATCH_ANY_USER flag requires
 *                        INTERACT_ACROSS_USERS permission ..."
 * ```
 *
 * Classification is therefore driven by content, with exit status as a secondary signal.
 */
data class CollectionResult(
    val backend: BackendIdentity.Kind,
    val sourceFormat: SourceFormat,
    /** Process exit status, or null if the process could not be run or timed out. */
    val exitCode: Int?,
    val stdoutBytes: Int,
    val stderrBytes: Int,
    /** First bytes of stdout, decoded as text, used only for classification. */
    val stdoutHead: String,
    /** stderr decoded as text, used only for classification. */
    val stderrText: String,
    /** Wall-clock duration of the attempt. */
    val durationMillis: Long,
    /** Epoch milliseconds at which the attempt completed. */
    val timestampMillis: Long,
) {
    val hasStdout: Boolean get() = stdoutBytes > 0
    val hasStderr: Boolean get() = stderrBytes > 0

    /**
     * Classify this attempt.
     *
     * Order matters. Denials are checked before emptiness, and emptiness before success,
     * because a denial is itself a small non-empty payload.
     */
    fun classify(): CapabilityState {
        if (exitCode == null) {
            return CapabilityState.ExecutionFailed("process did not complete")
        }

        val combined = stdoutHead + '\n' + stderrText

        missingPermissionIn(combined)?.let { return CapabilityState.PermissionMissing(it) }

        if (combined.contains(PERMISSION_DENIAL, ignoreCase = true) ||
            combined.contains(SECURITY_EXCEPTION, ignoreCase = true)
        ) {
            // Denied, but the platform did not name a permission we recognise.
            return CapabilityState.ExecutionFailed(combined.trim().take(DETAIL_LIMIT))
        }

        if (!hasStdout) {
            return if (exitCode == 0) {
                // Empty is not failure. A healthy source with nothing to report looks like this.
                CapabilityState.AvailableNoEvents("command produced no output")
            } else {
                CapabilityState.ExecutionFailed("exit $exitCode with no output")
            }
        }

        if (exitCode != 0) {
            return CapabilityState.ExecutionFailed("exit $exitCode")
        }

        return CapabilityState.Available
    }

    private fun missingPermissionIn(text: String): String? =
        KNOWN_PERMISSIONS.firstOrNull { permission ->
            text.contains(permission) &&
                (text.contains(MISSING, ignoreCase = true) ||
                    text.contains(REQUIRES, ignoreCase = true))
        }

    companion object {
        private const val PERMISSION_DENIAL = "Permission Denial"
        private const val SECURITY_EXCEPTION = "Security exception"
        private const val MISSING = "missing"
        private const val REQUIRES = "requires"
        private const val DETAIL_LIMIT = 200

        /**
         * Permissions the platform has been measured to name in a denial.
         *
         * Ordered most specific first: the INTERACT_ACROSS_USERS denial text mentions both
         * `INTERACT_ACROSS_USERS_FULL` and `INTERACT_ACROSS_USERS`, so the longer name must
         * be tested first to avoid reporting the wrong one.
         */
        private val KNOWN_PERMISSIONS = listOf(
            "android.permission.INTERACT_ACROSS_USERS_FULL",
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.DUMP",
            "android.permission.BATTERY_STATS",
        )
    }
}

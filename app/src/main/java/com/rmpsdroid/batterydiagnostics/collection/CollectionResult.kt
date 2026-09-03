package com.rmpsdroid.batterydiagnostics.collection

/**
 * Metadata describing one acquisition attempt.
 *
 * Deliberately carries no payload. Phase 2A does not store or parse batterystats output;
 * this type exists so the classification rules can be written and tested before any
 * collector exists.
 *
 * ## Exit status is necessary but not sufficient
 *
 * Phase 1B measured every permission denial arriving with **exit status 0**, the error on
 * **stdout**, and an empty stderr:
 *
 * ```
 * exit 0, stdout 131 B: "Permission Denial: can't dump BatteryStatsService ...
 *                        due to missing android.permission.DUMP permission"
 * exit 0, stdout 146 B: "... due to missing android.permission.PACKAGE_USAGE_STATS permission"
 * exit 0, stdout 210 B: "Security exception: MATCH_ANY_USER flag requires
 *                        INTERACT_ACROSS_USERS permission ..."
 * ```
 *
 * So a zero exit cannot be treated as success. It does not follow that the exit code is
 * irrelevant: a non-zero exit is real evidence of failure and is used as such. Content is
 * examined first, then the exit code, then the shape of the output.
 *
 * ## This layer does not decide what an outcome means
 *
 * [outcome] reports mechanics only. In particular an empty result is [CollectionOutcome.Empty]
 * and nothing more -- it is not translated into a capability state here, because whether
 * emptiness is correct depends on what the source looks like when healthy.
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
     * Classify the mechanics of this attempt.
     *
     * Precedence, in order:
     *  1. a recognised permission or security denial -- checked first because it can and
     *     does arrive with exit 0;
     *  2. a recognised source-level error;
     *  3. a non-zero exit status;
     *  4. output carrying a marker of the format we requested;
     *  5. exit 0 with no output;
     *  6. anything else, reported as unrecognised rather than assumed successful.
     */
    fun outcome(): CollectionOutcome {
        if (exitCode == null) {
            return CollectionOutcome.ExecutionFailed(null, "process did not complete")
        }

        val combined = buildString {
            append(stdoutHead)
            if (stderrText.isNotEmpty()) {
                append('\n')
                append(stderrText)
            }
        }

        // 1. Denials first -- these arrive with exit 0.
        denialIn(combined)?.let { return it }

        // 2. Source-level errors the command itself reported.
        sourceErrorIn(combined)?.let { return CollectionOutcome.SourceError(it) }

        // 3. A non-zero exit is real evidence of failure, once content has been ruled out.
        if (exitCode != 0) {
            val detail = combined.trim().ifEmpty { "no output" }.take(DETAIL_LIMIT)
            return CollectionOutcome.ExecutionFailed(exitCode, detail)
        }

        // 4. Output that looks like what we asked for.
        if (hasStdout && looksLikeRequestedFormat(combined)) {
            return CollectionOutcome.Data(stdoutBytes)
        }

        // 5. Clean exit, nothing produced. Meaning is decided by the capability layer.
        if (!hasStdout) {
            return CollectionOutcome.Empty
        }

        // 6. Output we cannot account for. Never reported as success.
        return CollectionOutcome.Unrecognised(combined.trim().take(DETAIL_LIMIT))
    }

    /**
     * Whether the output carries a marker of the format that was requested.
     *
     * Checkin output opens with a `vers` record; protobuf is binary and is length-checked
     * rather than pattern-matched, because a text error message would otherwise pass.
     */
    private fun looksLikeRequestedFormat(text: String): Boolean = when (sourceFormat) {
        SourceFormat.CHECKIN -> text.contains(CHECKIN_VERS_MARKER)
        SourceFormat.PROTO -> stdoutBytes > PROTO_MIN_PLAUSIBLE_BYTES &&
            !text.contains(PERMISSION_DENIAL, ignoreCase = true)
        SourceFormat.TEXT -> stdoutBytes > 0
    }

    private fun denialIn(text: String): CollectionOutcome.PermissionDenied? {
        val looksLikeDenial = text.contains(PERMISSION_DENIAL, ignoreCase = true) ||
            text.contains(SECURITY_EXCEPTION, ignoreCase = true) ||
            text.contains(MISSING, ignoreCase = true) ||
            text.contains(REQUIRES, ignoreCase = true)
        if (!looksLikeDenial) return null

        val signature = DENIAL_SIGNATURES.firstOrNull { sig ->
            sig.markers.any { text.contains(it) }
        }
        val detail = text.trim().take(DETAIL_LIMIT)

        return when {
            signature != null -> CollectionOutcome.PermissionDenied(
                permission = signature.actionable,
                alternatives = signature.alternatives.filter { text.contains(it) },
                rawDetail = detail,
            )
            // Refused, but no permission we recognise was named. Not a permission we can
            // act on, so it is not reported as one.
            text.contains(PERMISSION_DENIAL, ignoreCase = true) ||
                text.contains(SECURITY_EXCEPTION, ignoreCase = true) -> null
            else -> null
        }
    }

    private fun sourceErrorIn(text: String): String? {
        val marker = SOURCE_ERROR_MARKERS.firstOrNull { text.contains(it, ignoreCase = true) }
            ?: return null
        return if (text.contains(PERMISSION_DENIAL, ignoreCase = true) ||
            text.contains(SECURITY_EXCEPTION, ignoreCase = true)
        ) {
            null // a denial, already handled above
        } else {
            "$marker: ${text.trim().take(DETAIL_LIMIT)}"
        }
    }

    /**
     * A recognised denial, and which permission we should actually ask the user for.
     *
     * @param actionable the permission our onboarding can grant.
     * @param alternatives permissions the platform also names but which we cannot obtain.
     */
    private data class DenialSignature(
        val actionable: String,
        val alternatives: List<String> = emptyList(),
        val markers: List<String>,
    )

    companion object {
        private const val PERMISSION_DENIAL = "Permission Denial"
        private const val SECURITY_EXCEPTION = "Security exception"
        private const val MISSING = "missing android.permission"
        private const val REQUIRES = "requires android.permission"
        private const val CHECKIN_VERS_MARKER = ",vers,"
        private const val PROTO_MIN_PLAUSIBLE_BYTES = 1024
        private const val DETAIL_LIMIT = 400

        private val SOURCE_ERROR_MARKERS = listOf(
            "Unknown option",
            "Bad argument",
            "can't find service",
            "Could not access",
        )

        /**
         * Denials the platform has been measured to produce.
         *
         * `INTERACT_ACROSS_USERS` is the case that matters. The measured message names
         * both `INTERACT_ACROSS_USERS_FULL` and `INTERACT_ACROSS_USERS`:
         *
         * ```
         * Security exception: MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS permission:
         * UID 10241 requires android.permission.INTERACT_ACROSS_USERS_FULL or
         * android.permission.INTERACT_ACROSS_USERS to access user 0.
         * ```
         *
         * Only the non-`_FULL` form is actionable for us: Phase 1B measured that granting
         * `INTERACT_ACROSS_USERS` alone was sufficient for full acquisition, and on
         * Android 16 it carries the `development` protection level that makes `pm grant`
         * work. `INTERACT_ACROSS_USERS_FULL` does not share that grant model, so telling a
         * user to grant it would send them down a path that cannot succeed.
         *
         * `_FULL` is therefore recorded as an alternative the platform mentioned, never as
         * the thing to ask for.
         */
        private val DENIAL_SIGNATURES = listOf(
            DenialSignature(
                actionable = "android.permission.DUMP",
                markers = listOf("android.permission.DUMP"),
            ),
            DenialSignature(
                actionable = "android.permission.PACKAGE_USAGE_STATS",
                markers = listOf("android.permission.PACKAGE_USAGE_STATS"),
            ),
            DenialSignature(
                actionable = "android.permission.INTERACT_ACROSS_USERS",
                alternatives = listOf("android.permission.INTERACT_ACROSS_USERS_FULL"),
                markers = listOf("INTERACT_ACROSS_USERS"),
            ),
            DenialSignature(
                actionable = "android.permission.BATTERY_STATS",
                markers = listOf("android.permission.BATTERY_STATS"),
            ),
        )
    }
}

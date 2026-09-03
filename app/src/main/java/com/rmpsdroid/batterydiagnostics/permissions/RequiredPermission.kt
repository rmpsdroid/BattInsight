package com.rmpsdroid.batterydiagnostics.permissions

/**
 * The privileged permissions the granted-app backend requires.
 *
 * This set is measured, not assumed. Phase 1B granted permissions one at a time on
 * Android 16 and recorded what the platform said at each step:
 *
 * ```
 * (none)                                  -> "missing android.permission.DUMP"
 * + DUMP                                  -> "missing android.permission.PACKAGE_USAGE_STATS"
 * + PACKAGE_USAGE_STATS                   -> "MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS"
 * + INTERACT_ACROSS_USERS                 -> 814,304 bytes of data
 * ```
 *
 * Two results that contradict earlier planning:
 *
 *  - `BATTERY_STATS` is **not** required. Acquisition succeeded with it explicitly denied,
 *    and granting it afterwards changed nothing measurable. It is excluded here.
 *  - `INTERACT_ACROSS_USERS` **is** required even on a single-user device, because
 *    `dumpsys batterystats` passes `MATCH_ANY_USER` internally. Phase 0.1 had flagged it
 *    as a candidate for removal; measurement says otherwise.
 *
 * None of these are declared in the manifest yet. Declaration is deferred until the
 * backend that uses them exists -- see docs/security-privacy.md.
 *
 * The Shizuku backend requires **none** of these.
 */
enum class RequiredPermission(val manifestName: String, val why: String) {
    DUMP(
        "android.permission.DUMP",
        "Read BatteryStatsService output. Refused first without it.",
    ),
    PACKAGE_USAGE_STATS(
        "android.permission.PACKAGE_USAGE_STATS",
        "Refused second without it. Also enables UsageStatsManager and NetworkStatsManager.",
    ),
    INTERACT_ACROSS_USERS(
        "android.permission.INTERACT_ACROSS_USERS",
        "dumpsys batterystats uses MATCH_ANY_USER internally. Required even single-user.",
    ),
    ;

    /**
     * Permissions the platform may name alongside this one that we cannot obtain.
     *
     * The MATCH_ANY_USER denial names `INTERACT_ACROSS_USERS_FULL` as well, but that
     * permission does not carry the `development` protection level, so `pm grant` cannot
     * deliver it to an ordinary application. Phase 1B measured the non-FULL form to be
     * sufficient on its own. Recording FULL here keeps the platform's wording traceable
     * without ever offering it as something to grant.
     */
    val platformMentionedAlternatives: List<String>
        get() = when (this) {
            INTERACT_ACROSS_USERS -> listOf("android.permission.INTERACT_ACROSS_USERS_FULL")
            else -> emptyList()
        }

    /**
     * The ADB command that grants this permission.
     *
     * Kept as data rather than presentation text so onboarding can render it per platform
     * and, once the Shizuku backend exists, execute the equivalent on-device.
     */
    fun grantCommand(applicationId: String): String =
        "pm grant $applicationId $manifestName"

    companion object {
        /** The complete measured minimum set for the granted-app backend. */
        val minimumSet: List<RequiredPermission> get() = entries

        /**
         * Explicitly not required, recorded so it is not reintroduced by assumption.
         *
         * Both predecessor applications requested this and their documentation instructed
         * users to grant it. Phase 1B measured it to be unnecessary.
         */
        const val NOT_REQUIRED_BATTERY_STATS = "android.permission.BATTERY_STATS"
    }
}

package com.rmpsdroid.battinsight.setup

import com.rmpsdroid.battinsight.permissions.RequiredPermission

/**
 * The complete set of state-changing operations BattInsight is permitted to perform.
 *
 * This is the second security boundary in the application, and it is stricter than the
 * first. `ProbeCommand` governs what may be *read*; this governs what may be *changed* —
 * and everything here runs with shell identity through Shizuku, so the rules are tighter:
 *
 *  - There is no `execute(command: String)` anywhere on the path. A caller names an
 *    action; only this file maps an action to an argument vector.
 *  - The target package is a compile-time constant. It is never a parameter, never derived
 *    from anything a caller supplies, and cannot be pointed at another application.
 *  - The permission is drawn from [RequiredPermission], which is the measured minimum set.
 *    There is no action for `BATTERY_STATS` (measured unnecessary) and none for
 *    `INTERACT_ACROSS_USERS_FULL` (not grantable to an ordinary application).
 *  - Only `grant` and `revoke` exist. There is no app-op action: Phase 1B measured
 *    `PACKAGE_USAGE_STATS` granted while `GET_USAGE_STATS` stayed at `DEFAULT`, with the
 *    usage query still returning 70 rows, so forcing the app-op is unnecessary — and
 *    changing an app-op is a heavier, less visible intervention than granting a permission
 *    the user explicitly approved.
 *
 * Adding an entry is a reviewable change to this file, and `SetupActionContractTest`
 * asserts the shape of every one of them.
 */
sealed class SetupAction(
    /** Stable identifier. This, and only this, crosses the Binder. */
    val id: String,
    val permission: RequiredPermission,
    val operation: Operation,
) {
    /** What is being done to the permission. */
    enum class Operation(val pmVerb: String, val verb: String) {
        GRANT("grant", "Grant"),
        REVOKE("revoke", "Remove"),
    }

    /**
     * The argument vector, assembled here and nowhere else.
     *
     * Absolute path, fixed verb, fixed package, fixed permission name. No shell, no
     * `sh -c`, no interpolation of anything a caller controls.
     */
    val argv: List<String>
        get() = listOf(PM_PATH, operation.pmVerb, TARGET_PACKAGE, permission.manifestName)

    /** The equivalent command a user would run from a computer, for the manual path. */
    val adbCommand: String
        get() = "adb shell pm ${operation.pmVerb} $TARGET_PACKAGE ${permission.manifestName}"

    data object GrantDump :
        SetupAction("grant_dump", RequiredPermission.DUMP, Operation.GRANT)

    data object GrantPackageUsageStats :
        SetupAction("grant_package_usage_stats", RequiredPermission.PACKAGE_USAGE_STATS, Operation.GRANT)

    data object GrantInteractAcrossUsers :
        SetupAction("grant_interact_across_users", RequiredPermission.INTERACT_ACROSS_USERS, Operation.GRANT)

    data object RevokeDump :
        SetupAction("revoke_dump", RequiredPermission.DUMP, Operation.REVOKE)

    data object RevokePackageUsageStats :
        SetupAction("revoke_package_usage_stats", RequiredPermission.PACKAGE_USAGE_STATS, Operation.REVOKE)

    data object RevokeInteractAcrossUsers :
        SetupAction("revoke_interact_across_users", RequiredPermission.INTERACT_ACROSS_USERS, Operation.REVOKE)

    companion object {
        /**
         * BattInsight's own application id, fixed at compile time.
         *
         * Asserted equal to `BuildConfig.APPLICATION_ID` by test, so it cannot drift away
         * from the package actually being built, and so nobody can quietly repoint these
         * operations at another application.
         */
        const val TARGET_PACKAGE: String = "com.rmpsdroid.battinsight"

        /** Absolute path. Never resolved through `PATH`. */
        const val PM_PATH: String = "/system/bin/pm"

        /**
         * Every action that exists. A computed property for the same reason
         * `ProbeCommand.all` is: the companion initialiser runs before the nested objects
         * are constructed, and this is the list the safety tests iterate.
         */
        val all: List<SetupAction> get() = grants + revokes

        /** Grants, in the order the platform was measured to demand them. */
        val grants: List<SetupAction> get() = listOf(
            GrantDump,
            GrantPackageUsageStats,
            GrantInteractAcrossUsers,
        )

        /** Revokes, in the reverse of the grant order, so the broadest goes last. */
        val revokes: List<SetupAction> get() = listOf(
            RevokeInteractAcrossUsers,
            RevokePackageUsageStats,
            RevokeDump,
        )

        /** The grant action for a permission. Total over [RequiredPermission] by construction. */
        fun grantFor(permission: RequiredPermission): SetupAction = when (permission) {
            RequiredPermission.DUMP -> GrantDump
            RequiredPermission.PACKAGE_USAGE_STATS -> GrantPackageUsageStats
            RequiredPermission.INTERACT_ACROSS_USERS -> GrantInteractAcrossUsers
        }

        /** The revoke action for a permission. */
        fun revokeFor(permission: RequiredPermission): SetupAction = when (permission) {
            RequiredPermission.DUMP -> RevokeDump
            RequiredPermission.PACKAGE_USAGE_STATS -> RevokePackageUsageStats
            RequiredPermission.INTERACT_ACROSS_USERS -> RevokeInteractAcrossUsers
        }

        /**
         * Resolves an identifier, or null if it names nothing.
         *
         * The remote service calls this before doing anything at all: an unrecognised
         * identifier must fail without a process being created.
         */
        fun forId(id: String?): SetupAction? = all.firstOrNull { it.id == id }
    }
}

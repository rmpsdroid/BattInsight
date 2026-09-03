package com.rmpsdroid.battinsight.permissions

/** Whether one manifest permission is held. */
enum class PermissionGrant { GRANTED, DENIED, UNKNOWN }

/**
 * An app-op mode.
 *
 * Kept distinct from [PermissionGrant] because Phase 1B measured them disagreeing: after
 * `pm grant android.permission.PACKAGE_USAGE_STATS` the permission read GRANTED while the
 * `GET_USAGE_STATS` app-op stayed at `DEFAULT` -- and the usage query still returned 70
 * rows. Treating either as authoritative alone gives the wrong answer.
 */
enum class AppOpMode { ALLOWED, IGNORED, ERRORED, DEFAULT, UNKNOWN }

/** State of one required permission. */
data class PermissionStatus(
    val permission: RequiredPermission,
    val grant: PermissionGrant,
) {
    val isGranted: Boolean get() = grant == PermissionGrant.GRANTED
}

/**
 * The permission picture, reported per permission.
 *
 * Deliberately exposes no single `permissionsGranted: Boolean`. The measured platform
 * behaviour is a staged sequence -- DUMP alone is refused for lacking
 * PACKAGE_USAGE_STATS, which is then refused for lacking INTERACT_ACROSS_USERS -- so a
 * caller that only knows "not all granted" cannot tell the user which one to grant next.
 */
data class PermissionSnapshot(
    val statuses: List<PermissionStatus>,
    /** The usage-access app-op, an alternative route to usage data. */
    val usageStatsAppOp: AppOpMode,
) {
    fun grantOf(permission: RequiredPermission): PermissionGrant =
        statuses.firstOrNull { it.permission == permission }?.grant ?: PermissionGrant.UNKNOWN

    /** Permissions still needed, in the order the platform demands them. */
    val missing: List<RequiredPermission>
        get() = RequiredPermission.minimumSet.filter { grantOf(it) != PermissionGrant.GRANTED }

    /** True only when every measured-required permission is held. */
    val allRequiredGranted: Boolean get() = missing.isEmpty()

    /**
     * Whether usage data should be reachable.
     *
     * Two valid routes, and either suffices: holding `PACKAGE_USAGE_STATS`, or the
     * `GET_USAGE_STATS` app-op being explicitly allowed (what the Settings toggle sets).
     * Requiring the app-op to be `ALLOWED` when the permission is granted would contradict
     * the Phase 1B measurement.
     */
    val usageAccessExpected: Boolean
        get() = grantOf(RequiredPermission.PACKAGE_USAGE_STATS) == PermissionGrant.GRANTED ||
            usageStatsAppOp == AppOpMode.ALLOWED

    companion object {
        val unknown: PermissionSnapshot = PermissionSnapshot(
            statuses = RequiredPermission.minimumSet.map {
                PermissionStatus(it, PermissionGrant.UNKNOWN)
            },
            usageStatsAppOp = AppOpMode.UNKNOWN,
        )
    }
}

/** Reads permission and app-op state. An interface so capability logic is testable. */
interface PermissionStateReader {
    suspend fun read(): PermissionSnapshot
}

package com.rmpsdroid.battinsight.setup

import com.rmpsdroid.battinsight.permissions.AppOpMode
import com.rmpsdroid.battinsight.permissions.PermissionGrant
import com.rmpsdroid.battinsight.permissions.PermissionSnapshot
import com.rmpsdroid.battinsight.permissions.PermissionStateReader
import com.rmpsdroid.battinsight.permissions.PermissionStatus
import com.rmpsdroid.battinsight.permissions.RequiredPermission

/**
 * Fakes for the setup seams.
 *
 * The permission reader is *mutable* here, unlike the capability-layer fake, because the
 * whole point of the grant sequence is that permissions change while it runs. A fixed
 * snapshot could not distinguish "the grant worked" from "the grant claimed to work",
 * which is exactly the confusion the sequence exists to prevent.
 */

/** A permission reader whose answers change, the way a real device's do. */
class MutablePermissionReader(
    granted: Set<RequiredPermission> = emptySet(),
    private var appOp: AppOpMode = AppOpMode.DEFAULT,
) : PermissionStateReader {

    private val held = granted.toMutableSet()

    /** Every read, in order. Lets a test assert that state was re-read after each step. */
    val reads = mutableListOf<Set<RequiredPermission>>()

    override suspend fun read(): PermissionSnapshot {
        reads += held.toSet()
        return PermissionSnapshot(
            statuses = RequiredPermission.minimumSet.map {
                PermissionStatus(
                    it,
                    if (it in held) PermissionGrant.GRANTED else PermissionGrant.DENIED,
                )
            },
            usageStatsAppOp = appOp,
        )
    }

    fun grant(permission: RequiredPermission) = apply { held += permission }
    fun revoke(permission: RequiredPermission) = apply { held -= permission }
    fun holds(permission: RequiredPermission) = permission in held
    val heldNow: Set<RequiredPermission> get() = held.toSet()

    /** Records an app-op change so a test can prove none was ever made. */
    fun setAppOp(mode: AppOpMode) = apply { appOp = mode }
    val appOpNow: AppOpMode get() = appOp
}

/**
 * A setup executor that records everything asked of it.
 *
 * By default a grant actually updates [permissions], so the happy path behaves like a real
 * device. Individual actions can be made to fail, or to succeed at the command level while
 * leaving the permission untouched -- the case where `pm` reports success and nothing
 * changed.
 */
class FakeSetupExecutor(
    private val permissions: MutablePermissionReader,
    private var ready: Boolean = true,
) : SetupExecutor {

    /** Every action attempted, in order. */
    val attempted = mutableListOf<SetupAction>()

    private val failures = mutableMapOf<String, SetupOutcome>()
    private val silentNoOps = mutableSetOf<String>()

    override suspend fun isReady(): Boolean = ready

    override suspend fun execute(action: SetupAction): SetupOutcome {
        attempted += action

        failures[action.id]?.let { return it }

        if (action.id in silentNoOps) {
            // Reports success, changes nothing. The dishonest-command case.
            return SetupOutcome.Executed(exitCode = 0, message = "", durationMillis = 3)
        }

        when (action.operation) {
            SetupAction.Operation.GRANT -> permissions.grant(action.permission)
            SetupAction.Operation.REVOKE -> permissions.revoke(action.permission)
        }
        return SetupOutcome.Executed(exitCode = 0, message = "", durationMillis = 3)
    }

    /** Makes one action fail outright. */
    fun failing(action: SetupAction, reason: String = "Operation not allowed") = apply {
        failures[action.id] = SetupOutcome.Executed(exitCode = 255, message = reason, durationMillis = 2)
    }

    /** Makes one action report success while changing nothing. */
    fun silentlyIneffective(action: SetupAction) = apply { silentNoOps += action.id }

    fun notReady() = apply { ready = false }

    fun attemptedIds(): List<String> = attempted.map { it.id }
}

/** An executor with no privileged backend behind it. */
class UnavailableSetupExecutor(private val reason: String = "Shizuku is not authorised") :
    SetupExecutor {
    val attempted = mutableListOf<SetupAction>()
    override suspend fun isReady(): Boolean = false
    override suspend fun execute(action: SetupAction): SetupOutcome {
        attempted += action
        return SetupOutcome.Unavailable(reason)
    }
}

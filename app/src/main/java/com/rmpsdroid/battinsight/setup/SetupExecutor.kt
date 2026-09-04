package com.rmpsdroid.battinsight.setup

import com.rmpsdroid.battinsight.permissions.PermissionGrant
import com.rmpsdroid.battinsight.permissions.RequiredPermission

/**
 * What happened when a [SetupAction] was attempted.
 *
 * Deliberately mechanical: it records that a process ran and what it said. Whether the
 * *permission actually changed* is a separate question, answered by re-reading the
 * permission afterwards — see [GrantStep]. Phase 1B measured `pm grant` reporting success
 * for Shizuku's own permission while Shizuku still refused, so a command's own account of
 * itself is not accepted as proof.
 */
sealed interface SetupOutcome {

    /** The command ran to completion. A zero exit is not by itself success. */
    data class Executed(
        val exitCode: Int?,
        /** Bounded diagnostic text. Never a payload. */
        val message: String,
        val durationMillis: Long,
    ) : SetupOutcome

    /** The remote side refused the identifier without executing anything. */
    data class Refused(val reason: String) : SetupOutcome

    /** No privileged backend was available to attempt it. */
    data class Unavailable(val reason: String) : SetupOutcome

    /** The command reported a clean exit. Still not proof the permission changed. */
    val exitedCleanly: Boolean get() = this is Executed && exitCode == 0

    val detail: String
        get() = when (this) {
            is Executed -> if (exitCode == 0) {
                message.ifBlank { "completed" }
            } else {
                "exit $exitCode" + message.ifBlank { "" }.let { if (it.isBlank()) "" else ": $it" }
            }
            is Refused -> reason
            is Unavailable -> reason
        }
}

/**
 * One permission change, with the evidence either side of it.
 *
 * The verdict comes from comparing [before] and [after], never from [outcome] alone.
 */
data class GrantStep(
    val permission: RequiredPermission,
    val before: PermissionGrant,
    val after: PermissionGrant,
    val outcome: SetupOutcome?,
    val verdict: Verdict,
    val detail: String,
) {
    enum class Verdict {
        /** Was denied, is now granted. */
        CHANGED,

        /** Already held before we touched anything; nothing was executed. */
        ALREADY_HELD,

        /** Was granted, is now denied. The successful outcome of a revoke. */
        REMOVED,

        /** The command failed, or ran and the permission did not change. */
        FAILED,
    }

    val succeeded: Boolean
        get() = verdict == Verdict.CHANGED || verdict == Verdict.ALREADY_HELD ||
            verdict == Verdict.REMOVED
}

/**
 * Executes typed setup actions with elevated identity.
 *
 * An interface so the whole setup flow — including every failure path — runs on the JVM
 * against fakes. The only production implementation routes through the Shizuku
 * `UserService`; there is no ADB implementation, because BattInsight never runs `adb`
 * itself. When the user takes the manual route they run the commands, and BattInsight only
 * renders and verifies them.
 */
interface SetupExecutor {

    /** Whether a privileged backend is available to execute anything right now. */
    suspend fun isReady(): Boolean

    /** Attempts one action. Never throws for an ordinary failure. */
    suspend fun execute(action: SetupAction): SetupOutcome
}

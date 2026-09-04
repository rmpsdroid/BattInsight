package com.rmpsdroid.battinsight.setup

import com.rmpsdroid.battinsight.permissions.RequiredPermission

/**
 * The exact commands a user runs from a computer to set up or remove access.
 *
 * Generated from [SetupAction], so the manual route and the Shizuku route can never drift
 * apart: both are the same three permissions against the same fixed package, and adding a
 * command here without adding an action is not possible.
 *
 * ## What must never appear here
 *
 * `BATTERY_STATS` — measured unnecessary in Phase 1B, yet both predecessor applications
 * told users to grant it. `INTERACT_ACROSS_USERS_FULL` — named by the platform in its
 * denial message but not grantable to an ordinary application, so instructing someone to
 * try would waste their time. Any `appops` command — the app-op does not need forcing, and
 * a user pasting an app-op change they did not understand is a worse outcome than a
 * permission they explicitly approved.
 *
 * `ManualAdbInstructionsTest` asserts all three absences against the rendered text.
 *
 * BattInsight never executes `adb` itself. It has no way to: `adb` runs on a computer, and
 * the application deliberately holds no mechanism for running arbitrary commands. It
 * renders these, and verifies the result afterwards.
 */
object ManualAdbInstructions {

    /** The three grant commands, in the order the platform demands them. */
    val grantCommands: List<String> get() = SetupAction.grants.map { it.adbCommand }

    /** The three revoke commands. */
    val revokeCommands: List<String> get() = SetupAction.revokes.map { it.adbCommand }

    /** One clipboard-ready block, newline separated. */
    fun grantBlock(): String = grantCommands.joinToString("\n")

    fun revokeBlock(): String = revokeCommands.joinToString("\n")

    /** What each command is for, so the user is not pasting something opaque. */
    fun explanations(): List<Pair<String, String>> =
        SetupAction.grants.map { it.adbCommand to it.permission.plainLanguage }

    /**
     * Plain-language descriptions of the three permissions.
     *
     * Deliberately free of implementation vocabulary. A user deciding whether to elevate an
     * application's privileges needs to understand what it will be able to read, not how
     * the request is transported.
     */
    private val RequiredPermission.plainLanguage: String
        get() = when (this) {
            RequiredPermission.DUMP ->
                "Lets BattInsight read Android's own diagnostic reports, including the " +
                    "battery statistics service."
            RequiredPermission.PACKAGE_USAGE_STATS ->
                "Lets BattInsight see how long apps have been used, which the battery " +
                    "statistics path needs in order to attribute usage."
            RequiredPermission.INTERACT_ACROSS_USERS ->
                "Required by Android's battery statistics service itself, which asks for " +
                    "data across user profiles even on a device with only one profile."
        }
}

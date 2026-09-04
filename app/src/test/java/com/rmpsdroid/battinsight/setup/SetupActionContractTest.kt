package com.rmpsdroid.battinsight.setup

import com.rmpsdroid.battinsight.BuildConfig
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security boundary around state-changing operations.
 *
 * `SetupAction` is the only thing in the application that can change device state, and it
 * runs with shell identity. These assertions are the guarantee that it stays as narrow as
 * it was designed to be — that the set cannot quietly grow a seventh member, point at
 * another package, or grow an argument a caller could influence.
 */
class SetupActionContractTest {

    // ---- 19-20. only known identifiers resolve ----

    @Test
    fun `an unknown identifier resolves to nothing`() {
        listOf(
            "grant_battery_stats",
            "grant_dump ",
            "GRANT_DUMP",
            "",
            "unknown",
            "grant_interact_across_users_full",
        ).forEach {
            assertNull("'$it' must not resolve to an action", SetupAction.forId(it))
        }
    }

    @Test
    fun `a null identifier resolves to nothing`() {
        assertNull(SetupAction.forId(null))
    }

    /**
     * Command-shaped input, the way an attacker or a bug would supply it. None of these is
     * an identifier, so none resolves, so none can reach a process.
     */
    @Test
    fun `command-shaped input cannot masquerade as an action identifier`() {
        listOf(
            "pm grant com.rmpsdroid.battinsight android.permission.DUMP",
            "sh -c pm grant com.example android.permission.DUMP",
            "grant_dump; pm grant com.example android.permission.DUMP",
            "/system/bin/pm grant com.example android.permission.DUMP",
            "pm grant com.example.other android.permission.DUMP",
            "appops set com.rmpsdroid.battinsight GET_USAGE_STATS allow",
            "pm revoke com.android.settings android.permission.DUMP",
        ).forEach {
            assertNull("'$it' must not resolve to an action", SetupAction.forId(it))
        }
    }

    @Test
    fun `every action resolves by its own identifier and identifiers are unique`() {
        val ids = SetupAction.all.map { it.id }
        assertEquals("identifiers must be unique", ids.size, ids.toSet().size)
        SetupAction.all.forEach {
            assertEquals(it, SetupAction.forId(it.id))
        }
    }

    // ---- exactly six actions, three of each ----

    @Test
    fun `there are exactly three grant targets and three revoke targets`() {
        assertEquals(3, SetupAction.grants.size)
        assertEquals(3, SetupAction.revokes.size)
        assertEquals(6, SetupAction.all.size)

        assertEquals(
            RequiredPermission.entries.toSet(),
            SetupAction.grants.map { it.permission }.toSet(),
        )
        assertEquals(
            RequiredPermission.entries.toSet(),
            SetupAction.revokes.map { it.permission }.toSet(),
        )
    }

    @Test
    fun `grants run in the order the platform was measured to demand`() {
        assertEquals(
            listOf(
                RequiredPermission.DUMP,
                RequiredPermission.PACKAGE_USAGE_STATS,
                RequiredPermission.INTERACT_ACROSS_USERS,
            ),
            SetupAction.grants.map { it.permission },
        )
    }

    // ---- the target package is fixed ----

    @Test
    fun `the target package is BattInsight's own, at compile time`() {
        assertEquals(
            "the fixed target must match the package actually being built",
            BuildConfig.APPLICATION_ID,
            SetupAction.TARGET_PACKAGE,
        )
    }

    @Test
    fun `every action names only BattInsight's own package`() {
        SetupAction.all.forEach { action ->
            assertEquals(
                "${action.id} must target BattInsight and nothing else",
                SetupAction.TARGET_PACKAGE,
                action.argv[2],
            )
            assertEquals(1, action.argv.count { it == SetupAction.TARGET_PACKAGE })
        }
    }

    // ---- the argument vector is fixed and minimal ----

    @Test
    fun `every argument vector is exactly pm, a verb, the package and a permission`() {
        SetupAction.all.forEach { action ->
            assertEquals("${action.id} must have four arguments", 4, action.argv.size)
            assertEquals("/system/bin/pm", action.argv[0])
            assertTrue(
                "${action.id} verb must be grant or revoke",
                action.argv[1] == "grant" || action.argv[1] == "revoke",
            )
            assertEquals(action.permission.manifestName, action.argv[3])
        }
    }

    @Test
    fun `no argument vector contains a shell, an operator or an app-op`() {
        val forbidden = listOf(
            "sh", "-c", "su", "&&", "||", ";", "|", "$", "`", "appops", "settings",
            "install", "uninstall", "reset-permissions", "--user",
        )
        SetupAction.all.forEach { action ->
            action.argv.forEach { arg ->
                forbidden.forEach { bad ->
                    assertTrue(
                        "${action.id} argument '$arg' must not contain '$bad'",
                        !arg.contains(bad) || arg == "/system/bin/pm",
                    )
                }
            }
        }
    }

    @Test
    fun `no action targets a permission outside the measured minimum set`() {
        val allowed = RequiredPermission.entries.map { it.manifestName }.toSet()
        SetupAction.all.forEach {
            assertTrue(
                "${it.id} targets ${it.permission.manifestName}, which is not in the measured set",
                it.permission.manifestName in allowed,
            )
        }
        // The two the platform mentions but that must never be requested.
        val named = SetupAction.all.map { it.permission.manifestName }
        assertTrue(
            "BATTERY_STATS was measured unnecessary and must never be granted",
            named.none { it == RequiredPermission.NOT_REQUIRED_BATTERY_STATS },
        )
        assertTrue(
            "INTERACT_ACROSS_USERS_FULL is not grantable and must never be attempted",
            named.none { it.endsWith("INTERACT_ACROSS_USERS_FULL") },
        )
    }

    // ---- 15-18. the manual instructions match, exactly ----

    @Test
    fun `the manual instructions contain exactly three grant commands`() {
        assertEquals(3, ManualAdbInstructions.grantCommands.size)
        assertEquals(3, ManualAdbInstructions.grantBlock().lines().size)
    }

    @Test
    fun `each manual command is an adb pm grant for BattInsight`() {
        ManualAdbInstructions.grantCommands.forEachIndexed { index, command ->
            assertEquals(
                "adb shell pm grant ${SetupAction.TARGET_PACKAGE} " +
                    SetupAction.grants[index].permission.manifestName,
                command,
            )
        }
    }

    @Test
    fun `the manual instructions mention no BATTERY_STATS`() {
        val text = ManualAdbInstructions.grantBlock() + ManualAdbInstructions.revokeBlock() +
            ManualAdbInstructions.explanations().joinToString(" ") { it.first + " " + it.second }
        assertTrue(
            "BATTERY_STATS must not appear: both predecessors told users to grant it",
            !text.contains("BATTERY_STATS"),
        )
    }

    @Test
    fun `the manual instructions mention no INTERACT_ACROSS_USERS_FULL`() {
        val text = ManualAdbInstructions.grantBlock() + ManualAdbInstructions.revokeBlock()
        assertTrue(
            "_FULL is named by the platform but is not grantable to an ordinary app",
            !text.contains("INTERACT_ACROSS_USERS_FULL"),
        )
    }

    @Test
    fun `the manual instructions contain no appops command`() {
        val text = (ManualAdbInstructions.grantBlock() + ManualAdbInstructions.revokeBlock() +
            ManualAdbInstructions.explanations().joinToString(" ") { it.first + " " + it.second })
            .lowercase()
        assertTrue("no appops", !text.contains("appops"))
        assertTrue("no settings put", !text.contains("settings put"))
        assertTrue("no su", !text.contains(" su "))
    }

    @Test
    fun `the manual revoke commands are the same three permissions`() {
        assertEquals(3, ManualAdbInstructions.revokeCommands.size)
        assertTrue(ManualAdbInstructions.revokeCommands.all { it.startsWith("adb shell pm revoke ") })
        assertEquals(
            RequiredPermission.entries.map { it.manifestName }.toSet(),
            ManualAdbInstructions.revokeCommands.map { it.substringAfterLast(' ') }.toSet(),
        )
    }

    @Test
    fun `every manual command is explained in plain language`() {
        ManualAdbInstructions.explanations().forEach { (command, explanation) ->
            assertTrue(command.isNotBlank())
            assertTrue("'$command' needs an explanation", explanation.length > 40)
            // Implementation vocabulary belongs in diagnostics, not on the setup path.
            listOf("SELinux", "Binder", "AIDL", "uid=", "argv").forEach { jargon ->
                assertTrue(
                    "explanation should avoid '$jargon': $explanation",
                    !explanation.contains(jargon),
                )
            }
        }
    }
}

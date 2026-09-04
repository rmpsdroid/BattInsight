package com.rmpsdroid.battinsight.shizuku

import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.setup.SetupAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Shizuku backend's two load-bearing properties.
 *
 * 1. There is no reflective route to the private `Shizuku.newProcess`. That method is
 *    `private static` in the official API and documented as "planned to be removed from
 *    Shizuku API 14"; reaching it by reflection was a development shortcut, not a
 *    production dependency, and must not reappear.
 * 2. What crosses the Binder is a probe *identifier*, resolved against the same sealed
 *    whitelist the application uses. An arbitrary command must have no way through.
 *
 * The first is checked against the source text rather than the bytecode, because the point
 * is to fail a future edit at review time with a legible reason.
 */
class ShizukuBackendContractTest {

    @Test
    fun `production source contains no reflection route into Shizuku internals`() {
        val offences = mutableListOf<String>()
        productionSources().forEach { file ->
            strippedOfComments(file.readText()).lineSequence().forEachIndexed { index, line ->
                FORBIDDEN.forEach { token ->
                    if (line.contains(token)) {
                        offences.add(file.name + ":" + (index + 1) + " contains '" + token + "' -> " + line.trim())
                    }
                }
            }
        }
        assertTrue(
            "Reflection into Shizuku internals must not return.\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `the only Shizuku process mechanism referenced is bindUserService`() {
        val code = productionSources().joinToString("\n") { strippedOfComments(it.readText()) }
        assertTrue("bindUserService must be the execution mechanism", code.contains("Shizuku.bindUserService"))
        assertTrue("bound services must be released", code.contains("Shizuku.unbindUserService"))
        assertTrue("newProcess must not appear in code", !code.contains("newProcess"))
    }

    @Test
    fun `every whitelisted probe resolves by identifier`() {
        ProbeCommand.all.forEach { command ->
            val resolved = ProbeCommand.all.firstOrNull { it.id == command.id }
            assertNotNull("probe '" + command.id + "' must resolve by its own id", resolved)
            assertEquals(command.argv, resolved!!.argv)
        }
    }

    @Test
    fun `probe identifiers are unique so resolution is unambiguous`() {
        val ids = ProbeCommand.all.map { it.id }
        assertEquals("probe ids must be unique", ids.size, ids.toSet().size)
    }

    /**
     * The remote side resolves an identifier and refuses anything else. These are the shapes
     * an attacker-controlled or buggy caller would try: a raw command line, a shell
     * invocation, a chained command, an absolute path. None of them is a probe id, so none
     * of them resolves, so none of them reaches a process.
     */
    @Test
    fun `a command string cannot masquerade as a probe identifier`() {
        val attempts = listOf(
            "sh -c id",
            "dumpsys batterystats --reset",
            "id; rm -rf /",
            "/system/bin/sh",
            "dumpsys batterystats --proto",
            "",
            "IDENTITY",
        )
        attempts.forEach { attempt ->
            assertNull(
                "'" + attempt + "' must not resolve to a whitelisted probe",
                ProbeCommand.all.firstOrNull { it.id == attempt },
            )
        }
    }

    @Test
    fun `no whitelisted probe can mutate battery statistics state`() {
        ProbeCommand.all.forEach { command ->
            command.argv.forEach { arg ->
                ProbeCommand.forbiddenArguments.forEach { forbidden ->
                    assertTrue(
                        "probe '" + command.id + "' must not carry '" + forbidden + "'",
                        !arg.contains(forbidden),
                    )
                }
            }
        }
    }

    /**
     * Nothing in the production source may execute something a caller composed.
     *
     * Phase 4 added the only state-changing path in the application, so this scan widened
     * with it. The two `pm` operations are permitted because they are not free-form: their
     * argument vectors are built by [SetupAction] from compile-time constants, which
     * `SetupActionContractTest` verifies separately.
     *
     * String literals are matched with their surrounding quotes where the bare word would
     * be ambiguous — `su` appears inside `suspend` on nearly every line of this codebase.
     */
    @Test
    fun `production source contains no arbitrary execution surface`() {
        val code = productionSources().joinToString("\n") { strippedOfComments(it.readText()) }
        val q = '"'

        // Note "-c" is deliberately absent: it is a legitimate argument of
        // `dumpsys batterystats -c`, the checkin probe. What matters is a shell being
        // invoked with it, which the "sh" literal and the "sh -c" substring both catch.
        val quotedLiterals = listOf("sh", "su", "adb")
        quotedLiterals.forEach { literal ->
            val quoted = q + literal + q
            assertTrue(
                "production code must not contain the literal " + quoted,
                !code.contains(quoted),
            )
        }

        val substrings = listOf(
            "sh -c",
            "adb root",
            "appops",
            "settings put",
            "pm install",
            "pm uninstall",
            "QUERY_ALL_PACKAGES",
            "android.permission.INTERNET",
            // The state-changing dumpsys arguments are deliberately absent from this list:
            // they appear in ProbeCommand.forbiddenArguments, which is the deny-list that
            // prevents them. `no whitelisted probe can mutate battery statistics state`
            // asserts them against every argv, which is the check that actually matters.
        )
        substrings.forEach { forbidden ->
            assertTrue(
                "production code must not contain '" + forbidden + "'",
                !code.contains(forbidden),
            )
        }
    }

    /**
     * `BATTERY_STATS` must never be *requested*, which is not the same as never being
     * *mentioned*.
     *
     * `RequiredPermission.NOT_REQUIRED_BATTERY_STATS` names it deliberately: Phase 1B
     * measured acquisition succeeding with it denied, and both predecessor applications
     * told users to grant it anyway, so the constant exists to stop it being reintroduced
     * by assumption. A blunt text search would flag exactly the safeguard that prevents the
     * mistake, so the assertion is about the places that would actually ask for it.
     */
    @Test
    fun `BATTERY_STATS is never requested anywhere it would take effect`() {
        val requestable =
            ProbeCommand.all.flatMap { it.argv } + SetupAction.all.flatMap { it.argv } +
                SetupAction.all.map { it.permission.manifestName } +
                SetupAction.all.map { it.adbCommand }

        assertTrue(
            "no executable path may name BATTERY_STATS",
            requestable.none { it.contains("BATTERY_STATS") },
        )

        val manifest = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/AndroidManifest.xml") }
            .firstOrNull { it.isFile }
        assertNotNull("could not locate the manifest", manifest)
        val declarations = manifest!!.readText()
            .lineSequence()
            .filter { it.contains("<uses-permission") || it.contains("android:name=") }
            .filter { !it.trimStart().startsWith("<!--") }
            .joinToString("\n")
        assertTrue(
            "BATTERY_STATS must not be declared in the manifest",
            !declarations.contains("android.permission.BATTERY_STATS"),
        )
        assertTrue(
            "INTERACT_ACROSS_USERS_FULL must appear only as the provider guard",
            declarations.lineSequence()
                .filter { it.contains("INTERACT_ACROSS_USERS_FULL") }
                .all { it.contains("android:permission=") },
        )
    }

    @Test
    fun `only whitelisted argument vectors are executable`() {
        // Every argv element in the application comes from one of exactly two whitelists.
        val fromWhitelists =
            (ProbeCommand.all.flatMap { it.argv } + SetupAction.all.flatMap { it.argv }).toSet()

        assertTrue("the probe whitelist must not be empty", ProbeCommand.all.isNotEmpty())
        assertTrue("the setup whitelist must not be empty", SetupAction.all.isNotEmpty())
        assertTrue(
            "pm is the only absolute executable path any whitelist may name",
            fromWhitelists.filter { it.startsWith("/") }.all { it == SetupAction.PM_PATH },
        )
        assertTrue(
            "no whitelisted argument may name a shell",
            fromWhitelists.none { it == "sh" || it == "su" || it.endsWith("/sh") },
        )
    }

    @Test
    fun `setup actions target only BattInsight's own package`() {
        SetupAction.all.forEach {
            assertEquals(
                it.id + " must target BattInsight and nothing else",
                SetupAction.TARGET_PACKAGE,
                it.argv[2],
            )
        }
    }

    private fun productionSources(): List<File> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/rmpsdroid/battinsight") }
            .firstOrNull { it.isDirectory }
        assertNotNull("could not locate production sources from " + File("").absolutePath, root)
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * Removes comments so that documenting *why* `newProcess` was abandoned does not itself
     * trip the check. Adequate for Kotlin source we control; not a general parser.
     */
    private fun strippedOfComments(source: String): String {
        val out = StringBuilder(source.length)
        val backslash = '\\'
        val quote = '"'
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i++
                }
                inLine -> if (c == '\n') {
                    inLine = false
                    out.append(c)
                }
                inString -> {
                    out.append(c)
                    if (c == quote && source.getOrNull(i - 1) != backslash) inString = false
                }
                c == '/' && next == '*' -> {
                    inBlock = true
                    i++
                }
                c == '/' && next == '/' -> {
                    inLine = true
                    i++
                }
                c == quote -> {
                    inString = true
                    out.append(c)
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private companion object {
        val FORBIDDEN = listOf(
            "newProcess",
            "getDeclaredMethod",
            "getDeclaredField",
            "setAccessible",
            "ShizukuRemoteProcess",
            "Class.forName",
        )
    }
}

package com.rmpsdroid.battinsight.shizuku

import com.rmpsdroid.battinsight.collection.ProbeCommand
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

package com.rmpsdroid.battinsight.persistence

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules about the persistence layer that only source can answer.
 *
 * These read the tree rather than call an API, because what they check is the *absence* of
 * things -- a destructive fallback that must never be added, a Room import that must never
 * reach the pure engine. Absences have no runtime surface to assert against, and a comment
 * saying "do not add this" is not a test.
 */
class PersistencePolicyTest {

    private val moduleDir: File = File("").absoluteFile.let { cwd ->
        // Gradle runs unit tests with the module as working directory, but that is a default
        // rather than a guarantee. Walk up to whichever ancestor actually holds the sources.
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/com/rmpsdroid/battinsight").isDirectory }
            ?: error("could not locate the app module from $cwd")
    }

    private val mainSources: List<File>
        get() = File(moduleDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * A schema change must never be permission to delete a user's measurements.
     *
     * The predecessor's history was destroyed by an update. Room offers exactly one switch
     * that reproduces that, and this is what stops anybody flipping it to make a failing
     * migration go away.
     */
    @Test
    fun `no destructive migration fallback exists anywhere in production code`() {
        // Comment lines are excluded deliberately: the database class explains at length
        // why the call is absent, and a check that cannot tell an explanation from a call
        // would punish documenting the decision.
        val offenders = mainSources.filter { file ->
            file.readLines().any { line ->
                val code = line.trim()
                val isComment = code.startsWith("//") || code.startsWith("*") ||
                    code.startsWith("/*")
                !isComment && code.contains("fallbackToDestructiveMigration")
            }
        }
        assertEquals(
            "destructive migration deletes user data on upgrade and is never acceptable here",
            emptyList<File>(),
            offenders.map { it.relativeTo(moduleDir) },
        )
    }

    /**
     * The session engine stays pure.
     *
     * Its lifecycle rules are the part of this application most worth testing, and they are
     * only cheap to test while the package has no Android or database dependency. One
     * convenient import would end that, so the seam is asserted rather than intended.
     */
    @Test
    fun `the session package imports no database or Android machinery`() {
        val banned = listOf(
            "androidx.room",
            "android.database",
            "android.content",
            "com.rmpsdroid.battinsight.persistence",
        )
        val sessionSources = File(moduleDir, "src/main/java/com/rmpsdroid/battinsight/session")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }

        val violations = sessionSources.flatMap { file ->
            file.readLines()
                .filter { line -> line.startsWith("import ") && banned.any { line.contains(it) } }
                .map { "${file.name}: ${it.trim()}" }
        }.toList()

        assertEquals(
            "the session engine must remain pure Kotlin, testable without a device",
            emptyList<String>(),
            violations,
        )
    }

    /**
     * Every database version ships the schema it was built from.
     *
     * A migration can only be written, reviewed or tested against an exported schema. If a
     * version bump ever lands without one, that evidence is gone permanently -- the schema
     * cannot be reconstructed after the fact from a later version of the code.
     */
    @Test
    fun `the current database version has an exported schema`() {
        val schema = File(
            moduleDir,
            "schemas/com.rmpsdroid.battinsight.persistence.BattInsightDatabase/" +
                "${BattInsightDatabase.DATABASE_VERSION}.json",
        )
        assertTrue(
            "expected an exported schema at ${schema.path}; is exportSchema still true?",
            schema.isFile,
        )
        assertTrue(
            "the exported schema must declare the version it was exported for",
            schema.readText().contains("\"version\": ${BattInsightDatabase.DATABASE_VERSION}"),
        )
    }

    /**
     * Every version from 1 up to the current one is present, with none skipped.
     *
     * A gap means a version shipped whose schema was never exported, and therefore a
     * migration path that cannot be validated.
     */
    @Test
    fun `no schema version is missing between one and the current version`() {
        val dir = File(
            moduleDir,
            "schemas/com.rmpsdroid.battinsight.persistence.BattInsightDatabase",
        )
        val present = dir.listFiles()?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }?.toSet()
            ?: emptySet()
        val expected = (1..BattInsightDatabase.DATABASE_VERSION).toSet()
        assertEquals("exported schemas must be contiguous", expected, expected intersect present)
    }

    /**
     * The delta engine stays pure.
     *
     * Comparison policy is the part of this phase most worth testing exhaustively, and it is
     * only cheap to test while it has no database dependency. One convenient import would end
     * that, so the seam is asserted rather than intended.
     */
    @Test
    fun `the batterystats package imports no Room or database machinery`() {
        val banned = listOf("androidx.room", "androidx.room3", "android.database", "battinsight.persistence")
        val sources = File(moduleDir, "src/main/java/com/rmpsdroid/battinsight/batterystats")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }

        val violations = sources.flatMap { file ->
            file.readLines()
                .filter { line -> line.startsWith("import ") && banned.any { line.contains(it) } }
                .map { "${file.name}: ${it.trim()}" }
        }.toList()

        assertEquals(
            "the decoder and delta engine must remain testable without a device",
            emptyList<String>(),
            violations,
        )
    }

    /**
     * No column anywhere holds a privileged payload.
     *
     * The privacy claim this project makes is that the raw batterystats output is decoded and
     * discarded. A column named for it would be the quiet way that stops being true, so the
     * entity definitions are read rather than trusted.
     */
    @Test
    fun `no entity declares a raw payload column`() {
        val banned = listOf("payload_text", "raw_payload", "payload_body", "stdout", "raw_output")
        val entities = File(moduleDir, "src/main/java/com/rmpsdroid/battinsight/persistence")
            .walkTopDown().filter { it.isFile && it.name.endsWith("Entities.kt") }

        val violations = entities.flatMap { file ->
            file.readLines().filter { line -> banned.any { line.contains(it) } }
                .map { "${file.name}: ${it.trim()}" }
        }.toList()

        assertEquals(emptyList<String>(), violations)

        // And the one payload-shaped column that does exist holds a digest, not content.
        val counterEntities = File(
            moduleDir, "src/main/java/com/rmpsdroid/battinsight/persistence/CounterEntities.kt",
        ).readText()
        assertTrue(
            "payload_hash is a digest and payload_byte_count is a size; neither is content",
            counterEntities.contains("payload_hash") && counterEntities.contains("payload_byte_count"),
        )
    }

    /**
     * Package names are not persisted alongside counters.
     *
     * A numeric UID is a far weaker statement about a person's device than a durable list of
     * the applications on it. The decoder still reads `uid` records for live display; nothing
     * writes them to disk, and this asserts the counter tables have no column for them.
     */
    @Test
    fun `no counter table stores a package name`() {
        val counterEntities = File(
            moduleDir, "src/main/java/com/rmpsdroid/battinsight/persistence/CounterEntities.kt",
        ).readText()

        listOf("package_name", "packageName", "uid_package").forEach {
            assertTrue(
                "counter storage must not carry package attribution: found '$it'",
                !counterEntities.contains(it),
            )
        }
    }

    /**
     * Every shipped database version keeps its exported schema.
     *
     * Deleting an old one would delete the only evidence a migration can be validated against.
     */
    @Test
    fun `schema one and schema two are both committed`() {
        val dir = File(moduleDir, "schemas/com.rmpsdroid.battinsight.persistence.BattInsightDatabase")
        assertTrue("schema 1 must not be deleted when 2 arrives", File(dir, "1.json").isFile)
        assertTrue(File(dir, "2.json").isFile)
        assertEquals(2, BattInsightDatabase.DATABASE_VERSION)
    }
}

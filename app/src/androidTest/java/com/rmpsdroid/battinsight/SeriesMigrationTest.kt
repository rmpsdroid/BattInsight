package com.rmpsdroid.battinsight

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.persistence.ALL_MIGRATIONS
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import com.rmpsdroid.battinsight.persistence.MIGRATION_1_2
import com.rmpsdroid.battinsight.persistence.MIGRATION_2_3
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrating a real version 2 database to version 3.
 *
 * v3 is the first migration in this project that is not purely additive: the two counter child
 * tables are rebuilt to replace their text identity columns with a foreign key into
 * `wakelock_identity`. SQLite cannot retype a column in place, so it is the standard
 * create-copy-drop-rename recipe, and a rebuild is exactly where rows get lost.
 *
 * So the assertion is row-for-row rather than a count: every stored counter must still be
 * present, with the same value, and its identity must resolve back to the same `(uid, name)`
 * it had before. This project exists because an update once destroyed every user's history.
 *
 * **Not tested, deliberately:** applying `Migration(2,3)` twice to an already-v3 database. That
 * would fail at `CREATE TABLE wakelock_identity`, Room never does it, and asserting it would be
 * testing a scenario that cannot occur. What is tested instead is the migration against
 * several independently constructed v2 databases, and reopening each result through the
 * production builder -- which is what actually catches a schema that drifted from `3.json`.
 */
@RunWith(AndroidJUnit4::class)
class SeriesMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = BattInsightDatabase::class,
    )

    @Before
    @After
    fun removeAnyPreviousDatabase() {
        val f = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB)
        listOf(f, File("${f.path}-wal"), File("${f.path}-shm")).forEach { it.delete() }
    }

    // ------------------------------------------------------------------ the matrix, A..F

    /** A. An empty v2 database still migrates. */
    @Test
    fun anEmptyDatabaseMigrates() = runBlocking {
        helper.createDatabase(2).close()

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { migrated ->
            assertEquals(0L, migrated.countOf("wakelock_identity"))
            assertEquals(0L, migrated.countOf("battery_sample"))
            migrated.assertForeignKeysIntact()
        }
    }

    /** B. Sessions and snapshots but no counters at all. */
    @Test
    fun metadataOnlyDatabaseKeepsEverySessionRow() = runBlocking {
        helper.createDatabase(2).use { v2 ->
            v2.execSQL(SNAPSHOT_INSERT)
            v2.execSQL(SESSION_INSERT)
            v2.execSQL(ENGINE_STATE_INSERT)
        }

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { migrated ->
            assertEquals("the session survives", 1L, migrated.countOf("battery_sessions"))
            assertEquals("the snapshot survives", 1L, migrated.countOf("battery_snapshots"))
            assertEquals("the engine state survives", 1L, migrated.countOf("engine_state"))
            assertEquals(73L, migrated.longOf("SELECT level FROM battery_snapshots LIMIT 1"))
            assertEquals("nothing to intern", 0L, migrated.countOf("wakelock_identity"))
            assertNull(
                "a session that never evicted anything has no watermark",
                migrated.nullableLongOf(
                    "SELECT battery_samples_evicted_through_elapsed_millis " +
                        "FROM battery_sessions LIMIT 1",
                ),
            )
            migrated.assertForeignKeysIntact()
        }
    }

    /** C. A single baseline capture, with counters. */
    @Test
    fun aBaselineOnlyDatabaseMigratesItsCounters() = runBlocking {
        helper.createDatabase(2).use { v2 ->
            v2.execSQL(SNAPSHOT_INSERT)
            v2.execSQL(SESSION_INSERT)
            v2.execSQL(captureInsert("cap-1", SESSION_ID, 1000))
            v2.execSQL(kernelInsert("cap-1", "bluetooth", 1000, 10))
            v2.execSQL(partialInsert("cap-1", 10123, "*job*/com.x", 500, 2))
            v2.execSQL(stateInsert(SESSION_ID, "cap-1", "cap-1"))
        }

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { migrated ->
            assertEquals(1L, migrated.countOf("kernel_wakelock_counter"))
            assertEquals(1L, migrated.countOf("partial_wakelock_counter"))
            assertEquals("one kernel identity and one partial", 2L, migrated.countOf("wakelock_identity"))
            migrated.assertKernelRow("cap-1", "bluetooth", 1000, 10)
            migrated.assertPartialRow("cap-1", 10123, "*job*/com.x", 500, 2)
            migrated.assertForeignKeysIntact()
        }
    }

    /** D. Baseline and latest with many counters on both. */
    @Test
    fun aFullSessionMigratesEveryCounterRow() = runBlocking {
        helper.createDatabase(2).use { v2 ->
            v2.execSQL(SNAPSHOT_INSERT)
            v2.execSQL(SESSION_INSERT)
            listOf("cap-1" to 1000L, "cap-2" to 2000L).forEach { (id, elapsed) ->
                v2.execSQL(captureInsert(id, SESSION_ID, elapsed))
                repeat(20) { i -> v2.execSQL(kernelInsert(id, "k$i", 100L + i, i.toLong())) }
                repeat(20) { i -> v2.execSQL(partialInsert(id, 10000 + i, "p$i", 200L + i, i.toLong())) }
            }
            v2.execSQL(stateInsert(SESSION_ID, "cap-1", "cap-2"))
        }

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { migrated ->
            assertEquals("every kernel row survives", 40L, migrated.countOf("kernel_wakelock_counter"))
            assertEquals("every partial row survives", 40L, migrated.countOf("partial_wakelock_counter"))
            // 20 distinct kernel names + 20 distinct (uid, name) pairs, each interned once
            // however many captures used them.
            assertEquals("identities are interned, not duplicated", 40L, migrated.countOf("wakelock_identity"))
            migrated.assertKernelRow("cap-2", "k7", 107, 7)
            migrated.assertPartialRow("cap-1", 10007, "p7", 207, 7)
            migrated.assertForeignKeysIntact()
        }
    }

    /** E. Several sessions, one carrying a counter decrease. */
    @Test
    fun multipleSessionsIncludingACounterDecreaseMigrate() = runBlocking {
        helper.createDatabase(2).use { v2 ->
            v2.execSQL(SNAPSHOT_INSERT)
            v2.execSQL(SESSION_INSERT)
            v2.execSQL(SECOND_SNAPSHOT_INSERT)
            v2.execSQL(SECOND_SESSION_INSERT)

            v2.execSQL(captureInsert("a1", SESSION_ID, 1000))
            v2.execSQL(kernelInsert("a1", "shared", 100, 5))
            v2.execSQL(captureInsert("a2", SESSION_ID, 2000))
            // The decrease. Migration must carry it across unaltered -- "fixing" it would
            // destroy the evidence that Android's accounting restarted.
            v2.execSQL(kernelInsert("a2", "shared", 50, 2))
            v2.execSQL(stateInsert(SESSION_ID, "a1", "a2"))

            v2.execSQL(captureInsert("b1", SECOND_SESSION_ID, 1000))
            v2.execSQL(kernelInsert("b1", "shared", 900, 9))
            v2.execSQL(stateInsert(SECOND_SESSION_ID, "b1", "b1"))
        }

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { migrated ->
            assertEquals(3L, migrated.countOf("counter_capture"))
            assertEquals(3L, migrated.countOf("kernel_wakelock_counter"))
            assertEquals(
                "one identity shared by three captures across two sessions",
                1L,
                migrated.countOf("wakelock_identity"),
            )
            migrated.assertKernelRow("a1", "shared", 100, 5)
            migrated.assertKernelRow("a2", "shared", 50, 2)
            migrated.assertKernelRow("b1", "shared", 900, 9)
            migrated.assertForeignKeysIntact()
        }
    }

    /** F. The pathological identities a real Android 16 capture actually contains. */
    @Test
    fun pathologicalIdentitiesSurviveTheRebuild() = runBlocking {
        val long = "WorkManager:TikTokListenableWorker startWork -> " + "a.b.c.Component".repeat(25)
        helper.createDatabase(2).use { v2 ->
            v2.execSQL(SNAPSHOT_INSERT)
            v2.execSQL(SESSION_INSERT)
            v2.execSQL(captureInsert("cap-1", SESSION_ID, 1000))
            // Measured on a real device: one kernel wakelock has an empty name, names contain
            // commas, and the longest partial name is 423 characters.
            v2.execSQL(kernelInsert("cap-1", "", 1, 1))
            v2.execSQL(kernelInsert("cap-1", "has,commas,inside", 2, 2))
            v2.execSQL(partialInsert("cap-1", 10001, long, 3, 3))
            v2.execSQL(stateInsert(SESSION_ID, "cap-1", "cap-1"))
        }

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { migrated ->
            assertEquals(2L, migrated.countOf("kernel_wakelock_counter"))
            assertEquals(1L, migrated.countOf("partial_wakelock_counter"))
            migrated.assertKernelRow("cap-1", "", 1, 1)
            migrated.assertKernelRow("cap-1", "has,commas,inside", 2, 2)
            migrated.assertPartialRow("cap-1", 10001, long, 3, 3)
            assertTrue(
                "the long name is stored whole",
                (migrated.textOf(
                    "SELECT name FROM wakelock_identity WHERE family = 'PARTIAL'",
                )?.length ?: 0) > 400,
            )
            migrated.assertForeignKeysIntact()
        }
    }

    // ------------------------------------------------------- reopening through production

    /**
     * The migrated database opens through the real builder and accepts a battery sample.
     *
     * This is the load-bearing assertion of the whole file. Room validates the live schema
     * against the committed `3.json` on open and throws if they differ, so a hand-written
     * migration that "works" but leaves a different index set or column order fails here --
     * which a row-count assertion would never catch.
     */
    @Test
    fun theMigratedDatabaseOpensThroughProductionRoomAndAcceptsASample() = runBlocking {
        helper.createDatabase(2).use { v2 ->
            v2.execSQL(SNAPSHOT_INSERT)
            v2.execSQL(SESSION_INSERT)
            v2.execSQL(captureInsert("cap-1", SESSION_ID, 1000))
            v2.execSQL(kernelInsert("cap-1", "bluetooth", 1000, 10))
            v2.execSQL(stateInsert(SESSION_ID, "cap-1", "cap-1"))
        }
        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BattInsightDatabase::class.java,
            TEST_DB,
        ).addMigrations(*ALL_MIGRATIONS).build()

        try {
            val dao = db.batterySampleDao()
            assertEquals("the series starts empty", 0, dao.countFor(SESSION_ID))

            db.useWriterConnection { connection ->
                connection.exec(
                    "INSERT INTO battery_sample (sample_id, session_id, " +
                        "sample_elapsed_realtime_millis, sample_wall_clock_millis, " +
                        "sample_utc_offset_minutes, boot_kind, boot_kernel_id, " +
                        "boot_derived_millis, level, scale, battery_status, plug_source, " +
                        "temperature_deci_celsius, voltage_milli_volts, " +
                        "charge_counter_micro_amp_hours, trigger, counter_generation) " +
                        "VALUES ('s1', '$SESSION_ID', 1000, 1700000001000, 330, 'KERNEL', " +
                        "'boot-v2', NULL, 73, 100, 'DISCHARGING', 'NONE', 251, 4123, " +
                        "3210000, 'PERIODIC', 3)",
                )
            }
            assertEquals("and accepts a sample", 1, dao.countFor(SESSION_ID))

            // The migrated counter data is readable through the interned join, not merely
            // present as rows.
            val stats = db.counterDao().resolvedKernelWakelocks("cap-1")
            assertEquals("bluetooth", stats.single().name)
            assertEquals(1000L, stats.single().totalDurationMillis)
        } finally {
            db.close()
        }
    }

    /** A migrated database can also be cleared, which is where v3's new keys interact. */
    @Test
    fun theMigratedDatabaseCanBeClearedCompletely() = runBlocking {
        helper.createDatabase(2).use { v2 ->
            v2.execSQL(SNAPSHOT_INSERT)
            v2.execSQL(SESSION_INSERT)
            v2.execSQL(ENGINE_STATE_INSERT)
            v2.execSQL(captureInsert("cap-1", SESSION_ID, 1000))
            v2.execSQL(kernelInsert("cap-1", "bluetooth", 1000, 10))
            v2.execSQL(partialInsert("cap-1", 10123, "*job*/com.x", 500, 2))
            v2.execSQL(stateInsert(SESSION_ID, "cap-1", "cap-1"))
        }
        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BattInsightDatabase::class.java,
            TEST_DB,
        ).addMigrations(*ALL_MIGRATIONS).build()

        try {
            db.sessionDao().clearAll()

            db.useWriterConnection { connection ->
                listOf(
                    "engine_state", "session_counter_state", "kernel_wakelock_counter",
                    "partial_wakelock_counter", "counter_capture", "battery_sample",
                    "battery_sessions", "battery_snapshots", "wakelock_identity",
                ).forEach { table ->
                    val n = connection.usePrepared("SELECT COUNT(*) FROM $table") {
                        if (it.step()) it.getLong(0) else -1L
                    }
                    assertEquals("$table must be empty after clear", 0L, n)
                }
                val violations = connection.usePrepared("PRAGMA foreign_key_check") {
                    var count = 0
                    while (it.step()) count++
                    count
                }
                assertEquals("no foreign key violations after clear", 0, violations)
            }
        } finally {
            db.close()
        }
    }

    // --------------------------------------------------------------------------- helpers

    /** `useWriterConnection` yields a PooledConnection, which prepares rather than execs. */
    private suspend fun androidx.room3.PooledConnection.exec(sql: String) {
        usePrepared(sql) { it.step() }
    }

    private fun SQLiteConnection.assertKernelRow(
        captureId: String,
        name: String,
        millis: Long,
        count: Long,
    ) {
        val escaped = name.replace("'", "''")
        assertEquals(
            "kernel row ($captureId, $name) must keep its duration",
            millis,
            longOf(
                "SELECT k.total_duration_millis FROM kernel_wakelock_counter k " +
                    "JOIN wakelock_identity i ON i.identity_id = k.identity_id " +
                    "WHERE k.capture_id = '$captureId' AND i.family = 'KERNEL' " +
                    "AND i.uid = -1 AND i.name = '$escaped'",
            ),
        )
        assertEquals(
            count,
            longOf(
                "SELECT k.count FROM kernel_wakelock_counter k " +
                    "JOIN wakelock_identity i ON i.identity_id = k.identity_id " +
                    "WHERE k.capture_id = '$captureId' AND i.name = '$escaped'",
            ),
        )
    }

    private fun SQLiteConnection.assertPartialRow(
        captureId: String,
        uid: Int,
        name: String,
        millis: Long,
        count: Long,
    ) {
        val escaped = name.replace("'", "''")
        assertEquals(
            "partial row ($captureId, $uid) must keep its duration",
            millis,
            longOf(
                "SELECT p.total_duration_millis FROM partial_wakelock_counter p " +
                    "JOIN wakelock_identity i ON i.identity_id = p.identity_id " +
                    "WHERE p.capture_id = '$captureId' AND i.family = 'PARTIAL' " +
                    "AND i.uid = $uid AND i.name = '$escaped'",
            ),
        )
        assertEquals(
            count,
            longOf(
                "SELECT p.count FROM partial_wakelock_counter p " +
                    "JOIN wakelock_identity i ON i.identity_id = p.identity_id " +
                    "WHERE p.capture_id = '$captureId' AND i.uid = $uid",
            ),
        )
    }

    private fun SQLiteConnection.assertForeignKeysIntact() {
        prepare("PRAGMA foreign_key_check").use {
            var violations = 0
            while (it.step()) violations++
            assertEquals("migration must leave no dangling references", 0, violations)
        }
    }

    private fun SQLiteConnection.countOf(table: String): Long =
        prepare("SELECT COUNT(*) FROM $table").use { if (it.step()) it.getLong(0) else -1L }

    private fun SQLiteConnection.longOf(sql: String): Long =
        prepare(sql).use { if (it.step()) it.getLong(0) else -1L }

    private fun SQLiteConnection.nullableLongOf(sql: String): Long? =
        prepare(sql).use { if (it.step() && !it.isNull(0)) it.getLong(0) else null }

    private fun SQLiteConnection.textOf(sql: String): String? =
        prepare(sql).use { if (it.step()) it.getText(0) else null }

    private fun captureInsert(captureId: String, sessionId: String, elapsed: Long) =
        "INSERT INTO counter_capture " +
            "(capture_id, battery_session_id, battery_snapshot_id, source_format, " +
            " source_format_version, backend_kind, record_format_version, checkin_version, " +
            " parcel_version, platform_start_fingerprint, platform_end_fingerprint, " +
            " platform_changed, capture_elapsed_realtime_millis, capture_wall_clock_millis, " +
            " counter_generation, boot_kind, boot_kernel_id, boot_derived_millis, " +
            " payload_byte_count, payload_hash, warning_count, checkin_version_verified) " +
            "VALUES ('$captureId', '$sessionId', NULL, 'CHECKIN', 9, 'SHELL', 9, 36, 215, " +
            " 'BUILD.A', 'BUILD.A', 0, $elapsed, 1700000000000, 3, 'KERNEL', 'boot-x', NULL, " +
            " 900000, NULL, 0, 1)"

    private fun kernelInsert(captureId: String, name: String, millis: Long, count: Long) =
        "INSERT INTO kernel_wakelock_counter " +
            "(capture_id, accounting_window, name, total_duration_millis, count) " +
            "VALUES ('$captureId', 'SINCE_CHARGED', '${name.replace("'", "''")}', $millis, $count)"

    private fun partialInsert(
        captureId: String,
        uid: Int,
        name: String,
        millis: Long,
        count: Long,
    ) = "INSERT INTO partial_wakelock_counter " +
        "(capture_id, accounting_window, uid, name, total_duration_millis, count) " +
        "VALUES ('$captureId', 'SINCE_CHARGED', $uid, '${name.replace("'", "''")}', " +
        "$millis, $count)"

    private fun stateInsert(sessionId: String, baseline: String, latest: String) =
        "INSERT INTO session_counter_state " +
            "(battery_session_id, baseline_capture_id, latest_capture_id) " +
            "VALUES ('$sessionId', '$baseline', '$latest')"

    private companion object {
        const val TEST_DB = "series-migration-test.db"
        const val SESSION_ID = "11111111-1111-1111-1111-111111111111"
        const val SNAPSHOT_ID = "22222222-2222-2222-2222-222222222222"
        const val SECOND_SESSION_ID = "33333333-3333-3333-3333-333333333333"
        const val SECOND_SNAPSHOT_ID = "44444444-4444-4444-4444-444444444444"

        private fun snapshotInsert(snapshotId: String, sessionId: String, boot: String) =
            "INSERT INTO battery_snapshots " +
                "(snapshot_id, session_id, boot_kind, boot_kernel_id, boot_derived_millis, " +
                " elapsed_realtime_millis, wall_clock_millis, utc_offset_minutes, trigger, " +
                " observation_trigger, battery_status, plug_source, battery_health, level, " +
                " scale, present, temperature_deci_celsius, voltage_milli_volts, " +
                " charge_counter_micro_amp_hours, counter_generation, snapshot_schema_version, " +
                " counter_source, platform_version_at_capture, app_version_at_capture) " +
                "VALUES ('$snapshotId', '$sessionId', 'KERNEL', '$boot', NULL, 5000, " +
                " 1700000005000, 330, 'APP_START', 'BATTERY_CHANGED', 'DISCHARGING', 'NONE', " +
                " 'GOOD', 73, 100, 1, 251, 4123, 3210000, 3, 1, 'NONE', '16', '0.0.1')"

        private fun sessionInsert(sessionId: String, snapshotId: String) =
            "INSERT INTO battery_sessions " +
                "(session_id, session_type, start_snapshot_id, latest_snapshot_id, " +
                " end_snapshot_id, end_reason, counter_generation) " +
                "VALUES ('$sessionId', 'DISCHARGE', '$snapshotId', '$snapshotId', NULL, " +
                " 'NONE', 3)"

        val SNAPSHOT_INSERT = snapshotInsert(SNAPSHOT_ID, SESSION_ID, "boot-v2")
        val SESSION_INSERT = sessionInsert(SESSION_ID, SNAPSHOT_ID)
        val SECOND_SNAPSHOT_INSERT = snapshotInsert(SECOND_SNAPSHOT_ID, SECOND_SESSION_ID, "boot-v2")
        val SECOND_SESSION_INSERT = sessionInsert(SECOND_SESSION_ID, SECOND_SNAPSHOT_ID)

        val ENGINE_STATE_INSERT = """
            INSERT INTO engine_state (id, session_id, last_accepted_snapshot_id, counter_generation)
            VALUES (1, '$SESSION_ID', '$SNAPSHOT_ID', 7)
        """.trimIndent()
    }
}

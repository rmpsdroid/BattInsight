package com.rmpsdroid.battinsight

import androidx.room3.PooledConnection
import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import com.rmpsdroid.battinsight.persistence.MIGRATION_1_2
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrating a real version 1 database to version 2.
 *
 * The starting point is the **committed** v1 schema, not one this test invents. That is the
 * whole value of exporting schemas: a migration validated against a schema regenerated from
 * today's code would only prove the migration agrees with itself.
 *
 * What must be true afterwards is simple and absolute: **every row a user already had is still
 * there.** Version 2 adds four tables and touches nothing else. This project exists because an
 * update once destroyed every user's history, so the migration test asserts survival of each
 * v1 table individually rather than trusting that "additive" was implemented as intended.
 */
@RunWith(AndroidJUnit4::class)
class CounterMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = BattInsightDatabase::class,
    )

    /**
     * Room 3's helper takes an explicit path and does not clear it, so a database left by an
     * earlier run makes `createDatabase` fail with "Creation of tables didn't occur".
     */
    @Before
    fun removeAnyPreviousDatabase() {
        val f = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB)
        listOf(f, File("${f.path}-wal"), File("${f.path}-shm")).forEach { it.delete() }
    }

    @Test
    fun migratingFromVersionOnePreservesEverySessionRow() = runBlocking {
        // --- a v1 database with real content -------------------------------------------
        helper.createDatabase(1).use { v1 ->
            v1.execSQL(SNAPSHOT_INSERT)
            v1.execSQL(SESSION_INSERT)
            v1.execSQL(ENGINE_STATE_INSERT)
        }

        // --- migrate, and let Room validate the result against the committed v2 schema --
        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { migrated ->
            assertEquals("the session survives", 1L, migrated.countOf("battery_sessions"))
            assertEquals("the snapshot survives", 1L, migrated.countOf("battery_snapshots"))
            assertEquals("the engine state survives", 1L, migrated.countOf("engine_state"))

            // Not merely present -- unaltered.
            assertEquals(
                SESSION_ID,
                migrated.textOf("SELECT session_id FROM battery_sessions LIMIT 1"),
            )
            assertEquals(
                "DISCHARGE",
                migrated.textOf("SELECT session_type FROM battery_sessions LIMIT 1"),
            )
            assertEquals(
                "the snapshot's measurements are untouched",
                73L,
                migrated.longOf("SELECT level FROM battery_snapshots LIMIT 1"),
            )
            assertEquals(
                7L,
                migrated.longOf("SELECT counter_generation FROM engine_state LIMIT 1"),
            )

            // --- and the new tables exist, empty ---------------------------------------
            listOf(
                "counter_capture",
                "kernel_wakelock_counter",
                "partial_wakelock_counter",
                "session_counter_state",
            ).forEach {
                assertEquals("$it must exist and be empty", 0L, migrated.countOf(it))
            }
        }
    }

    /**
     * The migrated database is usable, not merely valid.
     *
     * Room's own validation compares schemas. This opens the migrated file through the normal
     * production builder and writes counter data into it, which is the thing a user's device
     * will actually do on the first launch after the upgrade.
     */
    @Test
    fun theMigratedDatabaseAcceptsCounterData() = runBlocking {
        helper.createDatabase(1).use { v1 ->
            v1.execSQL(SNAPSHOT_INSERT)
            v1.execSQL(SESSION_INSERT)
        }
        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BattInsightDatabase::class.java,
            TEST_DB,
        ).addMigrations(MIGRATION_1_2).build()

        db.useWriterConnection { connection ->
            connection.exec(
                """
                INSERT INTO counter_capture
                    (capture_id, battery_session_id, battery_snapshot_id, source_format,
                     source_format_version, backend_kind, record_format_version, checkin_version,
                     parcel_version, platform_start_fingerprint, platform_end_fingerprint,
                     platform_changed, capture_elapsed_realtime_millis, capture_wall_clock_millis,
                     counter_generation, boot_kind, boot_kernel_id, boot_derived_millis,
                     payload_byte_count, payload_hash, warning_count, checkin_version_verified)
                VALUES ('cap-1', '$SESSION_ID', NULL, 'CHECKIN', 9, 'SHELL', 9, 36, 215,
                        'BUILD.A', 'BUILD.A', 0, 1000, 1700000000000, 1, 'KERNEL', 'boot-x',
                        NULL, 900000, NULL, 0, 1)
                """.trimIndent(),
            )
            connection.exec(
                "INSERT INTO kernel_wakelock_counter " +
                    "(capture_id, accounting_window, name, total_duration_millis, count) " +
                    "VALUES ('cap-1', 'SINCE_CHARGED', 'bt_read', 681038, 678)",
            )
            connection.exec(
                "INSERT INTO session_counter_state " +
                    "(battery_session_id, baseline_capture_id, latest_capture_id) " +
                    "VALUES ('$SESSION_ID', 'cap-1', 'cap-1')",
            )
        }

        val state = db.counterDao().state(SESSION_ID)
        assertEquals("cap-1", state!!.baselineCaptureId)
        assertEquals(1, db.counterDao().kernelWakelocks("cap-1").size)
        assertEquals("and the v1 session is still there", 1, db.sessionDao().sessionCount())
        db.close()
    }

    /**
     * The counter tables inherit the schema's foreign-key discipline.
     *
     * Verified on the platform rather than assumed: a capture naming a session that does not
     * exist must be refused, or the bounded-retention model could accumulate orphans.
     */
    @Test
    fun foreignKeysAreEnforcedOnTheMigratedDatabase() = runBlocking {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BattInsightDatabase::class.java,
            TEST_DB,
        ).addMigrations(MIGRATION_1_2).build()

        db.useWriterConnection { connection ->
            assertEquals(1L, connection.readLong("PRAGMA foreign_keys"))
            assertEquals("immediate, not deferred", 0L, connection.readLong("PRAGMA defer_foreign_keys"))

            val refused = runCatching {
                connection.exec(
                    "INSERT INTO counter_capture " +
                        "(capture_id, battery_session_id, battery_snapshot_id, source_format, " +
                        " source_format_version, backend_kind, record_format_version, checkin_version, " +
                        " parcel_version, platform_start_fingerprint, platform_end_fingerprint, " +
                        " platform_changed, capture_elapsed_realtime_millis, capture_wall_clock_millis, " +
                        " counter_generation, boot_kind, boot_kernel_id, boot_derived_millis, " +
                        " payload_byte_count, payload_hash, warning_count, checkin_version_verified) " +
                        "VALUES ('orphan', 'no-such-session', NULL, 'CHECKIN', 9, 'SHELL', 9, 36, 215, " +
                        " 'B', 'B', 0, 0, 0, 1, 'UNKNOWN', NULL, NULL, 0, NULL, 0, 1)",
                )
            }
            assertTrue("a capture must not outlive its session", refused.isFailure)
        }
        db.close()
    }

    // ------------------------------------------------------------------------ helpers

    private suspend fun PooledConnection.exec(sql: String) {
        usePrepared(sql) { it.step() }
    }

    private suspend fun PooledConnection.readLong(sql: String): Long =
        usePrepared(sql) { if (it.step()) it.getLong(0) else -1L }

    private fun SQLiteConnection.countOf(table: String): Long =
        prepare("SELECT COUNT(*) FROM $table").use { if (it.step()) it.getLong(0) else -1L }

    private fun SQLiteConnection.longOf(sql: String): Long =
        prepare(sql).use { if (it.step()) it.getLong(0) else -1L }

    private fun SQLiteConnection.textOf(sql: String): String? =
        prepare(sql).use { if (it.step()) it.getText(0) else null }

    private companion object {
        const val TEST_DB = "counter-migration-test.db"
        const val SESSION_ID = "11111111-1111-1111-1111-111111111111"
        const val SNAPSHOT_ID = "22222222-2222-2222-2222-222222222222"

        val SNAPSHOT_INSERT = """
            INSERT INTO battery_snapshots
                (snapshot_id, session_id, boot_kind, boot_kernel_id, boot_derived_millis,
                 elapsed_realtime_millis, wall_clock_millis, utc_offset_minutes, trigger,
                 observation_trigger, battery_status, plug_source, battery_health, level, scale,
                 present, temperature_deci_celsius, voltage_milli_volts,
                 charge_counter_micro_amp_hours, counter_generation, snapshot_schema_version,
                 counter_source, platform_version_at_capture, app_version_at_capture)
            VALUES ('$SNAPSHOT_ID', '$SESSION_ID', 'KERNEL', 'boot-v1', NULL,
                    5000, 1700000005000, 330, 'APP_START', 'BATTERY_CHANGED', 'DISCHARGING',
                    'NONE', 'GOOD', 73, 100, 1, 251, 4123, 3210000, 3, 1, 'NONE', '16', '0.0.1')
        """.trimIndent()

        val SESSION_INSERT = """
            INSERT INTO battery_sessions
                (session_id, session_type, start_snapshot_id, latest_snapshot_id,
                 end_snapshot_id, end_reason, counter_generation)
            VALUES ('$SESSION_ID', 'DISCHARGE', '$SNAPSHOT_ID', '$SNAPSHOT_ID', NULL, 'NONE', 3)
        """.trimIndent()

        val ENGINE_STATE_INSERT = """
            INSERT INTO engine_state
                (id, session_id, last_accepted_snapshot_id, counter_generation)
            VALUES (0, '$SESSION_ID', '$SNAPSHOT_ID', 7)
        """.trimIndent()
    }
}

package com.rmpsdroid.battinsight

import androidx.room3.PooledConnection
import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration harness, standing up against real SQLite on a real platform.
 *
 * At version 1 there is nothing to migrate, and that is precisely why this exists now. A
 * migration harness written for the first time alongside the first migration is a harness
 * whose own correctness is unproven at the moment it is most needed -- any failure then is
 * ambiguous between a bad migration and a bad test. Building it while the answer is known
 * makes the next version's result trustworthy.
 *
 * What it proves today: the exported schema is present and readable, Room can create a
 * database from it, and the schema Room validates against matches the one the entities
 * generate. Drift between the committed schema and the code fails here.
 *
 * Room 3 rewrote this API. The helper now takes a database file and an explicit
 * `SQLiteDriver`, its methods suspend, and they hand back an `SQLiteConnection` rather than
 * a `SupportSQLiteDatabase`.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB),
        // The framework driver, not the bundled one. The bundled driver ships its own SQLite
        // build, so it would answer questions about *that* SQLite rather than about the one
        // on the user's device -- and every foreign-key measurement in this project was taken
        // against the platform's.
        driver = AndroidSQLiteDriver(),
        databaseClass = BattInsightDatabase::class,
    )

    /**
     * Removes any database left behind by a previous run.
     *
     * Room 2's `MigrationTestHelper` managed this file itself. Room 3's takes an explicit path
     * and does not, so a leftover from an earlier run makes `createDatabase` fail with
     * "Creation of tables didn't occur while creating a new database" -- which is exactly what
     * happened the first time this ran on a device that still had the Room 2 test database.
     *
     * The journal siblings matter too: deleting only the main file can leave a `-wal` that
     * SQLite replays into a database the test believes is empty.
     */
    @Before
    fun removeAnyPreviousDatabase() {
        val file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB)
        listOf(file, File("${'$'}{file.path}-wal"), File("${'$'}{file.path}-shm")).forEach { it.delete() }
    }

    @Test
    fun theCurrentSchemaCreatesAndValidates() = runBlocking {
        helper.createDatabase(BattInsightDatabase.DATABASE_VERSION).close()

        // No migrations to apply, so this is purely a validation of the committed schema
        // against what the entities declare. Room throws if they differ in any column,
        // index or foreign key.
        val validated = helper.runMigrationsAndValidate(
            BattInsightDatabase.DATABASE_VERSION,
            emptyList(),
        )
        validated.close()
    }

    /**
     * Foreign keys must actually be on, and immediate, on a real platform database.
     *
     * Not a pedantic check. Deferred constraints were measured to throw on commit while
     * leaving rows written -- the exact partial commit the schema exists to prevent -- and the
     * fix was to break the reference cycle so the checks could stay immediate. That fix is
     * only worth anything if the platform honours it.
     *
     * Deliberately not using the migration helper's connection: it is opened without the
     * pragmas Room sets when it builds a database, so it reports `foreign_keys = 0` and
     * enforces nothing. An earlier version of this test asserted against that and was
     * measuring the helper rather than the application. What ships is a Room-built database,
     * so that is what is examined here.
     */
    @Test
    fun foreignKeysAreEnforcedOnTheRealPlatform() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BattInsightDatabase::class.java,
        ).build()

        db.useWriterConnection { connection ->
            assertEquals(
                "Room must open the database with foreign keys enabled",
                1L,
                connection.readLong("PRAGMA foreign_keys"),
            )
            assertEquals(
                "and they must be immediate, not deferred",
                0L,
                connection.readLong("PRAGMA defer_foreign_keys"),
            )

            // A session naming a snapshot that does not exist must be refused outright.
            val refused = runCatching {
                connection.exec(
                    """
                    INSERT INTO battery_sessions
                        (session_id, session_type, start_snapshot_id, latest_snapshot_id,
                         end_snapshot_id, end_reason, counter_generation)
                    VALUES ('s1', 'DISCHARGE', 'missing', 'missing', NULL, 'NONE', 1)
                    """.trimIndent(),
                )
            }
            assertTrue("the platform must refuse a dangling reference", refused.isFailure)

            // And the direction deliberately left unconstrained stays unconstrained: a
            // snapshot may name a session that is not stored, because adding that key back
            // would close the reference cycle and force deferred checking again.
            connection.exec(
                """
                INSERT INTO battery_snapshots
                    (snapshot_id, session_id, boot_kind, boot_kernel_id, boot_derived_millis,
                     elapsed_realtime_millis, wall_clock_millis, utc_offset_minutes, trigger,
                     observation_trigger, battery_status, plug_source, battery_health, level,
                     scale, present, temperature_deci_celsius, voltage_milli_volts,
                     charge_counter_micro_amp_hours, counter_generation,
                     snapshot_schema_version, counter_source, platform_version_at_capture,
                     app_version_at_capture)
                VALUES ('snap-1', 'no-such-session', 'UNKNOWN', NULL, NULL,
                        0, 0, 0, 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', NULL,
                        NULL, NULL, NULL, NULL, NULL, 1, 1, 'NONE', NULL, NULL)
                """.trimIndent(),
            )
        }

        db.close()
    }

    /**
     * Room 3 hands out a [PooledConnection] rather than a raw connection, so statements are
     * prepared through it rather than executed on it.
     */
    private suspend fun PooledConnection.readLong(sql: String): Long =
        usePrepared(sql) { if (it.step()) it.getLong(0) else -1L }

    private suspend fun PooledConnection.exec(sql: String) {
        usePrepared(sql) { it.step() }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}

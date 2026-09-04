package com.rmpsdroid.battinsight

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration harness, standing up against the real SQLite on a real platform.
 *
 * At version 1 there is nothing to migrate, and that is precisely why this exists now. A
 * migration harness written for the first time alongside the first migration is a harness
 * whose own correctness is unproven at the moment it is most needed -- any failure then is
 * ambiguous between a bad migration and a bad test. Building it while the answer is known
 * makes the next version's result trustworthy.
 *
 * What it proves today: the exported schema is present and readable as an asset, Room can
 * create a database from it, and the schema Room validates against matches the one the
 * entities generate. A drift between the committed schema and the code fails here.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BattInsightDatabase::class.java,
    )

    @Test
    fun theCurrentSchemaCreatesAndValidates() {
        val db = helper.createDatabase(TEST_DB, BattInsightDatabase.DATABASE_VERSION)
        db.close()

        // No migrations to apply, so this is purely a validation of the committed schema
        // against what the entities declare. Room throws if they differ in any column,
        // index or foreign key.
        val validated = helper.runMigrationsAndValidate(
            TEST_DB,
            BattInsightDatabase.DATABASE_VERSION,
            true,
        )
        assertTrue(validated.isOpen)
        validated.close()
    }

    /**
     * Foreign keys must actually be on, and immediate, on a real platform database.
     *
     * Not a pedantic check. Deferred constraints were measured on the JVM to throw on commit
     * while leaving rows written -- the exact partial commit the schema exists to prevent --
     * and the fix was to break the reference cycle so the checks could stay immediate. That
     * fix is only worth anything if the platform honours it.
     *
     * Deliberately *not* using the migration helper's handle. That one is opened raw, without
     * the pragmas Room sets when it builds a database, so it reports `foreign_keys = 0` and
     * enforces nothing -- an earlier version of this test asserted against it and was
     * measuring the helper rather than the application. What ships is a Room-built database,
     * so that is what is examined here.
     */
    @Test
    fun foreignKeysAreEnforcedOnTheRealPlatform() {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BattInsightDatabase::class.java,
        ).build()

        val raw = db.openHelper.writableDatabase
        raw.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Room must open the database with foreign keys enabled", 1, cursor.getInt(0))
        }
        raw.query("PRAGMA defer_foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("and they must be immediate, not deferred", 0, cursor.getInt(0))
        }

        // A session naming a snapshot that does not exist must be refused outright.
        val refused = runCatching {
            raw.execSQL(
                """
                INSERT INTO battery_sessions
                    (session_id, session_type, start_snapshot_id, latest_snapshot_id,
                     end_snapshot_id, end_reason, counter_generation)
                VALUES ('s1', 'DISCHARGE', 'missing', 'missing', NULL, 'NONE', 1)
                """.trimIndent(),
            )
        }
        assertTrue("the platform must refuse a dangling reference", refused.isFailure)

        // And the direction that was deliberately left unconstrained stays unconstrained:
        // a snapshot may name a session that is not stored, because adding that key back
        // would close the reference cycle and force deferred checking again.
        raw.execSQL(
            """
            INSERT INTO battery_snapshots
                (snapshot_id, session_id, boot_kind, boot_kernel_id, boot_derived_millis,
                 elapsed_realtime_millis, wall_clock_millis, utc_offset_minutes, trigger,
                 observation_trigger, battery_status, plug_source, battery_health, level,
                 scale, present, temperature_deci_celsius, voltage_milli_volts,
                 charge_counter_micro_amp_hours, counter_generation, snapshot_schema_version,
                 counter_source, platform_version_at_capture, app_version_at_capture)
            VALUES ('snap-1', 'no-such-session', 'UNKNOWN', NULL, NULL,
                    0, 0, 0, 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', NULL,
                    NULL, NULL, NULL, NULL, NULL, 1, 1, 'NONE', NULL, NULL)
            """.trimIndent(),
        )

        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}

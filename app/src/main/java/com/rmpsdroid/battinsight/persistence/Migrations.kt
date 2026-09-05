package com.rmpsdroid.battinsight.persistence

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Schema migrations.
 *
 * Written by hand rather than auto-generated, and each one is tested from the *committed*
 * schema of the version it starts at. An auto-migration would be shorter; it would also be a
 * migration nobody has read, and this project's founding failure was an update that destroyed
 * every user's history.
 *
 * The rule that governs all of them: **existing data is never touched.** A migration adds
 * structure. If it ever needs to rewrite a row, that is a separate decision requiring its own
 * evidence, and there is no destructive fallback to fall back to.
 */

/**
 * Adds durable counter storage. Purely additive.
 *
 * Four new tables and their indices. The two capture references on `session_counter_state`
 * are **composite**, carrying the session id alongside the capture id, so a state row can only
 * ever point at captures belonging to its own battery session. A single-column key would prove
 * only that the capture exists, not whose it is, and would let a delta be computed across two
 * different sessions.
 *
 * Not one statement touches `battery_sessions`, `battery_snapshots` or `engine_state`: a
 * device upgrading from v1 keeps every session and snapshot it had, and simply gains empty
 * counter tables.
 *
 * The SQL is written to match what Room generates for the entities exactly -- column order,
 * types, nullability, foreign keys and index names. Room validates the result against the
 * exported v2 schema on open and throws if they differ, so a mismatch here fails loudly at
 * the first launch after upgrade rather than quietly later. The migration test exercises that
 * validation from the committed v1 schema.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `counter_capture` (
                `capture_id` TEXT NOT NULL,
                `battery_session_id` TEXT NOT NULL,
                `battery_snapshot_id` TEXT,
                `source_format` TEXT NOT NULL,
                `source_format_version` INTEGER,
                `backend_kind` TEXT NOT NULL,
                `record_format_version` INTEGER NOT NULL,
                `checkin_version` INTEGER NOT NULL,
                `parcel_version` INTEGER NOT NULL,
                `platform_start_fingerprint` TEXT NOT NULL,
                `platform_end_fingerprint` TEXT NOT NULL,
                `platform_changed` INTEGER NOT NULL,
                `capture_elapsed_realtime_millis` INTEGER NOT NULL,
                `capture_wall_clock_millis` INTEGER NOT NULL,
                `counter_generation` INTEGER NOT NULL,
                `boot_kind` TEXT NOT NULL,
                `boot_kernel_id` TEXT,
                `boot_derived_millis` INTEGER,
                `payload_byte_count` INTEGER NOT NULL,
                `payload_hash` TEXT,
                `warning_count` INTEGER NOT NULL,
                `checkin_version_verified` INTEGER NOT NULL,
                PRIMARY KEY(`capture_id`),
                FOREIGN KEY(`battery_session_id`) REFERENCES `battery_sessions`(`session_id`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_counter_capture_battery_session_id` " +
                "ON `counter_capture` (`battery_session_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_counter_capture_battery_snapshot_id` " +
                "ON `counter_capture` (`battery_snapshot_id`)",
        )
        // The parent key for session_counter_state's composite references. Unique because
        // SQLite requires a foreign key's parent columns to be uniquely indexed; capture_id
        // is already unique alone, so this constrains nothing new.
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_counter_capture_battery_session_id_capture_id` " +
                "ON `counter_capture` (`battery_session_id`, `capture_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `kernel_wakelock_counter` (
                `capture_id` TEXT NOT NULL,
                `accounting_window` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `total_duration_millis` INTEGER NOT NULL,
                `count` INTEGER NOT NULL,
                PRIMARY KEY(`capture_id`, `accounting_window`, `name`),
                FOREIGN KEY(`capture_id`) REFERENCES `counter_capture`(`capture_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_kernel_wakelock_counter_capture_id` " +
                "ON `kernel_wakelock_counter` (`capture_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `partial_wakelock_counter` (
                `capture_id` TEXT NOT NULL,
                `accounting_window` TEXT NOT NULL,
                `uid` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `total_duration_millis` INTEGER NOT NULL,
                `count` INTEGER NOT NULL,
                PRIMARY KEY(`capture_id`, `accounting_window`, `uid`, `name`),
                FOREIGN KEY(`capture_id`) REFERENCES `counter_capture`(`capture_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_partial_wakelock_counter_capture_id` " +
                "ON `partial_wakelock_counter` (`capture_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `session_counter_state` (
                `battery_session_id` TEXT NOT NULL,
                `baseline_capture_id` TEXT NOT NULL,
                `latest_capture_id` TEXT NOT NULL,
                PRIMARY KEY(`battery_session_id`),
                FOREIGN KEY(`battery_session_id`) REFERENCES `battery_sessions`(`session_id`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(`battery_session_id`, `baseline_capture_id`)
                    REFERENCES `counter_capture`(`battery_session_id`, `capture_id`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(`battery_session_id`, `latest_capture_id`)
                    REFERENCES `counter_capture`(`battery_session_id`, `capture_id`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_session_counter_state_baseline_capture_id` " +
                "ON `session_counter_state` (`baseline_capture_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_session_counter_state_latest_capture_id` " +
                "ON `session_counter_state` (`latest_capture_id`)",
        )
    }
}

/**
 * Adds the sampled series, and interns wakelock identities. Non-destructive.
 *
 * Two things happen here, and only one of them is additive.
 *
 * **Additive:** `wakelock_identity`, `battery_sample`, and one nullable column on
 * `battery_sessions`.
 *
 * **A rebuild:** the two counter child tables lose their text identity columns and gain an
 * `identity_id`. SQLite cannot retype or drop a column in place across the versions this
 * project supports, so it is the standard create-copy-drop-rename recipe. Every existing row
 * is carried across by an exact join on the columns that were its primary key, so the copy
 * matches each row once and cannot merge two.
 *
 * Why rebuild at all: Phase 9A measured partial wakelock names at 79 characters on average
 * and 423 at the longest, rewritten in full on every capture. Interning them cut a capture
 * from 103.8 KB to 25.0 KB with nothing discarded.
 *
 * The tables keep their **names**. Renaming them to something like `kwl_sample` was drafted
 * and withdrawn: they are still per-capture cumulative counter rows, and a rename would have
 * forced another edit to the clear order in `SessionDao.clearAll` -- the exact method that
 * silently broke in Phase 7B and had to be repaired in 7B.2.
 *
 * `session_counter_state` is untouched. The ordered series is derived from `counter_capture`
 * rows, which already carry `battery_session_id` and a capture timestamp.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        // 1. The identity dictionary. AUTOINCREMENT because identities are swept: without it
        //    SQLite would reuse a deleted rowid and silently relabel a different wakelock.
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `wakelock_identity` (
                `identity_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `family` TEXT NOT NULL,
                `uid` INTEGER NOT NULL,
                `name` TEXT NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_wakelock_identity_family_uid_name` " +
                "ON `wakelock_identity` (`family`, `uid`, `name`)",
        )

        // 2. Backfill from what is already stored. DISTINCT because one identity legitimately
        //    appears in many captures; INSERT OR IGNORE so the unique index arbitrates.
        connection.execSQL(
            "INSERT OR IGNORE INTO `wakelock_identity` (`family`, `uid`, `name`) " +
                "SELECT DISTINCT 'KERNEL', -1, `name` FROM `kernel_wakelock_counter`",
        )
        connection.execSQL(
            "INSERT OR IGNORE INTO `wakelock_identity` (`family`, `uid`, `name`) " +
                "SELECT DISTINCT 'PARTIAL', `uid`, `name` FROM `partial_wakelock_counter`",
        )

        // 3. The v3 shapes, under temporary names.
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `kernel_wakelock_counter_new` (
                `capture_id` TEXT NOT NULL,
                `accounting_window` TEXT NOT NULL,
                `identity_id` INTEGER NOT NULL,
                `total_duration_millis` INTEGER NOT NULL,
                `count` INTEGER NOT NULL,
                PRIMARY KEY(`capture_id`, `accounting_window`, `identity_id`),
                FOREIGN KEY(`capture_id`) REFERENCES `counter_capture`(`capture_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`identity_id`) REFERENCES `wakelock_identity`(`identity_id`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `partial_wakelock_counter_new` (
                `capture_id` TEXT NOT NULL,
                `accounting_window` TEXT NOT NULL,
                `identity_id` INTEGER NOT NULL,
                `total_duration_millis` INTEGER NOT NULL,
                `count` INTEGER NOT NULL,
                PRIMARY KEY(`capture_id`, `accounting_window`, `identity_id`),
                FOREIGN KEY(`capture_id`) REFERENCES `counter_capture`(`capture_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`identity_id`) REFERENCES `wakelock_identity`(`identity_id`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )

        // 4. Carry every row across. The join is on exactly the columns that formed the old
        //    primary key, so it matches each row once -- no loss, no invention, no merge.
        connection.execSQL(
            """
            INSERT INTO `kernel_wakelock_counter_new`
                (`capture_id`, `accounting_window`, `identity_id`, `total_duration_millis`, `count`)
            SELECT k.`capture_id`, k.`accounting_window`, i.`identity_id`,
                   k.`total_duration_millis`, k.`count`
              FROM `kernel_wakelock_counter` k
              JOIN `wakelock_identity` i
                ON i.`family` = 'KERNEL' AND i.`uid` = -1 AND i.`name` = k.`name`
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `partial_wakelock_counter_new`
                (`capture_id`, `accounting_window`, `identity_id`, `total_duration_millis`, `count`)
            SELECT p.`capture_id`, p.`accounting_window`, i.`identity_id`,
                   p.`total_duration_millis`, p.`count`
              FROM `partial_wakelock_counter` p
              JOIN `wakelock_identity` i
                ON i.`family` = 'PARTIAL' AND i.`uid` = p.`uid` AND i.`name` = p.`name`
            """.trimIndent(),
        )

        // 5/6. Swap, then recreate the indices explicitly rather than relying on what a
        //      RENAME carries with it.
        connection.execSQL("DROP TABLE `kernel_wakelock_counter`")
        connection.execSQL("DROP TABLE `partial_wakelock_counter`")
        connection.execSQL(
            "ALTER TABLE `kernel_wakelock_counter_new` RENAME TO `kernel_wakelock_counter`",
        )
        connection.execSQL(
            "ALTER TABLE `partial_wakelock_counter_new` RENAME TO `partial_wakelock_counter`",
        )
        // Only the identity index. The v2 capture_id index is deliberately not recreated:
        // the v3 primary key already begins with capture_id, so SQLite serves the by-capture
        // query from the primary-key index with the same plan and the same measured time,
        // and the separate index cost 17-20% of these tables for nothing.
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_kernel_wakelock_counter_identity_id` " +
                "ON `kernel_wakelock_counter` (`identity_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_partial_wakelock_counter_identity_id` " +
                "ON `partial_wakelock_counter` (`identity_id`)",
        )

        // 7. The retention watermark. Null for every existing session, which is true: nothing
        //    has been evicted, because there were no samples to evict.
        connection.execSQL(
            "ALTER TABLE `battery_sessions` ADD COLUMN " +
                "`battery_samples_evicted_through_elapsed_millis` INTEGER DEFAULT NULL",
        )

        // 8. The series itself, starting empty. Deliberately not backfilled from boundary
        //    snapshots: v2 never sampled a series, and synthesising one would fabricate
        //    observations nobody made.
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `battery_sample` (
                `sample_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `sample_elapsed_realtime_millis` INTEGER NOT NULL,
                `sample_wall_clock_millis` INTEGER NOT NULL,
                `sample_utc_offset_minutes` INTEGER NOT NULL,
                `boot_kind` TEXT NOT NULL,
                `boot_kernel_id` TEXT,
                `boot_derived_millis` INTEGER,
                `level` INTEGER,
                `scale` INTEGER,
                `battery_status` TEXT NOT NULL,
                `plug_source` TEXT NOT NULL,
                `temperature_deci_celsius` INTEGER,
                `voltage_milli_volts` INTEGER,
                `charge_counter_micro_amp_hours` INTEGER,
                `trigger` TEXT NOT NULL,
                `counter_generation` INTEGER NOT NULL,
                PRIMARY KEY(`sample_id`),
                FOREIGN KEY(`session_id`) REFERENCES `battery_sessions`(`session_id`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_battery_sample_session_id_sample_elapsed_realtime_millis` " +
                "ON `battery_sample` (`session_id`, `sample_elapsed_realtime_millis`)",
        )
    }
}

/** Every migration this database ships, in order. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

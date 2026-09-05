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

/** Every migration this database ships, in order. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

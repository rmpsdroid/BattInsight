package com.rmpsdroid.battinsight.persistence

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase

/**
 * BattInsight's local database.
 *
 * Holds the battery session domain and nothing else. Collector tables -- wakelocks, alarms,
 * sensors, CPU, network -- are deliberately absent: they belong where the decoded models
 * exist, and creating them speculatively would commit a schema to data whose shape is not
 * yet known.
 *
 * ## Four version domains, and why this is only one of them
 *
 * | Version | Meaning | Where |
 * |---|---|---|
 * | Room database version | The shape of *these tables* | [DATABASE_VERSION] |
 * | Snapshot schema version | The shape of BattInsight's *domain* snapshot | on every row |
 * | Android platform version | The OS at capture | on every row |
 * | batterystats parcel/checkin version | Android's own counter format | not stored yet |
 *
 * They move independently and must never be compared with one another. Phase 1A measured
 * Android's parcel version going *down* between platforms -- 1310906 on Android 10 to 215 on
 * Android 16 -- so anything reasoning across domains by magnitude would already be wrong.
 *
 * ## No destructive migration
 *
 * [Room.databaseBuilder] here never calls `fallbackToDestructiveMigration`. A diagnostics
 * application's measurements are the user's data, and a schema change is not permission to
 * delete them. If a future migration cannot be performed the correct behaviour is to surface
 * the failure, which Room does by throwing on open, and which the store reports as
 * [com.rmpsdroid.battinsight.session.PersistenceOutcome.MIGRATION_FAILURE].
 *
 * The predecessor lost every user's history to an update. That happened because the data had
 * no schema to migrate; this one has, and the absence of a destructive fallback is asserted
 * by test.
 */
@Database(
    entities = [
        SnapshotEntity::class, SessionEntity::class, EngineStateEntity::class,
        CounterCaptureEntity::class, KernelWakelockCounterEntity::class,
        PartialWakelockCounterEntity::class, SessionCounterStateEntity::class,
    ],
    version = BattInsightDatabase.DATABASE_VERSION,
    exportSchema = true,
)
abstract class BattInsightDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun counterDao(): CounterDao

    companion object {
        /**
         * The Room schema version.
         *
         * Distinct from `SnapshotSchemaVersion`, which versions the domain object rather
         * than the tables. Both started at 1 and have already diverged: version 2 adds
         * durable counter storage without changing the snapshot model at all.
         */
        const val DATABASE_VERSION = 2

        const val DATABASE_NAME = "battinsight-sessions.db"

        @Volatile
        private var instance: BattInsightDatabase? = null

        /**
         * The one database instance for the application.
         *
         * A singleton because Room instances are expensive and because two open handles to
         * one file invite exactly the kind of divergence this phase exists to prevent.
         * Building one from a Composable or an Activity would do that on every rotation.
         */
        fun get(context: Context): BattInsightDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): BattInsightDatabase =
            Room.databaseBuilder(context, BattInsightDatabase::class.java, DATABASE_NAME)
                // Real migrations, written by hand and tested from the committed schema of
                // the version they start at.
                .addMigrations(*ALL_MIGRATIONS)
                // Deliberately absent: fallbackToDestructiveMigration(). See the class note.
                .build()

        // Deliberately no resetForTest(). One existed and nothing called it: the tests build
        // their own in-memory databases, which is what keeps them isolated. An unused hook
        // into production state is a backdoor waiting for a caller.
    }
}

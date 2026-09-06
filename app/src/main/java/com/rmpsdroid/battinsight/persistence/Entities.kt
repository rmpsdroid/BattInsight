package com.rmpsdroid.battinsight.persistence

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * The stored shape of the battery session domain.
 *
 * Explicit typed columns, never a blob. The predecessor stored its snapshots as one opaque
 * serialized object with no version, and an update destroyed every user's history; a schema
 * where identity and comparability are real columns can be inspected, migrated and reasoned
 * about, and that is the whole point of writing them out.
 *
 * These types are **not** the domain. The pure session engine knows nothing about Room, and
 * mapping between the two is explicit -- see `Mappers.kt`. Annotating the domain classes
 * directly would have coupled a pure Kotlin state machine to Android.
 *
 * ## Enum values are stored by name
 *
 * Not by ordinal. An ordinal silently changes meaning the moment somebody reorders an enum,
 * which would reinterpret stored history rather than fail to read it. A name that no longer
 * resolves is a [com.rmpsdroid.battinsight.session.PersistenceOutcome.MAPPING_FAILURE],
 * which is loud and recoverable.
 *
 * ## Foreign keys are immediate, and the reference cycle is broken to allow that
 *
 * Sessions name their snapshots and snapshots name their session, which is a cycle: no
 * insert order satisfies both under immediate constraints.
 *
 * The first attempt here deferred the checks to commit, which made the cycle expressible.
 * It was measured and abandoned: a deferred violation inside a Room `@Transaction` threw
 * `SQLiteConstraintException` **and left the offending rows committed** -- one session and an
 * engine-state row survived a write that had failed. That is the exact partial commit this
 * schema exists to prevent, dressed up as integrity.
 *
 * So the cycle is broken instead. Enforced, immediately:
 *
 *   battery_sessions.start/latest/end_snapshot_id -> battery_snapshots
 *   engine_state.session_id                       -> battery_sessions
 *   engine_state.last_accepted_snapshot_id        -> battery_snapshots
 *
 * giving the write order snapshots -> sessions -> engine state, which is a topological sort
 * of the remaining graph.
 *
 * `battery_snapshots.session_id` keeps its column and index but carries no constraint,
 * because adding it back would restore the cycle. The direction that was kept is the one
 * that matters: a stored session can always be rebuilt, because the snapshots it names are
 * guaranteed to exist. The reverse -- an orphaned snapshot -- costs a row and loses nothing,
 * and is set from the owning session inside the same transaction anyway.
 */

/**
 * One immutable observation, stored.
 *
 * Both triggers are kept. `trigger` is why the *snapshot* was taken and `observationTrigger`
 * is why the *reading* was taken, and the engine sets them independently -- a cold start can
 * produce a snapshot triggered `APP_START` carrying an observation triggered
 * `BATTERY_CHANGED`. Storing one and inferring the other would lose that.
 */
@Entity(
    tableName = "battery_snapshots",
    // No foreign key on session_id: it would close the cycle described above and force the
    // deferred checks that were measured not to roll back. The column is indexed because
    // snapshots are queried by session.
    indices = [Index("session_id")],
)
data class SnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,

    // ---- boot identity, stored without inventing strength -------------------------------
    /** `KERNEL`, `DERIVED` or `UNKNOWN`. Decides how the value columns are read back. */
    @ColumnInfo(name = "boot_kind") val bootKind: String,
    /** The kernel UUID. Non-null only when [bootKind] is `KERNEL`. */
    @ColumnInfo(name = "boot_kernel_id") val bootKernelId: String?,
    /** The diagnostic estimate. Non-null only when [bootKind] is `DERIVED`. */
    @ColumnInfo(name = "boot_derived_millis") val bootDerivedMillis: Long?,

    // ---- time ---------------------------------------------------------------------------
    @ColumnInfo(name = "elapsed_realtime_millis") val elapsedRealtimeMillis: Long,
    @ColumnInfo(name = "wall_clock_millis") val wallClockMillis: Long,
    @ColumnInfo(name = "utc_offset_minutes") val utcOffsetMinutes: Int,

    // ---- provenance ---------------------------------------------------------------------
    @ColumnInfo(name = "trigger") val trigger: String,
    @ColumnInfo(name = "observation_trigger") val observationTrigger: String,

    // ---- battery reading; optional measurements stay null, never zero -------------------
    @ColumnInfo(name = "battery_status") val batteryStatus: String,
    @ColumnInfo(name = "plug_source") val plugSource: String,
    @ColumnInfo(name = "battery_health") val batteryHealth: String,
    @ColumnInfo(name = "level") val level: Int?,
    @ColumnInfo(name = "scale") val scale: Int?,
    @ColumnInfo(name = "present") val present: Boolean?,
    @ColumnInfo(name = "temperature_deci_celsius") val temperatureDeciCelsius: Int?,
    @ColumnInfo(name = "voltage_milli_volts") val voltageMilliVolts: Int?,
    @ColumnInfo(name = "charge_counter_micro_amp_hours") val chargeCounterMicroAmpHours: Long?,

    // ---- comparability ------------------------------------------------------------------
    @ColumnInfo(name = "counter_generation") val counterGeneration: Long,
    @ColumnInfo(name = "snapshot_schema_version") val snapshotSchemaVersion: Int,
    @ColumnInfo(name = "counter_source") val counterSource: String,
    @ColumnInfo(name = "platform_version_at_capture") val platformVersionAtCapture: String?,
    @ColumnInfo(name = "app_version_at_capture") val appVersionAtCapture: String?,
)

/**
 * One logical interval, stored.
 *
 * Normalised: the interval references its snapshots by identity rather than copying their
 * fields. Copying would create two sources of truth for the same reading, and they would
 * eventually disagree.
 *
 * An active session legitimately has no end snapshot, so [endSnapshotId] is nullable and
 * carries no default. Requiring an end would mean the current interval -- the one a user
 * most wants to see -- could not be stored until it was over.
 */
@Entity(
    tableName = "battery_sessions",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["snapshot_id"],
            childColumns = ["start_snapshot_id"],
            // NO_ACTION, not CASCADE: deleting a snapshot that a session depends on must
            // fail rather than silently destroy the evidence for that interval.
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["snapshot_id"],
            childColumns = ["latest_snapshot_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["snapshot_id"],
            childColumns = ["end_snapshot_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("start_snapshot_id"),
        Index("latest_snapshot_id"),
        Index("end_snapshot_id"),
    ],
)
data class SessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "session_type") val sessionType: String,
    @ColumnInfo(name = "start_snapshot_id") val startSnapshotId: String,
    @ColumnInfo(name = "latest_snapshot_id") val latestSnapshotId: String,
    /** Null while the interval is running. */
    @ColumnInfo(name = "end_snapshot_id") val endSnapshotId: String?,
    @ColumnInfo(name = "end_reason") val endReason: String,
    /**
     * The generation the interval began in.
     *
     * Stored on the session as well as on its snapshots because the two are deliberately
     * independent: counters can reset mid-interval, so a session's starting generation is
     * not recoverable from its latest snapshot.
     */
    @ColumnInfo(name = "counter_generation") val counterGeneration: Long,
    /**
     * The high-water mark of battery samples this session has lost to retention.
     *
     * Null means nothing has ever been evicted. Otherwise it is the **greatest**
     * `sample_elapsed_realtime_millis` actually deleted -- not the oldest surviving one.
     * The distinction is the whole point: eviction removes oldest-first, so the greatest
     * deleted value sits strictly below the oldest retained sample, and a read model can
     * tell that the space between the session's start and its first retained sample once
     * held data. Recording the oldest *retained* value instead would place the mark exactly
     * where the series begins, and the comparison that is supposed to reveal the gap would
     * never fire.
     *
     * Monotonic. A later eviction can only raise it.
     *
     * Defaulted so every existing construction site keeps compiling: a session that has
     * never evicted anything is the normal case, and the column is about what retention did,
     * not about the interval itself.
     */
    @ColumnInfo(name = "battery_samples_evicted_through_elapsed_millis")
    val batterySamplesEvictedThroughElapsedMillis: Long? = null,
)

/**
 * The current engine state. Exactly one row, always.
 *
 * A fixed primary key is what makes that structural rather than hoped for: an insert with
 * `REPLACE` overwrites the single row, so there is no arrangement of concurrent writes that
 * produces two conflicting "current" records. A table with an autoincrementing key and a
 * `WHERE is_current = 1` convention would allow exactly that.
 *
 * Both references are nullable because the engine legitimately has no session before its
 * first observation.
 */
@Entity(
    tableName = "engine_state",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["snapshot_id"],
            childColumns = ["last_accepted_snapshot_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("session_id"), Index("last_accepted_snapshot_id")],
)
data class EngineStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "last_accepted_snapshot_id") val lastAcceptedSnapshotId: String?,
    @ColumnInfo(name = "counter_generation") val counterGeneration: Long,
) {
    companion object {
        /** The only permitted key. There is one engine state, so there is one row. */
        const val SINGLETON_ID = 0
    }
}

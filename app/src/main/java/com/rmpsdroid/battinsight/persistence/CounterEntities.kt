package com.rmpsdroid.battinsight.persistence

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Durable storage for the normalised counters Phase 7A learned to decode.
 *
 * ## What is here, and what deliberately is not
 *
 * Only the record families whose field semantics were verified against AOSP source: kernel
 * wakelocks and per-UID partial wakelocks, plus the capture metadata needed to refuse an
 * unsafe comparison. Nothing else.
 *
 * Absent on purpose:
 *
 *  - **the raw payload.** Never stored, never written to a file, never logged. A capture is
 *    900 KB of the user's package list and wakelock activity; the point of decoding is that
 *    the payload does not have to survive.
 *  - **unsupported tags.** 42 record types go undecoded; storing their text would be storing
 *    data whose meaning we have said we do not know.
 *  - **battery history.** 45,000 lines per capture, a different format, and no consumer.
 *  - **speculative columns** for record families a later phase might decode.
 *
 * ## Growth is bounded by sessions, not by refreshes
 *
 * Each battery session keeps at most two counter captures: a baseline and a latest. A user
 * who refreshes a hundred times in one session leaves two, not a hundred. That is a storage
 * *contract for this phase*, not a claim about the final architecture -- a real time series
 * needs a schema designed against real chart requirements, and inventing one now would mean
 * guessing both the sampling rate and the retention policy.
 */

/**
 * One decoded capture, with everything needed to decide whether it may be compared.
 *
 * The metadata is not decoration. Every field here exists because some comparison must be
 * refused when it differs, and a store that cannot refuse is a store that will eventually
 * subtract two numbers that mean different things.
 */
@Entity(
    tableName = "counter_capture",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["battery_session_id"],
            // NO_ACTION, as everywhere else here: deleting a session that still owns counter
            // captures must fail loudly rather than silently destroy them.
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("battery_session_id"),
        Index("battery_snapshot_id"),
        /**
         * The parent key that binds a capture to its session.
         *
         * Unique because SQLite requires a foreign key's parent columns to be uniquely
         * indexed, and this is the parent of the composite key on [SessionCounterStateEntity].
         * `capture_id` is already unique on its own, so this index adds no constraint that was
         * not already true -- what it adds is the ability to reference the pair.
         */
        Index("battery_session_id", "capture_id", unique = true),
    ],
)
data class CounterCaptureEntity(
    /**
     * This capture's own identity.
     *
     * Independent of the session and the snapshot on purpose. A capture is a distinct event
     * from a battery reading -- it can fail when the reading succeeds, and several can share
     * one snapshot -- so borrowing either identity would make two different things equal.
     * Never the wall clock either: that can repeat and can move backwards.
     */
    @PrimaryKey
    @ColumnInfo(name = "capture_id") val captureId: String,

    @ColumnInfo(name = "battery_session_id") val batterySessionId: String,

    /**
     * The battery reading this capture sits alongside, when there was one.
     *
     * Nullable because the two are genuinely independent: a privileged capture can succeed
     * before any battery observation has been accepted. No foreign key, for the same reason
     * Phase 6 dropped the snapshot-to-session key -- it would close a reference cycle and
     * force deferred constraint checking, which was measured not to roll back.
     */
    @ColumnInfo(name = "battery_snapshot_id") val batterySnapshotId: String?,

    // ---- provenance --------------------------------------------------------------------
    @ColumnInfo(name = "source_format") val sourceFormat: String,
    @ColumnInfo(name = "source_format_version") val sourceFormatVersion: Int?,
    @ColumnInfo(name = "backend_kind") val backendKind: String,

    // ---- the format's own version block, which gates every comparison ------------------
    @ColumnInfo(name = "record_format_version") val recordFormatVersion: Int,
    @ColumnInfo(name = "checkin_version") val checkinVersion: Int,
    @ColumnInfo(name = "parcel_version") val parcelVersion: Long,
    @ColumnInfo(name = "platform_start_fingerprint") val platformStartFingerprint: String,
    @ColumnInfo(name = "platform_end_fingerprint") val platformEndFingerprint: String,
    /**
     * Whether the accounting window spans an OS update.
     *
     * Stored rather than derived so a query can refuse such a capture without re-comparing
     * two strings, and so the reason survives even if fingerprint formatting ever changes.
     */
    @ColumnInfo(name = "platform_changed") val platformChanged: Boolean,

    // ---- time --------------------------------------------------------------------------
    @ColumnInfo(name = "capture_elapsed_realtime_millis") val captureElapsedRealtimeMillis: Long,
    @ColumnInfo(name = "capture_wall_clock_millis") val captureWallClockMillis: Long,

    // ---- comparability carried from the session engine ---------------------------------
    @ColumnInfo(name = "counter_generation") val counterGeneration: Long,
    @ColumnInfo(name = "boot_kind") val bootKind: String,
    @ColumnInfo(name = "boot_kernel_id") val bootKernelId: String?,
    @ColumnInfo(name = "boot_derived_millis") val bootDerivedMillis: Long?,

    // ---- shape of what arrived ---------------------------------------------------------
    @ColumnInfo(name = "payload_byte_count") val payloadByteCount: Int,
    /** A digest for correlating captures in a bug report. Never the payload itself. */
    @ColumnInfo(name = "payload_hash") val payloadHash: String?,
    /**
     * How many decode warnings this capture produced.
     *
     * A count, not the messages. The messages name record tags and field positions, which is
     * engineer detail with no durable value, and storing free text invites storing payload
     * fragments in it by accident.
     */
    @ColumnInfo(name = "warning_count") val warningCount: Int,
    /**
     * Whether the decoder had verified this checkin version against a real capture.
     *
     * Persisted because "the decoder tolerated it" and "these counter semantics are
     * verified" are different claims, and only the second is safe to subtract.
     */
    @ColumnInfo(name = "checkin_version_verified") val checkinVersionVerified: Boolean,
)

/**
 * One kernel wakelock total, as stored.
 *
 * Identity is `capture + accounting window + name`, which is the real identity rather than a
 * convenience: measured across both real captures, that triple has **no duplicates** -- 68
 * distinct on Android 16 and 108 on Android 10, with zero collisions.
 *
 * The name may legitimately be empty (Android 16 emits exactly one such record) and may
 * contain commas, so it is stored as written.
 */
@Entity(
    tableName = "kernel_wakelock_counter",
    primaryKeys = ["capture_id", "accounting_window", "identity_id"],
    foreignKeys = [
        ForeignKey(
            entity = CounterCaptureEntity::class,
            parentColumns = ["capture_id"],
            childColumns = ["capture_id"],
            // CASCADE here, unlike everywhere else, and the difference is deliberate: these
            // rows have no meaning without their capture. A superseded capture is deleted as
            // one unit, and leaving orphaned counters behind would be the leak this bounded
            // retention model exists to prevent.
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WakelockIdentityEntity::class,
            parentColumns = ["identity_id"],
            childColumns = ["identity_id"],
            // NO_ACTION, not CASCADE: an identity must never be able to take counter rows
            // with it. The orphan sweep runs the other way round -- identities are removed
            // only once nothing references them.
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("capture_id"),
        /** "this counter's series across a session" -- an integer lookup, not a name scan. */
        Index("identity_id", "capture_id"),
    ],
)
data class KernelWakelockCounterEntity(
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "accounting_window") val accountingWindow: String,
    /** Resolves to `(KERNEL, -1, name)` in [WakelockIdentityEntity]. */
    @ColumnInfo(name = "identity_id") val identityId: Long,
    /** Cumulative held time in milliseconds within the window. */
    @ColumnInfo(name = "total_duration_millis") val totalDurationMillis: Long,
    /** Cumulative acquisition count within the window. */
    @ColumnInfo(name = "count") val count: Long,
)

/**
 * One per-UID partial wakelock total, as stored.
 *
 * Identity is `capture + accounting window + uid + name`. Measured: no duplicates across
 * either real capture -- 315 distinct on Android 16, 231 on Android 10.
 *
 * The UID is the identity and there is no package column. See the note in
 * `docs/security-privacy.md`: a numeric UID is a far weaker statement about a person's device
 * than a durable list of the applications on it, and the delta this phase computes does not
 * need the names.
 */
@Entity(
    tableName = "partial_wakelock_counter",
    primaryKeys = ["capture_id", "accounting_window", "identity_id"],
    foreignKeys = [
        ForeignKey(
            entity = CounterCaptureEntity::class,
            parentColumns = ["capture_id"],
            childColumns = ["capture_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WakelockIdentityEntity::class,
            parentColumns = ["identity_id"],
            childColumns = ["identity_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("capture_id"),
        Index("identity_id", "capture_id"),
    ],
)
data class PartialWakelockCounterEntity(
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "accounting_window") val accountingWindow: String,
    /** Resolves to `(PARTIAL, uid, name)` in [WakelockIdentityEntity]. */
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "total_duration_millis") val totalDurationMillis: Long,
    @ColumnInfo(name = "count") val count: Long,
)

/**
 * Which capture is the baseline for a session, and which is the latest.
 *
 * The session id is the primary key, so "at most one baseline and one latest per session" is
 * structural rather than a rule the application remembers to follow. There is no way to
 * express a second baseline.
 *
 * This indirection is why a first capture costs one row and not two. Baseline and latest both
 * point at the same capture until a second one arrives, so the common case stores one set of
 * counters rather than two identical sets.
 *
 * ## The capture keys are composite, and that is the point
 *
 * The obvious schema gives this table three independent foreign keys: one to the session, and
 * one each to `counter_capture(capture_id)`. That is what Phase 7B shipped, and it is not
 * enough. A single-column key proves the capture *exists*; it says nothing about whose it is.
 * Session A's state row could name a capture owned by session B and every constraint would be
 * satisfied -- producing a delta computed across two different battery sessions, which is
 * exactly the class of silently-wrong answer this project exists to prevent.
 *
 * So both capture references carry the session id with them:
 *
 * ```
 * (battery_session_id, baseline_capture_id) -> counter_capture(battery_session_id, capture_id)
 * (battery_session_id, latest_capture_id)   -> counter_capture(battery_session_id, capture_id)
 * ```
 *
 * `battery_session_id` appears in all three keys, so a row can only ever reference captures
 * belonging to its own session. The database refuses the cross-session pointer; nothing is
 * left to a Kotlin precondition that a later refactor could route around.
 */
@Entity(
    tableName = "session_counter_state",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["battery_session_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = CounterCaptureEntity::class,
            parentColumns = ["battery_session_id", "capture_id"],
            childColumns = ["battery_session_id", "baseline_capture_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = CounterCaptureEntity::class,
            parentColumns = ["battery_session_id", "capture_id"],
            childColumns = ["battery_session_id", "latest_capture_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("baseline_capture_id"), Index("latest_capture_id")],
)
data class SessionCounterStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "battery_session_id") val batterySessionId: String,
    @ColumnInfo(name = "baseline_capture_id") val baselineCaptureId: String,
    @ColumnInfo(name = "latest_capture_id") val latestCaptureId: String,
)

package com.rmpsdroid.battinsight.persistence

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Storage for the sampled series Phase 9B introduces.
 *
 * Two tables with very different economics, which is the whole reason they are separate
 * tables sampled at separate cadences. Measured in Phase 9A on a real Android 16 capture:
 *
 * ```
 * one battery sample                        343 bytes
 * one full counter capture (repeated text)  103.8 KB
 * one full counter capture (interned)        25.0 KB
 * ```
 *
 * A battery reading costs roughly **1/300th** of a privileged counter capture, so sampling
 * both on one cadence would waste either resolution or storage by two orders of magnitude.
 */

/**
 * A wakelock's identity, stored once instead of on every counter row.
 *
 * ## Why this exists
 *
 * Phase 9A measured partial wakelock names on a real Android 16 capture at **79 characters
 * on average and 423 at the longest** -- they are call chains, not labels:
 *
 * ```
 * WorkManager:TikTokListenableWorker startWork -> com.google.apps.tiktok.sync.impl...
 * ```
 *
 * With 408 partial rows per capture, roughly 32 KB of identical text was being rewritten
 * every time. Interning it cut a capture from 103.8 KB to 25.0 KB -- a 4.15× reduction that
 * discards nothing, which is why it was chosen over sparse checkpoints, interval deltas and
 * top-N, all of which buy less by throwing information away.
 *
 * ## This table is not harmless metadata
 *
 * Measured on the same capture: **60.3% of partial wakelock names contain a dotted
 * package-style token, and 63 distinct package prefixes are recoverable from the names
 * alone** -- `com.google.android.apps.messaging.shared.receiver.bootcomplete` and the like.
 * So this is, in effect, an inventory of what runs on the device: the very thing Phase 7B
 * declined to persist when it decided not to store package mappings.
 *
 * Interning does not introduce that data -- v2 already held it in
 * `partial_wakelock_counter.name` -- but it does change its **lifetime**, from "as long as a
 * counter row references it" to "forever, unless swept". That is why the sweep in
 * [CounterDao.sweepOrphanIdentities] runs with every retention pass and every clear, rather
 * than only when the user wipes everything.
 *
 * An identity was observed appearing within minutes of installing an application during the
 * Phase 9A measurements: `(uid 10237, "*launch*")` -- BattInsight itself.
 *
 * ## AUTOINCREMENT is load-bearing
 *
 * Without it SQLite reuses the rowids of deleted rows, so a swept identity's id could later
 * be handed to a completely different wakelock and silently relabel any retained reference to
 * it. `AUTOINCREMENT` costs one `sqlite_sequence` row and removes that entire class of bug.
 */
@Entity(
    tableName = "wakelock_identity",
    indices = [Index("family", "uid", "name", unique = true)],
)
data class WakelockIdentityEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "identity_id") val identityId: Long = 0L,
    /** [FAMILY_KERNEL] or [FAMILY_PARTIAL]. */
    @ColumnInfo(name = "family") val family: String,
    /** The real numeric UID for partial wakelocks; [KERNEL_UID] for kernel ones. */
    @ColumnInfo(name = "uid") val uid: Int,
    /**
     * Exactly as the decoder produced it.
     *
     * Not trimmed, not case-folded, not normalised. Android 16 emits one kernel wakelock with
     * an empty name and several containing commas, and altering either would make two
     * different counters share an identity.
     */
    @ColumnInfo(name = "name") val name: String,
) {
    companion object {
        const val FAMILY_KERNEL = "KERNEL"
        const val FAMILY_PARTIAL = "PARTIAL"

        /** Kernel wakelocks belong to no UID. A sentinel keeps the unique index total. */
        const val KERNEL_UID = -1
    }
}

/**
 * One sampled battery reading.
 *
 * ## Not a replacement for [SnapshotEntity]
 *
 * A snapshot anchors a session boundary and is never evicted; a sample is a point in a series
 * and is bounded by a hard cap. Phase 9A found that treating them as the same thing is what
 * made the existing data look like a series when it is not: snapshots are minted only when
 * Android broadcasts a change *and* the process happens to be alive. Measured on the
 * emulator, ten minutes of foreground life produced **two** snapshot rows.
 *
 * Keeping them separate is what lets retention delete samples freely -- the session's start
 * level is always recoverable from its start snapshot, which retention never touches.
 *
 * ## Every measurement is nullable
 *
 * A device that does not report temperature must store null, not zero. On a battery screen a
 * zero reads as "nothing", and this project has spent three phases making sure missing and
 * zero stay distinguishable.
 */
@Entity(
    tableName = "battery_sample",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            // NO_ACTION like every other session-owned table here. CASCADE would make the
            // clear order implicit, and Phase 7B.2 was the bill for an implicit clear order.
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    // One index, and it is the only query the series makes: a session's samples in elapsed
    // order. A wall-clock index was considered and rejected -- ordering a series by wall
    // clock is precisely the mistake the segment model exists to prevent.
    indices = [Index("session_id", "sample_elapsed_realtime_millis")],
)
data class BatterySampleEntity(
    @PrimaryKey
    @ColumnInfo(name = "sample_id") val sampleId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,

    // ---- time: the same three-part model the rest of the schema uses -------------------
    /** Ordering and arithmetic. Never display. */
    @ColumnInfo(name = "sample_elapsed_realtime_millis") val sampleElapsedRealtimeMillis: Long,
    /** Display only. Never a duration -- it can move backwards. */
    @ColumnInfo(name = "sample_wall_clock_millis") val sampleWallClockMillis: Long,
    /** Stored, not read from the device at display time, so history keeps its own labels. */
    @ColumnInfo(name = "sample_utc_offset_minutes") val sampleUtcOffsetMinutes: Int,

    // ---- boot identity, so continuity is provable rather than assumed ------------------
    @ColumnInfo(name = "boot_kind") val bootKind: String,
    @ColumnInfo(name = "boot_kernel_id") val bootKernelId: String?,
    @ColumnInfo(name = "boot_derived_millis") val bootDerivedMillis: Long?,

    // ---- the reading ------------------------------------------------------------------
    @ColumnInfo(name = "level") val level: Int?,
    @ColumnInfo(name = "scale") val scale: Int?,
    @ColumnInfo(name = "battery_status") val batteryStatus: String,
    @ColumnInfo(name = "plug_source") val plugSource: String,
    @ColumnInfo(name = "temperature_deci_celsius") val temperatureDeciCelsius: Int?,
    @ColumnInfo(name = "voltage_milli_volts") val voltageMilliVolts: Int?,
    @ColumnInfo(name = "charge_counter_micro_amp_hours") val chargeCounterMicroAmpHours: Long?,

    /** What produced this sample: a cadence tick, a broadcast, or the app starting. */
    @ColumnInfo(name = "trigger") val trigger: String,
    @ColumnInfo(name = "counter_generation") val counterGeneration: Long,
)

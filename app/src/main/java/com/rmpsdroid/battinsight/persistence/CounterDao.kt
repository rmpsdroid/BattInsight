package com.rmpsdroid.battinsight.persistence

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

/**
 * The only way counter data reaches the database.
 *
 * All suspending. Writes are [Upsert], never `@Insert(REPLACE)` -- Phase 6.1 measured that
 * SQLite's `INSERT OR REPLACE` deletes the existing row and inserts a new one, moving a
 * session row's rowid from 1 to 2 for what was meant to be a field update. Here that would be
 * worse than cosmetic: the counter rows hang off `counter_capture` with `ON DELETE CASCADE`,
 * so a replace of a capture row would take its counters with it.
 */
@Dao
interface CounterDao {

    // ------------------------------------------------------------------------ reads

    @Query("SELECT * FROM session_counter_state WHERE battery_session_id = :sessionId")
    suspend fun state(sessionId: String): SessionCounterStateEntity?

    @Query("SELECT * FROM counter_capture WHERE capture_id = :captureId")
    suspend fun capture(captureId: String): CounterCaptureEntity?

    @Query("SELECT * FROM kernel_wakelock_counter WHERE capture_id = :captureId")
    suspend fun kernelWakelocks(captureId: String): List<KernelWakelockCounterEntity>

    @Query("SELECT * FROM partial_wakelock_counter WHERE capture_id = :captureId")
    suspend fun partialWakelocks(captureId: String): List<PartialWakelockCounterEntity>

    /**
     * A capture's kernel counters with their names resolved.
     *
     * The join replaces what v2 got for free from a `name` column. It is cheap -- the
     * dictionary is a few hundred rows and `identity_id` is the primary key on the other side
     * -- and it is the price of not rewriting 32 KB of identical text per capture.
     */
    @Query(
        """
        SELECT c.accounting_window AS accountingWindow, i.name AS name,
               c.total_duration_millis AS totalDurationMillis, c.count AS count
          FROM kernel_wakelock_counter c
          JOIN wakelock_identity i ON i.identity_id = c.identity_id
         WHERE c.capture_id = :captureId
        """,
    )
    suspend fun resolvedKernelWakelocks(captureId: String): List<ResolvedKernelRow>

    @Query(
        """
        SELECT c.accounting_window AS accountingWindow, i.uid AS uid, i.name AS name,
               c.total_duration_millis AS totalDurationMillis, c.count AS count
          FROM partial_wakelock_counter c
          JOIN wakelock_identity i ON i.identity_id = c.identity_id
         WHERE c.capture_id = :captureId
        """,
    )
    suspend fun resolvedPartialWakelocks(captureId: String): List<ResolvedPartialRow>

    /**
     * One identity's values across a session's retained captures, oldest first.
     *
     * The query Phase 9A named as the reason the counter series exists at all -- "which
     * wakelocks became important during this interval". Under v2 it was a string comparison
     * against an average 79-character name on every row of every capture; here it is an
     * indexed integer lookup, which is what `(identity_id, capture_id)` exists for.
     */
    @Query(
        """
        SELECT c.capture_id AS captureId,
               cap.capture_elapsed_realtime_millis AS captureElapsedRealtimeMillis,
               c.accounting_window AS accountingWindow,
               c.total_duration_millis AS totalDurationMillis, c.count AS count
          FROM kernel_wakelock_counter c
          JOIN counter_capture cap ON cap.capture_id = c.capture_id
         WHERE c.identity_id = :identityId AND cap.battery_session_id = :sessionId
         ORDER BY cap.capture_elapsed_realtime_millis ASC, cap.capture_id ASC
        """,
    )
    suspend fun kernelIdentitySeries(sessionId: String, identityId: Long): List<IdentitySeriesRow>

    @Query(
        """
        SELECT c.capture_id AS captureId,
               cap.capture_elapsed_realtime_millis AS captureElapsedRealtimeMillis,
               c.accounting_window AS accountingWindow,
               c.total_duration_millis AS totalDurationMillis, c.count AS count
          FROM partial_wakelock_counter c
          JOIN counter_capture cap ON cap.capture_id = c.capture_id
         WHERE c.identity_id = :identityId AND cap.battery_session_id = :sessionId
         ORDER BY cap.capture_elapsed_realtime_millis ASC, cap.capture_id ASC
        """,
    )
    suspend fun partialIdentitySeries(sessionId: String, identityId: Long): List<IdentitySeriesRow>

    /**
     * Every retained capture for one session, oldest first.
     *
     * This *is* the counter series. Phase 9A.1 established that no second membership table is
     * needed: a capture's `battery_session_id` already expresses which session it belongs to,
     * and a parallel table asserting the same thing could only ever disagree with it.
     *
     * `capture_id` breaks ties for the same reason it does in the battery series. The existing
     * `index_counter_capture_battery_session_id` serves this; Phase 9A.1 measured a dedicated
     * ordering index as worth 0.5 microseconds at this scale and did not add it.
     */
    @Query(
        """
        SELECT * FROM counter_capture
         WHERE battery_session_id = :sessionId
         ORDER BY capture_elapsed_realtime_millis ASC, capture_id ASC
        """,
    )
    suspend fun capturesFor(sessionId: String): List<CounterCaptureEntity>

    @Query("SELECT * FROM wakelock_identity WHERE identity_id = :identityId")
    suspend fun identity(identityId: Long): WakelockIdentityEntity?

    @Query(
        """
        SELECT * FROM wakelock_identity
         WHERE family = :family AND uid = :uid AND name = :name
        """,
    )
    suspend fun findIdentity(family: String, uid: Int, name: String): WakelockIdentityEntity?

    @Query("SELECT * FROM wakelock_identity")
    suspend fun allIdentities(): List<WakelockIdentityEntity>

    @Query("SELECT COUNT(*) FROM wakelock_identity")
    suspend fun identityCount(): Int

    @Query("SELECT COUNT(*) FROM counter_capture")
    suspend fun captureCount(): Int

    @Query("SELECT COUNT(*) FROM counter_capture WHERE battery_session_id = :sessionId")
    suspend fun captureCountFor(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM kernel_wakelock_counter")
    suspend fun kernelWakelockRowCount(): Int

    @Query("SELECT COUNT(*) FROM partial_wakelock_counter")
    suspend fun partialWakelockRowCount(): Int

    // ----------------------------------------------------------------------- writes

    @Upsert
    suspend fun upsertCapture(capture: CounterCaptureEntity)

    @Upsert
    suspend fun upsertKernelWakelocks(rows: List<KernelWakelockCounterEntity>)

    @Upsert
    suspend fun upsertPartialWakelocks(rows: List<PartialWakelockCounterEntity>)

    @Upsert
    suspend fun upsertState(state: SessionCounterStateEntity)

    /**
     * Interns an identity, ignoring a row that already exists.
     *
     * `IGNORE` rather than `REPLACE`: replacing would delete and reinsert, handing the
     * identity a new `identity_id` and orphaning every counter row that referenced the old
     * one. The unique index on `(family, uid, name)` is what makes concurrent creation safe,
     * so the guarantee lives in the schema rather than in a caller remembering to check.
     */
    @Insert(onConflict = androidx.room3.OnConflictStrategy.IGNORE)
    suspend fun insertIdentityIgnoring(identity: WakelockIdentityEntity): Long

    @Query("DELETE FROM counter_capture WHERE capture_id = :captureId")
    suspend fun deleteCapture(captureId: String)

    /**
     * Removes every identity no counter row still references.
     *
     * Privacy, not housekeeping. The identity table is small -- a few hundred rows, ~32 KB of
     * names -- but 60.3% of partial wakelock names carry a package-style token, so a stale
     * identity is a durable record that an application ran on this device long after the
     * measurement justifying it was deleted. It is swept with every retention pass, not only
     * on a full wipe.
     */
    @Query(
        """
        DELETE FROM wakelock_identity
         WHERE NOT EXISTS (
             SELECT 1 FROM kernel_wakelock_counter k
              WHERE k.identity_id = wakelock_identity.identity_id
         )
           AND NOT EXISTS (
             SELECT 1 FROM partial_wakelock_counter p
              WHERE p.identity_id = wakelock_identity.identity_id
         )
        """,
    )
    suspend fun sweepOrphanIdentities(): Int

    @Query("DELETE FROM session_counter_state")
    suspend fun deleteAllState()

    @Query("DELETE FROM counter_capture")
    suspend fun deleteAllCaptures()

    // ------------------------------------------------------------------ transactions

    /**
     * Interns a wakelock identity, returning the id to reference it by.
     *
     * `INSERT OR IGNORE` then re-read, rather than check-then-insert: the unique index on
     * `(family, uid, name)` is the thing that actually guarantees uniqueness, so the race
     * between two callers resolves in SQLite rather than in Kotlin. A returned rowid of -1
     * means the insert was ignored because the row already existed.
     */
    @Transaction
    suspend fun internIdentity(family: String, uid: Int, name: String): Long {
        findIdentity(family, uid, name)?.let { return it.identityId }
        val rowId = insertIdentityIgnoring(
            WakelockIdentityEntity(family = family, uid = uid, name = name),
        )
        if (rowId != -1L) return rowId
        return findIdentity(family, uid, name)!!.identityId
    }

    /**
     * Stores a capture, points state at it, applies retention and sweeps identities -- once.
     *
     * This replaces v2's `establishBaseline`/`replaceLatest` pair. The behavioural change is
     * that a superseded capture is **no longer deleted on sight**: v2 kept a baseline and a
     * latest and threw the middle away, which is exactly why there was no series to chart.
     * What to remove is now decided by retention and passed in as [evictCaptureIds].
     *
     * The eviction *decision* is made before this call, by the pure comparability engine over
     * stored observations; this method only applies it. Doing the comparison here would mean
     * a second comparison engine inside persistence, which Phase 9A.2 forbids.
     *
     * Order is load-bearing under immediate foreign keys:
     *
     *  1. identities first -- the counter rows reference them;
     *  2. the capture -- the counter rows and the state row reference it;
     *  3. the counter rows;
     *  4. the state row, repointed at the new latest **before** anything is deleted, so no
     *     constraint ever sees it naming a row that has gone;
     *  5. evictions, which cascade their counter rows away;
     *  6. the orphan sweep, last, once nothing references the identities any more.
     *
     * @param baselineCaptureId null when this capture is the session's first, and therefore
     *   becomes both baseline and latest.
     */
    @Transaction
    suspend fun persistCapture(
        capture: CounterCaptureEntity,
        kernelWakelocks: List<CounterRowInput>,
        partialWakelocks: List<CounterRowInput>,
        baselineCaptureId: String?,
        evictCaptureIds: List<String> = emptyList(),
    ) {
        upsertCapture(capture)

        val kernelRows = kernelWakelocks.map { row ->
            KernelWakelockCounterEntity(
                captureId = capture.captureId,
                accountingWindow = row.accountingWindow,
                identityId = internIdentity(
                    WakelockIdentityEntity.FAMILY_KERNEL,
                    WakelockIdentityEntity.KERNEL_UID,
                    row.name,
                ),
                totalDurationMillis = row.totalDurationMillis,
                count = row.count,
            )
        }
        val partialRows = partialWakelocks.map { row ->
            PartialWakelockCounterEntity(
                captureId = capture.captureId,
                accountingWindow = row.accountingWindow,
                identityId = internIdentity(
                    WakelockIdentityEntity.FAMILY_PARTIAL,
                    row.uid,
                    row.name,
                ),
                totalDurationMillis = row.totalDurationMillis,
                count = row.count,
            )
        }
        upsertKernelWakelocks(kernelRows)
        upsertPartialWakelocks(partialRows)

        upsertState(
            SessionCounterStateEntity(
                batterySessionId = capture.batterySessionId,
                baselineCaptureId = baselineCaptureId ?: capture.captureId,
                latestCaptureId = capture.captureId,
            ),
        )

        for (id in evictCaptureIds) {
            // Guarded rather than trusted. Deleting the baseline would cascade away the row
            // every session total is measured from, and deleting the incoming capture would
            // leave the state row pointing at nothing.
            if (id != capture.captureId && id != baselineCaptureId) deleteCapture(id)
        }
        if (evictCaptureIds.isNotEmpty()) sweepOrphanIdentities()
    }

    /**
     * Forgets every counter capture.
     *
     * State rows first: they reference captures, and the constraints are immediate.
     */
    @Transaction
    suspend fun clearAllCounters() {
        deleteAllState()
        deleteAllCaptures()
        // The captures took their counter rows with them via CASCADE, so every identity is
        // now unreferenced. Forgetting the counters has to mean forgetting who they belonged
        // to as well, or the dictionary outlives the data that justified it.
        sweepOrphanIdentities()
    }
}

/**
 * One counter row on its way into storage, before it has an identity id.
 *
 * A persistence-level carrier, not a domain type: it exists so [CounterDao.persistCapture] can
 * intern identities inside the same transaction that writes the rows referencing them. The
 * decoder's own types stay Room-free.
 *
 * `uid` is ignored for kernel wakelocks, which use [WakelockIdentityEntity.KERNEL_UID].
 */
data class CounterRowInput(
    val accountingWindow: String,
    val uid: Int,
    val name: String,
    val totalDurationMillis: Long,
    val count: Long,
)

/** A kernel counter row with its interned name resolved back. */
data class ResolvedKernelRow(
    val accountingWindow: String,
    val name: String,
    val totalDurationMillis: Long,
    val count: Long,
)

/** A partial counter row with its interned uid and name resolved back. */
data class ResolvedPartialRow(
    val accountingWindow: String,
    val uid: Int,
    val name: String,
    val totalDurationMillis: Long,
    val count: Long,
)

/** One point in a single identity's series: which capture, when, and the value it held. */
data class IdentitySeriesRow(
    val captureId: String,
    val captureElapsedRealtimeMillis: Long,
    val accountingWindow: String,
    val totalDurationMillis: Long,
    val count: Long,
)

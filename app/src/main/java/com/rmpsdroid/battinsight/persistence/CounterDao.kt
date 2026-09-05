package com.rmpsdroid.battinsight.persistence

import androidx.room3.Dao
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

    @Query("DELETE FROM counter_capture WHERE capture_id = :captureId")
    suspend fun deleteCapture(captureId: String)

    @Query("DELETE FROM session_counter_state")
    suspend fun deleteAllState()

    @Query("DELETE FROM counter_capture")
    suspend fun deleteAllCaptures()

    // ------------------------------------------------------------------ transactions

    /**
     * Records the first capture of a session, as both baseline and latest.
     *
     * One row of counters, not two. Baseline and latest both name this capture until a second
     * one arrives, which is what keeps the common case from storing two identical sets.
     *
     * Order is load-bearing under immediate foreign keys: the capture must exist before the
     * state row can reference it.
     */
    @Transaction
    suspend fun establishBaseline(
        capture: CounterCaptureEntity,
        kernelWakelocks: List<KernelWakelockCounterEntity>,
        partialWakelocks: List<PartialWakelockCounterEntity>,
    ) {
        upsertCapture(capture)
        upsertKernelWakelocks(kernelWakelocks)
        upsertPartialWakelocks(partialWakelocks)
        upsertState(
            SessionCounterStateEntity(
                batterySessionId = capture.batterySessionId,
                baselineCaptureId = capture.captureId,
                latestCaptureId = capture.captureId,
            ),
        )
    }

    /**
     * Replaces the latest capture, leaving the baseline exactly where it was.
     *
     * The superseded capture is deleted explicitly, inside the same transaction, and only
     * when it is not the baseline. That last condition is the whole reason this is written
     * out rather than left to a conflict strategy: on the second capture of a session the
     * outgoing latest *is* the baseline, and deleting it would cascade the baseline's
     * counters away.
     *
     * Sequence matters. The state row is repointed before the old capture is deleted, so the
     * foreign key never sees a state row referencing a row that has gone.
     */
    @Transaction
    suspend fun replaceLatest(
        capture: CounterCaptureEntity,
        kernelWakelocks: List<KernelWakelockCounterEntity>,
        partialWakelocks: List<PartialWakelockCounterEntity>,
        baselineCaptureId: String,
        supersededCaptureId: String?,
    ) {
        upsertCapture(capture)
        upsertKernelWakelocks(kernelWakelocks)
        upsertPartialWakelocks(partialWakelocks)
        upsertState(
            SessionCounterStateEntity(
                batterySessionId = capture.batterySessionId,
                baselineCaptureId = baselineCaptureId,
                latestCaptureId = capture.captureId,
            ),
        )
        if (supersededCaptureId != null &&
            supersededCaptureId != baselineCaptureId &&
            supersededCaptureId != capture.captureId
        ) {
            deleteCapture(supersededCaptureId)
        }
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
    }
}

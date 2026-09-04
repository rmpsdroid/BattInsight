package com.rmpsdroid.battinsight.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * The only way anything reaches the database.
 *
 * All suspending, so nothing here can block the main thread. Room enforces that for suspend
 * DAO functions by dispatching them off the caller's thread.
 *
 * The writes are `REPLACE` upserts because a transition legitimately rewrites rows it has
 * already written -- a session's `latest_snapshot_id` moves forward on every accepted
 * observation, and the single engine-state row is overwritten every time.
 */
@Dao
interface SessionDao {

    // ------------------------------------------------------------------------ reads

    @Query("SELECT * FROM engine_state WHERE id = :id")
    suspend fun engineState(id: Int = EngineStateEntity.SINGLETON_ID): EngineStateEntity?

    @Query("SELECT * FROM battery_sessions WHERE session_id = :sessionId")
    suspend fun session(sessionId: String): SessionEntity?

    @Query("SELECT * FROM battery_snapshots WHERE snapshot_id IN (:ids)")
    suspend fun snapshots(ids: List<String>): List<SnapshotEntity>

    @Query("SELECT COUNT(*) FROM battery_sessions")
    suspend fun sessionCount(): Int

    @Query("SELECT COUNT(*) FROM battery_snapshots")
    suspend fun snapshotCount(): Int

    /**
     * Sessions with no end snapshot.
     *
     * Used by tests to assert the invariant that at most one interval is ever open. The
     * application itself finds the active session through the engine-state row, which is
     * structurally singular, rather than by scanning for open ones.
     */
    @Query("SELECT * FROM battery_sessions WHERE end_snapshot_id IS NULL")
    suspend fun activeSessions(): List<SessionEntity>

    // ----------------------------------------------------------------------- writes

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshots(snapshots: List<SnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(sessions: List<SessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEngineState(state: EngineStateEntity)

    @Query("DELETE FROM engine_state")
    suspend fun deleteEngineState()

    @Query("DELETE FROM battery_sessions")
    suspend fun deleteSessions()

    @Query("DELETE FROM battery_snapshots")
    suspend fun deleteSnapshots()

    // ------------------------------------------------------------------ transactions

    /**
     * Writes everything one transition changed, atomically.
     *
     * The order is load-bearing, not cosmetic. Foreign keys are immediate, so each statement
     * is checked as it runs and every referenced row must already exist: snapshots before the
     * sessions that name them, sessions before the engine state that names them. A violation
     * therefore fails the statement and rolls the whole transaction back, which is what
     * deferred constraints were measured *not* to do.
     */
    @Transaction
    suspend fun persistTransition(
        snapshots: List<SnapshotEntity>,
        sessions: List<SessionEntity>,
        engineState: EngineStateEntity,
    ) {
        upsertSnapshots(snapshots)
        upsertSessions(sessions)
        upsertEngineState(engineState)
    }

    /**
     * Removes every stored session, snapshot and engine-state row.
     *
     * One transaction, and in dependency order: engine state first, then the sessions it
     * referenced, then the snapshots those referenced. Immediate constraints would refuse
     * any other order, which is the schema doing its job.
     */
    @Transaction
    suspend fun clearAll() {
        deleteEngineState()
        deleteSessions()
        deleteSnapshots()
    }
}

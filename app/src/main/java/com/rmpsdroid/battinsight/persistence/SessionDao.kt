package com.rmpsdroid.battinsight.persistence

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

/**
 * The only way anything reaches the database.
 *
 * All suspending, so nothing here can block the main thread. Room enforces that for suspend
 * DAO functions by dispatching them off the caller's thread.
 *
 * The writes are [Upsert], and that is a correctness choice rather than a style one.
 *
 * They began as `@Insert(onConflict = REPLACE)`, which compiles to SQLite's
 * `INSERT OR REPLACE`. SQLite resolves a primary-key collision there by *deleting* the
 * existing row and inserting a new one, which was measured directly: advancing a session's
 * `latest_snapshot_id` moved its rowid from 1 to 2, so an ordinary field update was
 * destroying and recreating the row.
 *
 * Nothing was lost that time, only because every foreign key here is `NO_ACTION` and the
 * reinsert happens inside the same statement. That is a coincidence of the current schema,
 * not a property of the operation: the day any child row is added with `ON DELETE CASCADE`,
 * the same "update" silently deletes it. Delete-and-reinsert is simply not what is meant when
 * a session's latest snapshot advances or the engine-state row is refreshed.
 *
 * `@Upsert` generates a real `ON CONFLICT ... DO UPDATE`, which changes the existing row in
 * place and leaves its identity alone.
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

    /**
     * Recent sessions, newest first, joined to their start snapshot for the wall clock.
     *
     * The ordering column lives on `battery_snapshots`, not on `battery_sessions`, so this
     * joins by `start_snapshot_id` -- which is the snapshot table's primary key, making the
     * lookup an index seek per row rather than a scan.
     *
     * SQLite has to sort the joined result because no index covers "sessions ordered by their
     * start snapshot's wall clock". That is deliberate: adding one would mean a schema change,
     * and at the scale this table actually reaches -- roughly one session per charge cycle, so
     * hundreds per year -- sorting is not measurable. An index is worth adding when a
     * measurement says so, not on principle.
     *
     * `snapshot_id` breaks ties. Two sessions can share a wall-clock millisecond, and without
     * a deterministic tiebreak the same query would return them in different orders on
     * different runs, which makes paging skip or repeat rows.
     */
    @Query(
        """
        SELECT s.* FROM battery_sessions s
        JOIN battery_snapshots start ON start.snapshot_id = s.start_snapshot_id
        WHERE (:before IS NULL OR start.wall_clock_millis < :before)
        ORDER BY start.wall_clock_millis DESC, start.snapshot_id DESC
        LIMIT :limit
        """,
    )
    suspend fun recentSessions(limit: Int, before: Long?): List<SessionEntity>

    /** The start wall clock of a session, used to page further back. */
    @Query(
        """
        SELECT start.wall_clock_millis FROM battery_sessions s
        JOIN battery_snapshots start ON start.snapshot_id = s.start_snapshot_id
        WHERE s.session_id = :sessionId
        """,
    )
    suspend fun sessionStartWallClock(sessionId: String): Long?

    // ----------------------------------------------------------------------- writes

    @Upsert
    suspend fun upsertSnapshots(snapshots: List<SnapshotEntity>)

    @Upsert
    suspend fun upsertSessions(sessions: List<SessionEntity>)

    @Upsert
    suspend fun upsertEngineState(state: EngineStateEntity)

    @Query("DELETE FROM engine_state")
    suspend fun deleteEngineState()

    // The two counter statements below also exist on CounterDao, which owns the narrower
    // "forget the counters, keep the history" operation. They are repeated here rather than
    // delegated because Room DAOs cannot call one another, and a full clear has to be a
    // single transaction -- see clearAll().

    @Query("DELETE FROM session_counter_state")
    suspend fun deleteSessionCounterState()

    @Query("DELETE FROM counter_capture")
    suspend fun deleteCounterCaptures()

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
     * Removes every row BattInsight durably owns: engine state, counter state, counter
     * captures and their wakelock rows, sessions and snapshots.
     *
     * One transaction, in dependency order derived from the schema rather than from memory.
     * Every foreign key in this database is NO ACTION and therefore immediate, so a child row
     * outliving its parent is not a tidiness problem -- it aborts the delete outright:
     *
     * ```
     * engine_state             -> battery_sessions, battery_snapshots
     * session_counter_state    -> battery_sessions, counter_capture
     * counter_capture          -> battery_sessions
     * kernel_wakelock_counter  -> counter_capture   (CASCADE)
     * partial_wakelock_counter -> counter_capture   (CASCADE)
     * battery_sessions         -> battery_snapshots
     * battery_snapshots        -> (none)
     * ```
     *
     * The two wakelock tables are not deleted explicitly. They are the only children in this
     * schema declared CASCADE, so removing their capture removes them, and the regression test
     * asserts they reach zero rather than trusting that.
     *
     * This method deleted only the first, second-to-last and last of those tables until the
     * Phase 8 audit: Phase 6 wrote it before the counter tables existed, and Phase 7B added
     * four tables pointing at `battery_sessions` without revisiting it. A clear then failed
     * with FOREIGN KEY constraint failed as soon as one counter capture existed.
     */
    @Transaction
    suspend fun clearAll() {
        deleteEngineState()
        deleteSessionCounterState()
        deleteCounterCaptures()
        deleteSessions()
        deleteSnapshots()
    }
}

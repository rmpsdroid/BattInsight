package com.rmpsdroid.battinsight.persistence

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction

/**
 * The only way sampled battery readings reach the database.
 *
 * Insert-and-retain is one transaction, because the cap is a promise about what is
 * observable: a reader must never see 301 rows, not even briefly.
 */
@Dao
interface BatterySampleDao {

    // ------------------------------------------------------------------------ reads

    /**
     * A session's samples, oldest first.
     *
     * Elapsed realtime orders the series and `sample_id` breaks ties, the same determinism
     * rule Phase 8 applied to history paging. Two samples can share a millisecond, and
     * without a stable tiebreak the same query would return them in different orders on
     * different runs.
     */
    @Query(
        """
        SELECT * FROM battery_sample
         WHERE session_id = :sessionId
         ORDER BY sample_elapsed_realtime_millis ASC, sample_id ASC
        """,
    )
    suspend fun samplesFor(sessionId: String): List<BatterySampleEntity>

    @Query(
        """
        SELECT * FROM battery_sample
         WHERE session_id = :sessionId
         ORDER BY sample_elapsed_realtime_millis DESC, sample_id DESC
         LIMIT 1
        """,
    )
    suspend fun lastSampleFor(sessionId: String): BatterySampleEntity?

    @Query("SELECT COUNT(*) FROM battery_sample WHERE session_id = :sessionId")
    suspend fun countFor(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM battery_sample")
    suspend fun totalCount(): Int

    @Query(
        """
        SELECT battery_samples_evicted_through_elapsed_millis
          FROM battery_sessions WHERE session_id = :sessionId
        """,
    )
    suspend fun watermarkFor(sessionId: String): Long?

    // ----------------------------------------------------------------------- writes

    @Insert
    suspend fun insert(sample: BatterySampleEntity)

    /**
     * The elapsed times of the rows a cap of [keep] would remove, newest of them first.
     *
     * Selected by the *same* ordering the series uses, so "oldest" means the same thing to
     * retention and to the reader. Returning the values rather than deleting blind is what
     * lets the watermark record what was actually removed.
     */
    @Query(
        """
        SELECT sample_elapsed_realtime_millis FROM battery_sample
         WHERE session_id = :sessionId
         ORDER BY sample_elapsed_realtime_millis ASC, sample_id ASC
         LIMIT :evictCount
        """,
    )
    suspend fun oldestElapsedTimes(sessionId: String, evictCount: Int): List<Long>

    @Query(
        """
        DELETE FROM battery_sample
         WHERE sample_id IN (
             SELECT sample_id FROM battery_sample
              WHERE session_id = :sessionId
              ORDER BY sample_elapsed_realtime_millis ASC, sample_id ASC
              LIMIT :evictCount
         )
        """,
    )
    suspend fun deleteOldest(sessionId: String, evictCount: Int)

    @Query(
        """
        UPDATE battery_sessions
           SET battery_samples_evicted_through_elapsed_millis =
               MAX(COALESCE(battery_samples_evicted_through_elapsed_millis, :mark), :mark)
         WHERE session_id = :sessionId
        """,
    )
    suspend fun raiseWatermark(sessionId: String, mark: Long)

    @Query("DELETE FROM battery_sample")
    suspend fun deleteAllSamples()

    // ------------------------------------------------------------------ transactions

    /**
     * Stores a sample and enforces the hard cap, atomically.
     *
     * The cap is enforced *after* the insert rather than by refusing the write, because the
     * newest reading is always the one worth keeping. Everything happens in one transaction,
     * so no committed state ever shows more than [cap] rows for the session.
     *
     * The watermark is set from the greatest elapsed time **actually deleted**, never from
     * the oldest surviving row. Recording the survivor would put the mark exactly where the
     * retained series begins, and the read model's `watermark != null` test would describe a
     * gap of zero width. `MAX` in the UPDATE keeps it monotonic across repeated cycles.
     */
    @Transaction
    suspend fun insertAndRetain(sample: BatterySampleEntity, cap: Int) {
        insert(sample)
        val excess = countFor(sample.sessionId) - cap
        if (excess <= 0) return

        val removed = oldestElapsedTimes(sample.sessionId, excess)
        deleteOldest(sample.sessionId, excess)
        removed.maxOrNull()?.let { raiseWatermark(sample.sessionId, it) }
    }
}

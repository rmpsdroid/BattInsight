package com.rmpsdroid.battinsight.series

import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.PersistenceOutcome
import com.rmpsdroid.battinsight.session.SessionTrigger

/**
 * What happened when a sample was offered to the store.
 *
 * [NoActiveSession] is a typed outcome rather than a silent skip because it is a real state
 * with a real cause -- the engine has not reconciled yet -- and a sample must belong to a
 * session that exists. Fabricating a session id to hang a row on would create exactly the
 * orphan the schema's foreign key exists to prevent.
 */
sealed interface SampleResult {
    data class Stored(val sampleId: String) : SampleResult
    data object NoActiveSession : SampleResult
    data object Coalesced : SampleResult
    data class Failed(val outcome: PersistenceOutcome, val detail: String) : SampleResult

    val succeeded: Boolean get() = this is Stored
}

/**
 * The persistence boundary for the sampled battery series.
 *
 * An interface so the series can be exercised without a database, and so no view model or
 * Composable ever holds a DAO. Reads are suspending rather than reactive for the same reason
 * Phase 8 chose that: the series changes when a sample is taken, and the caller taking it
 * already knows.
 */
interface BatterySampleStore {

    /**
     * Stores one reading, enforcing the per-session cap in the same transaction.
     *
     * @param sessionId the session the observation belongs to. The caller resolves this from
     *   the engine *after* the observation has been accepted, so a reading that crossed a
     *   session boundary lands on the session it actually describes.
     */
    suspend fun record(
        sessionId: String,
        observation: BatteryObservation,
        trigger: SessionTrigger,
        counterGeneration: CounterGeneration,
    ): SampleResult

    /** A session's samples, oldest first, as domain points. */
    suspend fun samplesFor(sessionId: String): List<BatterySeriesPoint>

    /** The newest retained sample, or null when the session has none. */
    suspend fun lastSampleFor(sessionId: String): BatterySeriesPoint?

    /** How many samples the session currently retains. */
    suspend fun countFor(sessionId: String): Int

    /**
     * The session's retention watermark: the greatest elapsed time actually deleted.
     *
     * Null means nothing was ever evicted. Non-null is what makes the read model emit a
     * leading [SeriesGapReason.NOT_RETAINED] gap.
     */
    suspend fun evictedThroughElapsedMillis(sessionId: String): Long?

    /** The whole series for a session, already divided into segments and gaps. */
    suspend fun seriesFor(sessionId: String): BatterySeries

    companion object {
        /**
         * The most samples one session retains. **A hard cap**, unlike the counter target.
         *
         * PROVISIONAL UNTIL SUPPORTED PHYSICAL-HARDWARE VALIDATION.
         *
         * At the five-minute cadence, 300 samples is twenty-five hours of continuous
         * lifecycle-visible observation -- far more visible UI time than any real session
         * accumulates, so this is a runaway guard rather than an expected limit. At the 343
         * bytes a sample was measured to cost, a session at the cap holds about 103 KB.
         *
         * Hard, where the counter target is soft, because the two differ in kind: samples come
         * from a timer and each is individually disposable, while a counter capture may be the
         * only evidence that Android's accounting broke.
         */
        const val MAX_BATTERY_SAMPLES_PER_SESSION = 300

        /**
         * Production sampling cadence while the UI is visible.
         *
         * Injectable at the call site rather than read from here directly, so tests can drive
         * a short cadence without changing what ships.
         */
        const val BATTERY_SAMPLE_CADENCE_MILLIS = 5L * 60L * 1000L
    }
}

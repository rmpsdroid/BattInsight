package com.rmpsdroid.battinsight.series

import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.SessionTrigger

/**
 * Decides when a battery reading becomes a stored sample.
 *
 * ## Lifecycle-visible, not background, and not "while the app is alive"
 *
 * The cadence this drives runs **only while the UI is visible** -- the caller starts it inside
 * `repeatOnLifecycle(STARTED)` and that block is cancelled on `STOPPED`. Those three phrases
 * are not synonyms and Phase 9A used them interchangeably by mistake: a process can be alive
 * with no visible UI for a long time, and BattInsight's existing broadcast collection is
 * genuinely process-lifetime.
 *
 * Nothing here wakes a dead process. There is no service, no `WorkManager`, no alarm and no
 * manifest receiver. The consequence is real and accepted: the series will have large gaps
 * covering most of every day, and [BatterySeriesBuilder] renders them as gaps rather than
 * pretending otherwise.
 *
 * ## Coalescing
 *
 * An **accepted observation is always recorded** -- a level change is the most informative
 * moment available and it arrives for free on a broadcast that was going to happen anyway.
 * A **cadence tick is recorded only if nothing has been stored for this session within the
 * cadence**, which is what stops a timer tick and a broadcast landing milliseconds apart from
 * producing two near-identical rows.
 *
 * The asymmetry is deliberate: dropping an observation because a tick happened nearby would
 * discard the more informative of the two.
 *
 * ## Pure of Android
 *
 * The cadence is a parameter and the clock is injected, so the rules are testable on the JVM
 * without a real five-minute wait and without changing what production ships.
 */
class BatterySampler(
    private val store: BatterySampleStore,
    private val cadenceMillis: Long = BatterySampleStore.BATTERY_SAMPLE_CADENCE_MILLIS,
) {

    /**
     * Records a reading the session engine has already accepted.
     *
     * @param sessionId resolved by the caller **after** the engine accepted the observation,
     *   so a reading that crossed a session boundary is attributed to the session it actually
     *   describes rather than the one that was active when it arrived.
     */
    suspend fun onObservation(
        sessionId: String?,
        observation: BatteryObservation,
        counterGeneration: CounterGeneration,
    ): SampleResult {
        val id = sessionId ?: return SampleResult.NoActiveSession
        return store.record(id, observation, observation.trigger, counterGeneration)
    }

    /**
     * Records a cadence tick, unless a sample already covers this window.
     *
     * Returns [SampleResult.Coalesced] rather than storing a near-duplicate. That is a normal
     * outcome, not a failure: it means a broadcast already told us what the timer was about
     * to ask.
     */
    suspend fun onCadenceTick(
        sessionId: String?,
        observation: BatteryObservation,
        counterGeneration: CounterGeneration,
    ): SampleResult {
        val id = sessionId ?: return SampleResult.NoActiveSession

        val last = store.lastSampleFor(id)
        if (last != null) {
            val since = observation.time.elapsedRealtime.millis - last.elapsedRealtimeMillis
            // `since < 0` would mean the stored sample is in this reading's future, which
            // within one boot cannot happen. Treated as "covered" rather than written, so a
            // contradictory clock cannot append a row that reorders the series.
            if (since in 0 until cadenceMillis) return SampleResult.Coalesced
        }
        return store.record(id, observation, SessionTrigger.PERIODIC, counterGeneration)
    }
}

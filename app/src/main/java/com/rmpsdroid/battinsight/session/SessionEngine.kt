package com.rmpsdroid.battinsight.session

import java.util.UUID

/**
 * Everything the engine knows, at one instant.
 *
 * Immutable, so a transition is a value returned rather than a mutation performed, and a
 * rejected observation genuinely cannot have moved anything.
 */
data class SessionEngineState(
    val session: BatterySession?,
    /** The last observation accepted. Rejected ones never become this. */
    val lastAccepted: BatterySnapshot?,
    val counterGeneration: CounterGeneration,
) {
    companion object {
        val empty = SessionEngineState(
            session = null,
            lastAccepted = null,
            counterGeneration = CounterGeneration.INITIAL,
        )
    }
}

/** What an observation did. */
sealed interface TransitionResult {

    /** No session existed; one now does. */
    data class Started(val session: BatterySession, val trigger: SessionTrigger) : TransitionResult

    /** The observation belonged to the running session and moved it forward. */
    data class Continued(val session: BatterySession) : TransitionResult

    /**
     * One interval ended and another began.
     *
     * [reason] distinguishes a transition that was *observed* from one that was *inferred*
     * at cold start, which is a distinction the user is entitled to.
     */
    data class Boundary(
        val ended: BatterySession,
        val started: BatterySession,
        val reason: SessionBoundaryReason,
        val trigger: SessionTrigger,
    ) : TransitionResult

    /**
     * Accepted, but nothing about the session changed.
     *
     * The idempotent path: a repeated `ACTION_BATTERY_CHANGED` saying the same thing, or a
     * `ACTION_POWER_CONNECTED` immediately followed by a `BATTERY_CHANGED` describing the
     * same physical event. Session identity must survive both.
     */
    data class Unchanged(val session: BatterySession) : TransitionResult

    /** The observation was refused and state is untouched. */
    data class Rejected(val reason: RejectionReason, val detail: String) : TransitionResult

    enum class RejectionReason {
        /** Monotonic time moved backwards within one boot. */
        OUT_OF_ORDER,

        /** Saved state contradicted itself. */
        INCONSISTENT_STATE,
    }

    val sessionOrNull: BatterySession?
        get() = when (this) {
            is Started -> session
            is Continued -> session
            is Boundary -> started
            is Unchanged -> session
            is Rejected -> null
        }
}

/** A state change and its explanation, returned together so neither can be lost. */
data class SessionTransition(
    val state: SessionEngineState,
    val result: TransitionResult,
)

/**
 * The battery session state machine.
 *
 * Pure. No Android, no clock, no I/O, no randomness beyond the identifier factory it is
 * handed. Every decision in this file is a function of its arguments, which is what makes
 * the forty-odd lifecycle scenarios testable on the JVM in milliseconds.
 *
 * ## What owns a session boundary
 *
 * Power attachment, and nothing else. Not battery level, not `dumpsys batterystats`, not
 * the application's own lifetime.
 *
 * Level is explicitly excluded: it is noisy, it is recalibrated by the platform, it freezes
 * for long periods, and it jumps. A level that rose does not mean charging began, and one
 * that fell does not mean it ended. Level is carried on the observation for diagnostics and
 * never consulted here.
 *
 * Batterystats `dsd`/`csd` records are excluded too. They were absent entirely on the
 * Android 16 emulator measured in Phase 1A, and a session model that depended on them would
 * have no answer on that device. They may corroborate later; they will never decide.
 *
 * ## What is not a session boundary
 *
 * The application process dying and restarting is not one. A reboot *is*, because the
 * monotonic timeline the interval is measured on has restarted -- so the old interval
 * cannot continue as the same measurable thing, whatever the user was doing with the cable.
 *
 * A counter reset is not one either. Counters resetting mid-discharge does not plug the
 * device in.
 */
class SessionEngine(
    /** Injected so tests get deterministic identifiers. */
    private val newId: () -> UUID = UUID::randomUUID,
) {

    /**
     * Accepts a live observation.
     *
     * For the first observation after a process start, prefer [reconcile]: it can consider
     * previously saved state, which this cannot.
     */
    fun accept(state: SessionEngineState, observation: BatteryObservation): SessionTransition {
        val ordering = checkOrdering(state, observation)
        if (ordering != null) return SessionTransition(state, ordering)

        val bootRelation = state.lastAccepted
            ?.bootIdentity
            ?.relationTo(observation.bootIdentity)

        // A boot change invalidates the monotonic timeline, so the old interval cannot
        // continue even if the cable never moved.
        if (bootRelation == BootRelation.DIFFERENT) {
            return bootBoundary(state, observation)
        }

        val active = state.session ?: return start(state, observation, observation.trigger)

        val newType = SessionType.forAttachment(observation.powerAttachment)

        // Unknown attachment is not evidence of a transition. It is the absence of
        // evidence, and ending a valid interval on it would lose a real measurement.
        if (newType == SessionType.UNKNOWN) {
            return SessionTransition(
                advance(state, active, observation),
                TransitionResult.Continued(nextSession(active, snapshotFor(state, observation, active.id))),
            )
        }

        if (newType == active.type) {
            // The idempotent path. Driven by resulting state, not by which broadcast
            // arrived, so POWER_CONNECTED followed by BATTERY_CHANGED(CHARGING) is one
            // transition and not two.
            val snapshot = snapshotFor(state, observation, active.id)
            val moved = nextSession(active, snapshot)
            val advanced = state.copy(session = moved, lastAccepted = snapshot)
            return SessionTransition(
                advanced,
                if (observation.powerAttachment == active.attachmentAtStart) {
                    TransitionResult.Unchanged(moved)
                } else {
                    TransitionResult.Continued(moved)
                },
            )
        }

        return transition(
            state,
            active,
            observation,
            SessionBoundaryReason.POWER_TRANSITION,
            observation.trigger,
        )
    }

    /**
     * Establishes state at process start, considering what was saved before.
     *
     * The application cannot observe anything while its process does not exist, so this is
     * where correctness after process death actually comes from. It is careful to
     * distinguish three different situations that a naive implementation collapses:
     *
     *  - the process restarted and nothing changed -- the interval continues, same identity;
     *  - the process restarted and something changed while it was gone -- a boundary, marked
     *    [SessionTrigger.RECOVERY], because no broadcast was seen and claiming otherwise
     *    would be a lie about provenance;
     *  - the device rebooted -- the previous interval cannot continue on a restarted clock.
     */
    fun reconcile(
        previous: SessionEngineState?,
        observation: BatteryObservation,
    ): SessionTransition {
        val prior = previous?.session
            ?: return start(
                previous ?: SessionEngineState.empty,
                observation,
                SessionTrigger.APP_START,
            )

        val base = previous
        val relation = prior.latest.bootIdentity.relationTo(observation.bootIdentity)

        if (relation == BootRelation.DIFFERENT) {
            return bootBoundary(base, observation)
        }

        if (relation == BootRelation.UNKNOWN) {
            // Continuity cannot be established, so it is not claimed. A fresh interval
            // starts rather than an old one being silently adopted onto a clock that may
            // not be the same clock.
            val ended = prior.copy(
                end = prior.latest,
                endReason = SessionBoundaryReason.INCONSISTENT_STATE,
            )
            val started = openSession(
                base.counterGeneration,
                observation,
                SessionTrigger.RECOVERY,
            )
            return SessionTransition(
                base.copy(session = started, lastAccepted = started.start),
                TransitionResult.Boundary(
                    ended,
                    started,
                    SessionBoundaryReason.INCONSISTENT_STATE,
                    SessionTrigger.RECOVERY,
                ),
            )
        }

        // Same boot from here on.
        if (observation.time.elapsedRealtime < prior.latest.time.elapsedRealtime) {
            return SessionTransition(
                base,
                TransitionResult.Rejected(
                    TransitionResult.RejectionReason.INCONSISTENT_STATE,
                    "Saved state claims a later monotonic time (" +
                        "${prior.latest.time.elapsedRealtime.millis}) than the current reading " +
                        "(${observation.time.elapsedRealtime.millis}) on the same start-up.",
                ),
            )
        }

        val currentType = SessionType.forAttachment(observation.powerAttachment)

        if (currentType == SessionType.UNKNOWN || currentType == prior.type) {
            // Nothing observable changed while the process was gone, so the interval is
            // the same interval and keeps its identity. Process death is not a boundary.
            val snapshot = snapshotFor(base, observation, prior.id, SessionTrigger.APP_START)
            val moved = nextSession(prior, snapshot)
            return SessionTransition(
                base.copy(session = moved, lastAccepted = snapshot),
                TransitionResult.Continued(moved),
            )
        }

        // Something changed while nothing was watching. The boundary is real, but it was
        // inferred, and it says so.
        return transition(
            base,
            prior,
            observation,
            SessionBoundaryReason.RECOVERY,
            SessionTrigger.RECOVERY,
        )
    }

    /**
     * Records that platform counters restarted.
     *
     * Deliberately not a session boundary. The device did not change what it is doing; only
     * the numbers Android reports did. The interval keeps its identity and its start time,
     * and snapshots taken after this point simply refuse to be subtracted from ones taken
     * before.
     *
     * No production detector calls this yet -- that needs the decoder. The transition is
     * modelled now so the comparability rules have something real to be tested against.
     */
    fun noteCounterReset(
        state: SessionEngineState,
        change: CounterGenerationChange,
    ): SessionEngineState = if (change == CounterGenerationChange.NONE) {
        state
    } else {
        state.copy(counterGeneration = state.counterGeneration.next())
    }

    // ------------------------------------------------------------------------- internals

    private fun checkOrdering(
        state: SessionEngineState,
        observation: BatteryObservation,
    ): TransitionResult? {
        val last = state.lastAccepted ?: return null
        if (last.bootIdentity.relationTo(observation.bootIdentity) != BootRelation.SAME) return null
        if (observation.time.elapsedRealtime >= last.time.elapsedRealtime) return null

        // Within one boot the monotonic clock cannot go backwards, so this observation is
        // stale or duplicated out of order. Accepting it would rewind the session.
        return TransitionResult.Rejected(
            TransitionResult.RejectionReason.OUT_OF_ORDER,
            "Reading is older than the last accepted one (" +
                "${observation.time.elapsedRealtime.millis} before ${last.time.elapsedRealtime.millis}) " +
                "on the same start-up.",
        )
    }

    private fun start(
        state: SessionEngineState,
        observation: BatteryObservation,
        trigger: SessionTrigger,
    ): SessionTransition {
        val session = openSession(state.counterGeneration, observation, trigger)
        return SessionTransition(
            state.copy(session = session, lastAccepted = session.start),
            TransitionResult.Started(session, trigger),
        )
    }

    private fun bootBoundary(
        state: SessionEngineState,
        observation: BatteryObservation,
    ): SessionTransition {
        val ended = state.session?.copy(
            end = state.session.latest,
            endReason = SessionBoundaryReason.BOOT_BOUNDARY,
        )
        // A new boot always restarts platform counters, so the generation moves with it.
        val generation = state.counterGeneration.next()
        val started = openSession(generation, observation, SessionTrigger.BOOT_CHANGED)
        val next = state.copy(
            session = started,
            lastAccepted = started.start,
            counterGeneration = generation,
        )
        return SessionTransition(
            next,
            if (ended == null) {
                TransitionResult.Started(started, SessionTrigger.BOOT_CHANGED)
            } else {
                TransitionResult.Boundary(
                    ended,
                    started,
                    SessionBoundaryReason.BOOT_BOUNDARY,
                    SessionTrigger.BOOT_CHANGED,
                )
            },
        )
    }

    private fun transition(
        state: SessionEngineState,
        active: BatterySession,
        observation: BatteryObservation,
        reason: SessionBoundaryReason,
        trigger: SessionTrigger,
    ): SessionTransition {
        val closingSnapshot = snapshotFor(state, observation, active.id, trigger)
        val ended = active.copy(end = closingSnapshot, endReason = reason)
        val started = openSession(state.counterGeneration, observation, trigger)
        return SessionTransition(
            state.copy(session = started, lastAccepted = started.start),
            TransitionResult.Boundary(ended, started, reason, trigger),
        )
    }

    private fun openSession(
        generation: CounterGeneration,
        observation: BatteryObservation,
        trigger: SessionTrigger,
    ): BatterySession {
        val sessionId = newId()
        val snapshot = BatterySnapshot(
            id = newId(),
            sessionId = sessionId,
            bootIdentity = observation.bootIdentity,
            time = observation.time,
            trigger = trigger,
            battery = observation,
            counterGeneration = generation,
        )
        return BatterySession(
            id = sessionId,
            type = SessionType.forAttachment(observation.powerAttachment),
            start = snapshot,
            latest = snapshot,
            counterGeneration = generation,
        )
    }

    private fun snapshotFor(
        state: SessionEngineState,
        observation: BatteryObservation,
        sessionId: UUID,
        trigger: SessionTrigger = observation.trigger,
    ) = BatterySnapshot(
        id = newId(),
        sessionId = sessionId,
        bootIdentity = observation.bootIdentity,
        time = observation.time,
        trigger = trigger,
        battery = observation,
        counterGeneration = state.counterGeneration,
    )

    private fun nextSession(active: BatterySession, snapshot: BatterySnapshot) =
        active.copy(latest = snapshot)

    private fun advance(
        state: SessionEngineState,
        active: BatterySession,
        observation: BatteryObservation,
    ): SessionEngineState {
        val snapshot = snapshotFor(state, observation, active.id)
        return state.copy(session = nextSession(active, snapshot), lastAccepted = snapshot)
    }
}

/** The attachment the session began under. Used to tell "unchanged" from "moved forward". */
private val BatterySession.attachmentAtStart: PowerAttachment
    get() = start.battery.powerAttachment

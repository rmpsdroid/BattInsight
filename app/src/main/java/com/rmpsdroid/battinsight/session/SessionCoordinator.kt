package com.rmpsdroid.battinsight.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the rest of the application sees of the session engine. */
data class SessionStatus(
    val session: BatterySession?,
    val lastObservation: BatteryObservation?,
    val counterGeneration: CounterGeneration,
    val bootIdentity: BootIdentity,
    /** What the most recent observation did. Null before anything arrives. */
    val lastResult: TransitionResult? = null,
    /**
     * How the last durable write went.
     *
     * Present so the UI can tell "this is what BattInsight believes and has stored" from
     * "this is what it believes and could not store". Null before anything has been written.
     */
    val persistence: PersistenceResult? = null,
    /**
     * What was loaded at start-up, when that failed.
     *
     * Null when loading succeeded or found nothing. An unreadable store is not the same as
     * an empty one, and the difference is visible rather than smoothed away.
     */
    val loadFailure: StoredState.Failed? = null,
) {
    val isActive: Boolean get() = session?.isActive == true

    companion object {
        val unknown = SessionStatus(
            session = null,
            lastObservation = null,
            counterGeneration = CounterGeneration.INITIAL,
            bootIdentity = BootIdentity.Unknown,
        )
    }
}

/**
 * Feeds observations into [SessionEngine] and publishes the result.
 *
 * A thin thing on purpose. It owns sequencing and publication; every decision about what an
 * observation *means* belongs to the engine, which is pure and therefore testable without
 * any of this.
 *
 * Not responsible for storage, parsing, permissions or rendering. In particular it holds no
 * reference to the capability layer: a battery session is a fact about the device, and it
 * must not shift because the user changed access method or a permission was revoked.
 *
 * Observations are serialised through a [Mutex] so two broadcasts arriving together cannot
 * interleave a read-modify-write and lose one.
 */
class SessionCoordinator(
    private val engine: SessionEngine = SessionEngine(),
    private val store: SessionStateStore = InMemorySessionStateStore(),
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    @Volatile
    private var state: SessionEngineState = SessionEngineState.empty

    private val _status = MutableStateFlow(SessionStatus.unknown)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    /**
     * Whether persisted state has been read and reconciled in this coordinator's lifetime.
     *
     * Guarded by [mutex]; every read and write happens inside it, so no separate memory
     * barrier is needed and no check-then-act window exists.
     */
    private var initialised = false

    /**
     * Establishes state at start-up from saved state plus a current reading.
     *
     * Everything the application knows about transitions it did not witness comes from here.
     *
     * **Ordering is enforced, not requested.** This used to document "call once per process,
     * before [observe]" and rely on callers to honour it. Phase 10A.2 measured a Samsung
     * SM-M156B splitting one continuous discharge interval into two open sessions because
     * production could not honour it: the start-up reading and the lifecycle-visible sampler
     * run in two independently scheduled coroutines, and when the sampler won,
     * [SessionEngine.accept] saw [SessionEngineState.empty] and started a fresh interval on
     * top of a perfectly readable stored one.
     *
     * Initialisation is therefore a property of this type rather than a convention. Whichever
     * of [begin] or [observe] arrives first performs the load-and-reconcile; a later [begin]
     * is an ordinary observation, because the process has already started exactly once.
     */
    suspend fun begin(observation: BatteryObservation): TransitionResult = mutex.withLock {
        if (initialised) acceptLocked(observation) else initialiseLocked(observation)
    }

    /**
     * Accepts a live observation.
     *
     * If this is the first call to reach the coordinator, it performs initialisation rather
     * than being accepted against empty state -- see [begin]. The observation is not
     * discarded: reconciliation is defined as "saved state plus a current reading", and an
     * early observation is exactly such a reading.
     */
    suspend fun observe(observation: BatteryObservation): TransitionResult = mutex.withLock {
        if (initialised) acceptLocked(observation) else initialiseLocked(observation)
    }

    /**
     * Reads persisted state and reconciles [observation] against it. Call under [mutex].
     *
     * Deliberately not a suspending wait on some other coroutine's initialisation: there is
     * nothing to wait for and therefore nothing to dead-lock on, nothing to cancel, and no
     * timeout to tune. The first caller through either door does the work.
     */
    private suspend fun initialiseLocked(observation: BatteryObservation): TransitionResult {
        val stored = store.load()

        // An unreadable store is not an empty one. Reconciling from null would start a fresh
        // interval and quietly discard whatever was there; instead the failure is carried
        // through to the UI, and reconciliation proceeds from nothing *knowingly*.
        val loadFailure = stored as? StoredState.Failed
        val saved = (stored as? StoredState.Loaded)?.state

        val transition = engine.reconcile(saved, observation)
        // Only when the reconciliation was actually adopted. A rejected reading (a
        // contradictory monotonic clock) or a failed write adopts nothing, and calling the
        // coordinator "initialised" then would hand the next observation an empty state --
        // reintroducing the very split this fix removes, one step later.
        initialised = commit(transition, observation, loadFailure)
        return transition.result
    }

    /** Accepts an observation against already-initialised state. Call under [mutex]. */
    private suspend fun acceptLocked(observation: BatteryObservation): TransitionResult {
        val transition = engine.accept(state, observation)
        commit(transition, observation)
        return transition.result
    }

    /** Accepts an observation without suspending the caller. For broadcast receivers. */
    fun observeAsync(observation: BatteryObservation) {
        scope.launch { observe(observation) }
    }

    /**
     * Records that platform counters restarted.
     *
     * Does not end the session -- see [SessionEngine.noteCounterReset]. Nothing calls this
     * in production yet; the detector needs the decoder.
     */
    suspend fun noteCounterReset(change: CounterGenerationChange): PersistenceResult =
        mutex.withLock {
            val next = engine.noteCounterReset(state, change)
            val result = store.saveState(next)
            if (result.succeeded) {
                state = next
            }
            publish(_status.value.lastObservation, _status.value.lastResult, result)
            result
        }

    /**
     * Applies a transition, but only if it can be stored.
     *
     * The order is the point. Persist first, adopt second, publish third -- so the in-memory
     * state and the database cannot diverge, and the UI never shows a session that was not
     * written. A failed write leaves the previous state in force and reports the failure;
     * the alternative, carrying on in memory, would produce an application confidently
     * describing history it will not have after the next process death.
     */
    /**
     * @return whether [transition] was adopted into [state]. Initialisation depends on this:
     *   a reconciliation that was rejected or could not be saved has adopted nothing, so the
     *   coordinator is *not* initialised and must reconcile again rather than accept the next
     *   observation against empty state.
     */
    private suspend fun commit(
        transition: SessionTransition,
        observation: BatteryObservation,
        loadFailure: StoredState.Failed? = null,
    ): Boolean {
        // A rejected observation leaves state untouched, and must not be saved or published
        // as though it had been accepted.
        if (transition.result is TransitionResult.Rejected) {
            publish(_status.value.lastObservation, transition.result, _status.value.persistence, loadFailure)
            return false
        }

        val result = store.persist(transition)
        if (!result.succeeded) {
            // Nothing is adopted. The previous state remains authoritative, and the failure
            // is visible rather than swallowed.
            publish(_status.value.lastObservation, _status.value.lastResult, result, loadFailure)
            return false
        }

        state = transition.state
        publish(observation, transition.result, result, loadFailure)
        return true
    }

    private fun publish(
        observation: BatteryObservation?,
        result: TransitionResult?,
        persistence: PersistenceResult? = _status.value.persistence,
        loadFailure: StoredState.Failed? = _status.value.loadFailure,
    ) {
        _status.value = SessionStatus(
            session = state.session,
            lastObservation = observation,
            counterGeneration = state.counterGeneration,
            bootIdentity = state.lastAccepted?.bootIdentity ?: BootIdentity.Unknown,
            lastResult = result,
            persistence = persistence,
            loadFailure = loadFailure,
        )
    }
}

/**
 * Where battery observations come from.
 *
 * An interface so the engine, the coordinator and every lifecycle scenario run without
 * Android. The platform implementation is an adapter and nothing more.
 */
interface BatteryObservationSource {

    /** Reads current state once, without registering anything. */
    suspend fun readCurrent(trigger: SessionTrigger = SessionTrigger.APP_START): BatteryObservation?

    /**
     * Observations as they arrive.
     *
     * Collecting registers whatever the platform needs; cancelling unregisters it. Nothing
     * is left running when nobody is listening.
     */
    fun observations(): Flow<BatteryObservation>
}

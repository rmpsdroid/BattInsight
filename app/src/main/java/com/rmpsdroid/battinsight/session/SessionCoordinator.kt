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
     * Establishes state at start-up from saved state plus a current reading.
     *
     * Call once per process, before [observe]. Everything the application knows about
     * transitions it did not witness comes from here.
     */
    suspend fun begin(observation: BatteryObservation): TransitionResult = mutex.withLock {
        val stored = store.load()

        // An unreadable store is not an empty one. Reconciling from null would start a fresh
        // interval and quietly discard whatever was there; instead the failure is carried
        // through to the UI, and reconciliation proceeds from nothing *knowingly*.
        val loadFailure = stored as? StoredState.Failed
        val saved = (stored as? StoredState.Loaded)?.state

        val transition = engine.reconcile(saved, observation)
        commit(transition, observation, loadFailure)
        transition.result
    }

    /** Accepts a live observation. */
    suspend fun observe(observation: BatteryObservation): TransitionResult = mutex.withLock {
        val transition = engine.accept(state, observation)
        commit(transition, observation)
        transition.result
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
    private suspend fun commit(
        transition: SessionTransition,
        observation: BatteryObservation,
        loadFailure: StoredState.Failed? = null,
    ) {
        // A rejected observation leaves state untouched, and must not be saved or published
        // as though it had been accepted.
        if (transition.result is TransitionResult.Rejected) {
            publish(_status.value.lastObservation, transition.result, _status.value.persistence, loadFailure)
            return
        }

        val result = store.persist(transition)
        if (!result.succeeded) {
            // Nothing is adopted. The previous state remains authoritative, and the failure
            // is visible rather than swallowed.
            publish(_status.value.lastObservation, _status.value.lastResult, result, loadFailure)
            return
        }

        state = transition.state
        publish(observation, transition.result, result, loadFailure)
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

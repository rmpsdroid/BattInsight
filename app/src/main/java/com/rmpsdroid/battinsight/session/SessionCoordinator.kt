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
        val saved = store.load()
        val transition = engine.reconcile(saved, observation)
        commit(transition, observation)
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
    suspend fun noteCounterReset(change: CounterGenerationChange) = mutex.withLock {
        state = engine.noteCounterReset(state, change)
        store.save(state)
        publish(_status.value.lastObservation, _status.value.lastResult)
    }

    private suspend fun commit(transition: SessionTransition, observation: BatteryObservation) {
        // A rejected observation leaves state untouched, and must not be saved or published
        // as though it had been accepted.
        if (transition.result is TransitionResult.Rejected) {
            publish(_status.value.lastObservation, transition.result)
            return
        }
        state = transition.state
        store.save(state)
        publish(observation, transition.result)
    }

    private fun publish(observation: BatteryObservation?, result: TransitionResult?) {
        _status.value = SessionStatus(
            session = state.session,
            lastObservation = observation,
            counterGeneration = state.counterGeneration,
            bootIdentity = state.lastAccepted?.bootIdentity ?: BootIdentity.Unknown,
            lastResult = result,
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

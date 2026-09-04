package com.rmpsdroid.battinsight.session

/**
 * What went wrong when durable state could not be read or written.
 *
 * Typed because a diagnostics application that silently loses its own measurements has no
 * business telling anyone about theirs. Each value names a different failure with a
 * different response: a constraint violation is a bug in our write ordering, a mapping
 * failure means stored data we cannot interpret, and an unavailable database is usually
 * transient.
 *
 * Deliberately free of SQL vocabulary. The underlying exception detail is carried alongside
 * for diagnostics, never rendered as ordinary UI.
 */
enum class PersistenceOutcome {
    /** The operation completed and is durable. */
    SUCCESS,

    /** A foreign key, uniqueness or other integrity constraint refused the write. */
    CONSTRAINT_FAILURE,

    /** Stored data could not be turned back into a domain object, or vice versa. */
    MAPPING_FAILURE,

    /** The database could not be opened or was closed underneath us. */
    DATABASE_UNAVAILABLE,

    /** Stored state is internally inconsistent -- for example referencing a missing row. */
    CORRUPT_STATE,

    /** A schema migration was required and could not be performed. */
    MIGRATION_FAILURE,

    /** Something else failed. */
    UNKNOWN,
}

/**
 * The result of a durable write.
 *
 * Never a bare boolean, and never swallowed. Phase 6 exists so session identity survives
 * process death; a write that quietly failed would leave the application confidently
 * reporting a session it can no longer recover.
 */
sealed interface PersistenceResult {

    data object Success : PersistenceResult

    /**
     * @param outcome what kind of failure, for the caller to act on.
     * @param detail engineer-facing description. Kept out of ordinary UI.
     */
    data class Failure(val outcome: PersistenceOutcome, val detail: String) : PersistenceResult

    val succeeded: Boolean get() = this is Success

    val failureOrNull: Failure? get() = this as? Failure
}

/** What a load found. Three answers, because "nothing" and "could not tell" differ. */
sealed interface StoredState {

    data class Loaded(val state: SessionEngineState) : StoredState

    /** Nothing has been stored yet. A fresh install, or after an explicit clear. */
    data object Empty : StoredState

    /**
     * State exists but could not be read.
     *
     * Distinct from [Empty] on purpose. Treating an unreadable store as empty would start a
     * fresh interval and quietly discard whatever was there, which is the predecessor's
     * behaviour this project exists to avoid.
     */
    data class Failed(val outcome: PersistenceOutcome, val detail: String) : StoredState
}

/**
 * Where engine state is kept between process lifetimes.
 *
 * Phase 5 declared this seam with only an in-memory implementation. Phase 6 backs it with
 * Room, which is what makes session identity survive ordinary process death.
 *
 * The interface stays free of Room, Android and SQL entirely, so the session engine remains
 * pure and every lifecycle rule remains testable on the JVM.
 *
 * ## Why [persist] takes a transition rather than a state
 *
 * A boundary produces two durable facts at once: the interval that ended and the one that
 * began. [SessionEngineState] carries only the active session, so saving it alone would lose
 * the closed one. Passing the whole transition lets the implementation write both -- plus
 * their snapshots and the new engine state -- inside a single transaction, which is the only
 * way a crash mid-write cannot leave two active sessions or none at all.
 */
interface SessionStateStore {

    /** Reads durable state. */
    suspend fun load(): StoredState

    /**
     * Durably records everything one transition changed, atomically.
     *
     * All or nothing: the ended session, the started or continued session, their snapshots,
     * and the current engine state either all commit or none do.
     */
    suspend fun persist(transition: SessionTransition): PersistenceResult

    /**
     * Records a state change that came from no transition.
     *
     * Currently only a counter-generation change, which alters engine state without ending
     * or starting an interval.
     */
    suspend fun saveState(state: SessionEngineState): PersistenceResult

    /** Forgets everything. Used when saved state is established to be inconsistent. */
    suspend fun clear(): PersistenceResult
}

/**
 * A store that keeps state for the life of the process and no longer.
 *
 * Retained after Phase 6 because it is what the pure engine tests run against: they must not
 * need a database to check a transition rule. It is also the honest fallback if a database
 * cannot be opened at all -- the application still works for the current process, and says
 * that persistence is unavailable rather than pretending it saved.
 */
class InMemorySessionStateStore(
    initial: SessionEngineState? = null,
) : SessionStateStore {

    @Volatile
    private var state: SessionEngineState? = initial

    override suspend fun load(): StoredState =
        state?.let { StoredState.Loaded(it) } ?: StoredState.Empty

    override suspend fun persist(transition: SessionTransition): PersistenceResult {
        state = transition.state
        return PersistenceResult.Success
    }

    override suspend fun saveState(state: SessionEngineState): PersistenceResult {
        this.state = state
        return PersistenceResult.Success
    }

    override suspend fun clear(): PersistenceResult {
        state = null
        return PersistenceResult.Success
    }
}

package com.rmpsdroid.battinsight.session

/**
 * Where engine state is kept between process lifetimes.
 *
 * **Phase 5 has no durable implementation and deliberately ships none.** Persistence is
 * Phase 6's subject, and doing it properly means a versioned schema with tested
 * migrations -- the predecessor stored snapshots as a single opaque blob with no version,
 * and an update destroyed every user's history.
 *
 * Writing the model to preferences or ad-hoc JSON in the meantime would create exactly that
 * blob, and the pressure to keep reading it later would outlive the phase. So the seam is
 * declared, an in-memory implementation satisfies it, and cold-start reconciliation is
 * written and tested against the seam rather than against storage.
 *
 * The honest consequence, stated rather than hidden: with only [InMemorySessionStateStore],
 * state does not survive process death, so every cold start reconciles from nothing and
 * begins a fresh interval. The reconciliation logic that will make that unnecessary already
 * exists and is tested; it simply has nothing to load yet.
 */
interface SessionStateStore {

    /** The last saved state, or null if there is none. */
    suspend fun load(): SessionEngineState?

    /** Records current state. */
    suspend fun save(state: SessionEngineState)

    /** Forgets everything. Used when saved state is established to be inconsistent. */
    suspend fun clear()
}

/**
 * A store that keeps state for the life of the process and no longer.
 *
 * The only implementation in Phase 5. Being obviously non-durable is a feature: nothing can
 * mistake it for persistence, and no migration debt accrues before there is a schema to
 * migrate.
 */
class InMemorySessionStateStore(
    initial: SessionEngineState? = null,
) : SessionStateStore {

    @Volatile
    private var state: SessionEngineState? = initial

    override suspend fun load(): SessionEngineState? = state

    override suspend fun save(state: SessionEngineState) {
        this.state = state
    }

    override suspend fun clear() {
        state = null
    }
}

package com.rmpsdroid.battinsight.persistence

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import com.rmpsdroid.battinsight.session.BatterySession
import com.rmpsdroid.battinsight.session.BatterySnapshot
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.PersistenceOutcome
import com.rmpsdroid.battinsight.session.PersistenceResult
import com.rmpsdroid.battinsight.session.SessionEngineState
import com.rmpsdroid.battinsight.session.SessionStateStore
import com.rmpsdroid.battinsight.session.SessionTransition
import com.rmpsdroid.battinsight.session.StoredState
import com.rmpsdroid.battinsight.session.TransitionResult
import kotlinx.coroutines.CancellationException

/** How many stored rows exist. Counts only -- never a dump of their contents. */
data class StorageCounts(val sessions: Int, val snapshots: Int)

/**
 * The durable implementation of the Phase 5 persistence seam.
 *
 * Everything a transition changed is written in one transaction, and nothing is reported as
 * saved unless it committed. That pairing is the whole substance of this phase: without the
 * transaction a crash can leave two active sessions, and without honest failure reporting
 * the application can believe it has history it does not have.
 *
 * Exceptions are translated into [PersistenceOutcome] rather than propagated. SQL detail is
 * kept for diagnostics and never becomes ordinary UI text -- a user who is told
 * `FOREIGN KEY constraint failed (code 787)` has been told nothing.
 */
class RoomSessionStateStore(
    private val dao: SessionDao,
) : SessionStateStore {

    // -------------------------------------------------------------------------- reading

    override suspend fun load(): StoredState {
        return try {
            val engineState = dao.engineState()
                ?: return StoredState.Empty

            val sessionEntity = engineState.sessionId?.let { id ->
                dao.session(id) ?: return StoredState.Failed(
                    PersistenceOutcome.CORRUPT_STATE,
                    "engine state references session $id, which is not stored",
                )
            }

            // Fetch every snapshot the graph needs in one query, then resolve locally.
            val wanted = buildSet {
                engineState.lastAcceptedSnapshotId?.let { add(it) }
                sessionEntity?.let {
                    add(it.startSnapshotId)
                    add(it.latestSnapshotId)
                    it.endSnapshotId?.let { end -> add(end) }
                }
            }
            val snapshotsById = if (wanted.isEmpty()) {
                emptyMap()
            } else {
                dao.snapshots(wanted.toList()).associateBy { it.snapshotId }
            }

            val missing = wanted - snapshotsById.keys
            if (missing.isNotEmpty()) {
                return StoredState.Failed(
                    PersistenceOutcome.CORRUPT_STATE,
                    "stored state references ${missing.size} snapshot(s) that are not stored",
                )
            }

            val domainSnapshots: Map<String, BatterySnapshot> =
                snapshotsById.mapValues { (_, entity) -> Mappers.toDomain(entity) }

            val session: BatterySession? = sessionEntity?.let { Mappers.toDomain(it, domainSnapshots) }
            val lastAccepted = engineState.lastAcceptedSnapshotId?.let { domainSnapshots.getValue(it) }

            StoredState.Loaded(
                SessionEngineState(
                    session = session,
                    lastAccepted = lastAccepted,
                    counterGeneration = CounterGeneration(engineState.counterGeneration),
                ),
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val failure = classify(t)
            StoredState.Failed(failure.outcome, failure.detail)
        }
    }

    // -------------------------------------------------------------------------- writing

    override suspend fun persist(transition: SessionTransition): PersistenceResult = write {
        val state = transition.state

        // Everything the transition touched. A boundary changes two sessions, and
        // SessionEngineState carries only the active one -- so the ended interval is taken
        // from the result, which is the only place it exists.
        val sessions = buildList {
            (transition.result as? TransitionResult.Boundary)?.ended?.let { add(it) }
            state.session?.let { add(it) }
        }

        val snapshots = buildSet {
            state.lastAccepted?.let { add(it) }
            sessions.forEach { session ->
                add(session.start)
                add(session.latest)
                session.end?.let { add(it) }
            }
        }

        dao.persistTransition(
            snapshots = snapshots.map(Mappers::toEntity),
            sessions = sessions.map(Mappers::toEntity),
            engineState = engineStateEntity(state),
        )
    }

    override suspend fun saveState(state: SessionEngineState): PersistenceResult = write {
        // A state change with no transition -- currently only a counter-generation change.
        // The rows it references must already exist, and the deferred foreign keys say so
        // at commit if they do not.
        val snapshots = buildSet {
            state.lastAccepted?.let { add(it) }
            state.session?.let {
                add(it.start)
                add(it.latest)
                it.end?.let { end -> add(end) }
            }
        }
        dao.persistTransition(
            snapshots = snapshots.map(Mappers::toEntity),
            sessions = listOfNotNull(state.session).map(Mappers::toEntity),
            engineState = engineStateEntity(state),
        )
    }

    override suspend fun clear(): PersistenceResult = write { dao.clearAll() }

    /** Row counts, for the diagnostics view. Never reads row contents. */
    suspend fun counts(): StorageCounts? = try {
        StorageCounts(sessions = dao.sessionCount(), snapshots = dao.snapshotCount())
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        null
    }

    // ------------------------------------------------------------------------ internals

    private fun engineStateEntity(state: SessionEngineState) = EngineStateEntity(
        sessionId = state.session?.id?.toString(),
        lastAcceptedSnapshotId = state.lastAccepted?.id?.toString(),
        counterGeneration = state.counterGeneration.value,
    )

    private suspend fun write(block: suspend () -> Unit): PersistenceResult = try {
        block()
        PersistenceResult.Success
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        classify(t)
    }

    /**
     * Turns a thrown exception into an outcome a caller can act on.
     *
     * Ordered most specific first. The detail string is for logs and diagnostics; the
     * outcome is what the UI reasons about.
     */
    private fun classify(t: Throwable): PersistenceResult.Failure = when (t) {
        is SnapshotMappingException -> PersistenceResult.Failure(
            PersistenceOutcome.MAPPING_FAILURE,
            t.message ?: "stored data could not be mapped",
        )
        is SQLiteConstraintException -> PersistenceResult.Failure(
            PersistenceOutcome.CONSTRAINT_FAILURE,
            t.message ?: "a database constraint refused the write",
        )
        is IllegalStateException -> {
            // Room throws IllegalStateException for a closed database ("Database is closed",
            // measured on Room 3.0.2) and for a migration that is required but not supplied.
            val message = t.message.orEmpty()
            val outcome = when {
                message.contains("Migration", ignoreCase = true) ->
                    PersistenceOutcome.MIGRATION_FAILURE
                else -> PersistenceOutcome.DATABASE_UNAVAILABLE
            }
            PersistenceResult.Failure(outcome, message.ifBlank { "database unavailable" })
        }
        is SQLiteException -> PersistenceResult.Failure(
            PersistenceOutcome.DATABASE_UNAVAILABLE,
            t.message ?: "the database could not be used",
        )
        else -> PersistenceResult.Failure(
            PersistenceOutcome.UNKNOWN,
            t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""),
        )
    }
}

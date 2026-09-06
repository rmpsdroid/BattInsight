package com.rmpsdroid.battinsight.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one point at which counter-table mutations are serialised.
 *
 * ## Why a lock rather than a transaction
 *
 * Storing a capture is three steps: read the retained topology, compute an eviction plan from
 * it, apply the plan. The middle step is
 * [com.rmpsdroid.battinsight.series.CounterRetentionPolicy], which is pure domain logic and
 * must stay that way -- Phase 9A.2 put it outside persistence deliberately, so its edge cases
 * could be tested without a database.
 *
 * That leaves the read and the apply in separate transactions. Two coroutines can therefore
 * both read the same topology, both plan against it, and both apply -- and the second plan is
 * **stale**. Retention safety is an adjacency property: a plan says "removing capture C leaves
 * a comparable pair (prev, next)", and that claim is only true of the topology it was computed
 * against. Apply it to a different one and the store can silently join two captures that must
 * never be subtracted.
 *
 * Wrapping only the final write transaction would not help: the damage is done by the time the
 * plan is handed over. So the lock spans the **complete read → plan → apply** sequence.
 *
 * A database transaction covering all three was considered first, as the preferred design. It
 * would require either moving the policy into a DAO -- which makes it untestable and
 * reintroduces a second comparison engine inside persistence -- or nesting DAO calls inside
 * `useWriterConnection`, whose confinement semantics would themselves need proving. The mutex
 * gives the same guarantee with less to be wrong about.
 *
 * ## Why process-wide
 *
 * A per-instance lock would not be enough, because there is already more than one
 * [RoomCounterStore]: the view model owns one and `RoomSessionHistoryRepository` builds
 * another. That second one only reads today, but "only one class writes" is not a guarantee --
 * it is an observation about the current call graph, and this lock exists precisely so the
 * guarantee does not depend on that observation staying true.
 *
 * There is exactly one database ([BattInsightDatabase] is a singleton), so one lock covering
 * every store over it is the correct scope.
 *
 * ## What it costs
 *
 * Nothing that matters. Counter captures come only from a deliberate user action, so
 * contention is rare by construction; the lock exists for correctness under concurrency, not
 * for throughput. [Mutex] suspends rather than blocking a thread and is cancellation-correct,
 * so a cancelled capture releases it.
 *
 * Battery samples are **not** serialised here. Their retention is a plain "delete the oldest
 * n", computed and applied inside one Room `@Transaction` with no separate read phase, so it
 * has no stale-plan window to protect.
 */
internal object CounterMutationLock {

    private val mutex = Mutex()

    /** Runs [block] with every other counter mutation excluded. */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    /** Whether a mutation is in progress. Diagnostics and tests only. */
    val isLocked: Boolean get() = mutex.isLocked
}

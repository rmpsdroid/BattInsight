package com.rmpsdroid.battinsight.series

import com.rmpsdroid.battinsight.batterystats.CounterDeltaEngine
import com.rmpsdroid.battinsight.batterystats.StoredCounterCapture

/**
 * Which retained counter captures may safely be removed.
 *
 * Pure policy over the existing comparability engine, deliberately **not** inside the Room
 * store. Two reasons: it is a domain rule rather than a persistence detail, and burying it in
 * a private method would have made the cases below untestable except through a store that
 * refuses some of them before they are ever written.
 *
 * ## Why the target is soft
 *
 * [TARGET_COUNTER_CAPTURES_PER_SESSION] is a target, not a cap. Captures come only from a
 * deliberate user action -- there is no privileged timer -- so overflow is bounded by how many
 * times a person pressed refresh. Keeping one capture too many is a far smaller problem than
 * destroying the evidence that Android's accounting broke, so when nothing can safely go,
 * nothing goes.
 *
 * ## The three comparisons
 *
 * For a candidate `c` between `prev` and `next`, all three must be comparable:
 *
 * ```
 * A = comparability(prev, c)      an existing interval
 * B = comparability(c, next)      an existing interval
 * C = comparability(prev, next)   the adjacency deletion would create
 * ```
 *
 * A and B protect discontinuities already known; C stops a clean-looking one being
 * manufactured. Phase 9A.1 specified C alone, and Phase 9A.2 measured that this evicts exactly
 * the capture it was written to protect:
 *
 * ```
 * prev = 100   c = 50   next = 120
 * ```
 *
 * `prev -> c` is a counter decrease and is refused, but `prev -> next` reads 100 -> 120 and
 * yields a clean **+20** spanning a reset. Metadata behaves the same way whenever a value
 * round-trips -- boot `b1 -> b2 -> b1`, generation `3 -> 4 -> 3`, elapsed `0 -> 5 -> 2` -- so
 * the refusal never reaches C.
 *
 * Nothing is special-cased by reason: the rule consumes whatever `comparability` returns, so a
 * reason added later is covered without touching this code. No verdict is persisted; every
 * comparison is recomputed from stored observations.
 */
object CounterRetentionPolicy {

    /**
     * How many captures a session aims to retain. **A target, not a cap.**
     *
     * PROVISIONAL UNTIL SUPPORTED PHYSICAL-HARDWARE VALIDATION.
     *
     * Eight because captures are manual: a realistic session produces two or three, and eight
     * allows a genuine investigation without unbounded growth. At the ~25 KB an interned
     * capture was measured to cost, that is roughly 200 KB per session at the target.
     */
    const val TARGET_COUNTER_CAPTURES_PER_SESSION = 8

    /** All three comparisons must pass. See the class note. */
    fun isEvictable(
        prev: StoredCounterCapture,
        candidate: StoredCounterCapture,
        next: StoredCounterCapture,
    ): Boolean =
        CounterDeltaEngine.comparability(prev, candidate) == null &&
            CounterDeltaEngine.comparability(candidate, next) == null &&
            CounterDeltaEngine.comparability(prev, next) == null

    /**
     * The capture ids that may be removed to bring a session towards [target].
     *
     * @param series retained captures in elapsed order, **excluding** any incoming capture.
     * @param incomingCount how many captures are about to be added, so the plan accounts for
     *   the size the session is heading for rather than the one it has.
     *
     * Candidates are considered oldest first, and the baseline and the current last capture
     * are never candidates. When no candidate passes, the loop stops and the session keeps
     * more than the target -- deliberately.
     */
    fun evictionPlan(
        series: List<StoredCounterCapture>,
        baselineCaptureId: String,
        incomingCount: Int = 1,
        target: Int = TARGET_COUNTER_CAPTURES_PER_SESSION,
    ): List<String> {
        val retained = series
            .sortedWith(compareBy({ it.captureElapsedRealtimeMillis }, { it.captureId }))
            .toMutableList()
        val evict = mutableListOf<String>()

        while (retained.size + incomingCount > target) {
            val index = (1 until retained.size - 1).firstOrNull { i ->
                retained[i].captureId != baselineCaptureId &&
                    isEvictable(retained[i - 1], retained[i], retained[i + 1])
            } ?: break
            evict += retained.removeAt(index).captureId
        }
        return evict
    }
}

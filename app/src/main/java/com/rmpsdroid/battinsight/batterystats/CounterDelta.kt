package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.BootRelation
import com.rmpsdroid.battinsight.session.Comparability
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.relationTo

/**
 * Why two counter captures may not be subtracted.
 *
 * A superset of the Phase 5 [Comparability.Reason] values rather than a replacement for them.
 * The session engine remains the authority on boot identity, counter generation and time
 * ordering; this adds the reasons that only exist once a *capture format* is involved --
 * which accounting window the numbers came from, which checkin version described them, and
 * whether the accounting window straddles an OS update.
 *
 * Every one of these is a refusal to answer rather than an error. A refusal that can explain
 * itself is a usable answer; a zero that should have been a refusal is a lie.
 */
enum class CounterDeltaReason {
    /** The device restarted between the captures. */
    DIFFERENT_BOOT,

    /** Android's own counters restarted between them. */
    DIFFERENT_COUNTER_GENERATION,

    /** The numbers describe different accounting windows and are not the same quantity. */
    DIFFERENT_ACCOUNTING_WINDOW,

    /** One came from checkin and the other from a different acquisition format. */
    SOURCE_FORMAT_CHANGED,

    /** The checkin version changed, so the record layouts may have too. */
    CHECKIN_VERSION_CHANGED,

    /** The accounting window spans an OS update. */
    PLATFORM_CHANGED,

    /** The later capture has an earlier monotonic time. */
    TIME_REVERSED,

    /** No baseline has been established for this session. */
    BASELINE_MISSING,

    /** No latest capture exists for this session. */
    LATEST_MISSING,

    /** This particular counter is absent from the baseline. Not zero -- absent. */
    COUNTER_MISSING_IN_BASELINE,

    /** This particular counter is absent from the latest capture. Not zero -- absent. */
    COUNTER_MISSING_IN_LATEST,

    /**
     * A cumulative counter went down.
     *
     * Evidence of *something* -- a reset, a window boundary, a source discontinuity -- and
     * proof of nothing in particular, so it is reported rather than interpreted.
     */
    COUNTER_DECREASED,

    /** Stored state is internally inconsistent. */
    MALFORMED_STORED_STATE,

    /** Not established either way. */
    UNKNOWN,
    ;

    companion object {
        /**
         * Lifts a Phase 5 refusal into this vocabulary.
         *
         * The session engine's rules are not restated here. Where it has already decided that
         * two readings are incomparable, that decision is carried through unchanged, so the
         * two layers cannot drift into disagreeing about the same device.
         */
        fun from(reason: Comparability.Reason): CounterDeltaReason = when (reason) {
            Comparability.Reason.DIFFERENT_BOOT -> DIFFERENT_BOOT
            Comparability.Reason.DIFFERENT_COUNTER_GENERATION -> DIFFERENT_COUNTER_GENERATION
            Comparability.Reason.TIME_REVERSED -> TIME_REVERSED
            Comparability.Reason.SOURCE_INCOMPATIBLE -> SOURCE_FORMAT_CHANGED
            Comparability.Reason.SCHEMA_INCOMPATIBLE -> MALFORMED_STORED_STATE
            Comparability.Reason.MISSING_IDENTITY -> UNKNOWN
            Comparability.Reason.UNKNOWN -> UNKNOWN
        }
    }
}

/**
 * The result of subtracting one counter capture from another.
 *
 * Three outcomes, and the difference between the last two matters. [NotComparable] means the
 * two captures may not be subtracted at all; [MissingData] means they may, but this specific
 * counter is not present in both. The first is a statement about the pair, the second about
 * one row, and collapsing them would hide which counters are usable.
 */
sealed interface CounterDeltaResult<out T> {

    data class Success<T>(
        val baselineCaptureId: String,
        val latestCaptureId: String,
        /** Monotonic time between the two captures. */
        val elapsedMillis: Long,
        val value: T,
    ) : CounterDeltaResult<T>

    data class NotComparable(
        val reason: CounterDeltaReason,
        val detail: String,
    ) : CounterDeltaResult<Nothing>

    data class MissingData(
        val reason: CounterDeltaReason,
        val detail: String,
    ) : CounterDeltaResult<Nothing>

    val succeeded: Boolean get() = this is Success<*>
    val valueOrNull: T? get() = (this as? Success<T>)?.value
}

/** What changed for one kernel wakelock between two captures. */
data class KernelWakelockDelta(
    val name: String,
    val window: AggregationWindow,
    val durationDeltaMillis: Long,
    val countDelta: Long,
)

/** What changed for one application wakelock between two captures. */
data class PartialWakelockDelta(
    val uid: Int,
    val name: String,
    val window: AggregationWindow,
    val durationDeltaMillis: Long,
    val countDelta: Long,
)

/**
 * A counter capture as it was stored and read back.
 *
 * A domain type, not a Room entity. The delta engine must not import Room, and a repository
 * that returned entities would drag the database into every calculation that touches them.
 */
data class StoredCounterCapture(
    val captureId: String,
    val batterySessionId: String,
    val batterySnapshotId: String?,
    val sourceFormat: SourceFormat,
    val backendKind: BackendIdentity.Kind,
    val version: CheckinVersionBlock,
    val platformChanged: Boolean,
    val checkinVersionVerified: Boolean,
    val captureElapsedRealtimeMillis: Long,
    val captureWallClockMillis: Long,
    val counterGeneration: CounterGeneration,
    val bootIdentity: BootIdentity,
    val payloadByteCount: Int,
    val warningCount: Int,
    val kernelWakelocks: List<KernelWakelockStat>,
    val partialWakelocks: List<PartialWakelockStat>,
)

/** Baseline and latest for one session, as stored. */
data class SessionCounterState(
    val batterySessionId: String,
    val baseline: StoredCounterCapture,
    val latest: StoredCounterCapture,
) {
    /** True on the first capture of a session, when both roles are the same row. */
    val baselineIsLatest: Boolean get() = baseline.captureId == latest.captureId
}

/**
 * Subtracts counter captures, and refuses to when it would be meaningless.
 *
 * Pure: no Room, no Android, no clock, no I/O. Everything it needs arrives as arguments,
 * which is what lets the whole comparison policy be tested exhaustively on the JVM.
 *
 * ## The rule that shapes all of this
 *
 * A missing counter never becomes zero. Not in the baseline, not in the latest, not for
 * convenience. If a wakelock appears only in the later capture, the honest answer is that its
 * delta is unavailable -- assuming it started at zero would attribute all of its accumulated
 * time to this session, which may be entirely wrong. If it disappears from the later capture,
 * assuming zero would manufacture a large negative that looks exactly like a counter reset.
 *
 * One unmatched counter never invalidates the others. The session can still report deltas for
 * everything that did match.
 */
object CounterDeltaEngine {

    /**
     * Whether two captures may be subtracted at all.
     *
     * Ordered so the most fundamental objection is reported first: a reboot makes every other
     * question irrelevant, and telling a user "the checkin version changed" when the real
     * answer is "your device restarted" would be technically true and useless.
     */
    fun comparability(
        baseline: StoredCounterCapture,
        latest: StoredCounterCapture,
    ): CounterDeltaResult.NotComparable? {
        when (baseline.bootIdentity.relationTo(latest.bootIdentity)) {
            BootRelation.DIFFERENT -> return CounterDeltaResult.NotComparable(
                CounterDeltaReason.DIFFERENT_BOOT,
                "The device restarted between these captures, so the counters restarted too.",
            )
            BootRelation.UNKNOWN -> return CounterDeltaResult.NotComparable(
                CounterDeltaReason.UNKNOWN,
                "It could not be established that both captures came from the same start-up.",
            )
            BootRelation.SAME -> Unit
        }

        if (latest.captureElapsedRealtimeMillis < baseline.captureElapsedRealtimeMillis) {
            return CounterDeltaResult.NotComparable(
                CounterDeltaReason.TIME_REVERSED,
                "The later capture has an earlier monotonic time.",
            )
        }

        if (baseline.counterGeneration != latest.counterGeneration) {
            return CounterDeltaResult.NotComparable(
                CounterDeltaReason.DIFFERENT_COUNTER_GENERATION,
                "Android's own counters restarted between these captures.",
            )
        }

        if (baseline.sourceFormat != latest.sourceFormat) {
            return CounterDeltaResult.NotComparable(
                CounterDeltaReason.SOURCE_FORMAT_CHANGED,
                "These captures came from different acquisition formats " +
                    "(${baseline.sourceFormat} and ${latest.sourceFormat}).",
            )
        }

        if (baseline.version.checkinVersion != latest.version.checkinVersion ||
            baseline.version.recordFormatVersion != latest.version.recordFormatVersion
        ) {
            return CounterDeltaResult.NotComparable(
                CounterDeltaReason.CHECKIN_VERSION_CHANGED,
                "The batterystats format version changed between these captures " +
                    "(${baseline.version.checkinVersion} and ${latest.version.checkinVersion}), " +
                    "so the records may not mean the same thing.",
            )
        }

        if (baseline.platformChanged || latest.platformChanged) {
            return CounterDeltaResult.NotComparable(
                CounterDeltaReason.PLATFORM_CHANGED,
                "The accounting window spans an operating system update, so counters from " +
                    "either side of it are not the same measurement.",
            )
        }

        return null
    }

    /** Every kernel wakelock that can be compared, plus a reason for each that cannot. */
    fun kernelWakelockDeltas(
        state: SessionCounterState,
    ): CounterDeltaResult<List<KernelWakelockDelta>> {
        comparability(state.baseline, state.latest)?.let { return it }

        val baselineByKey = state.baseline.kernelWakelocks.associateBy { it.window to it.name }
        val deltas = mutableListOf<KernelWakelockDelta>()

        for (later in state.latest.kernelWakelocks) {
            // Absent from the baseline: unavailable, never treated as having started at zero.
            val earlier = baselineByKey[later.window to later.name] ?: continue
            val duration = later.totalTimeMillis - earlier.totalTimeMillis
            val count = later.count - earlier.count
            // A decrease is reported by omission from the comparable set; the individual
            // reason is available through kernelWakelockDelta for a single counter.
            if (duration < 0L || count < 0L) continue
            deltas.add(
                KernelWakelockDelta(
                    name = later.name,
                    window = later.window,
                    durationDeltaMillis = duration,
                    countDelta = count,
                ),
            )
        }

        return CounterDeltaResult.Success(
            baselineCaptureId = state.baseline.captureId,
            latestCaptureId = state.latest.captureId,
            elapsedMillis = elapsed(state),
            value = deltas,
        )
    }

    /** The same, for application wakelocks, keyed by UID rather than by package. */
    fun partialWakelockDeltas(
        state: SessionCounterState,
    ): CounterDeltaResult<List<PartialWakelockDelta>> {
        comparability(state.baseline, state.latest)?.let { return it }

        val baselineByKey = state.baseline.partialWakelocks
            .associateBy { Triple(it.window, it.uid, it.name) }
        val deltas = mutableListOf<PartialWakelockDelta>()

        for (later in state.latest.partialWakelocks) {
            val earlier = baselineByKey[Triple(later.window, later.uid, later.name)] ?: continue
            val duration = later.totalTimeMillis - earlier.totalTimeMillis
            val count = later.count - earlier.count
            if (duration < 0L || count < 0L) continue
            deltas.add(
                PartialWakelockDelta(
                    uid = later.uid,
                    name = later.name,
                    window = later.window,
                    durationDeltaMillis = duration,
                    countDelta = count,
                ),
            )
        }

        return CounterDeltaResult.Success(
            baselineCaptureId = state.baseline.captureId,
            latestCaptureId = state.latest.captureId,
            elapsedMillis = elapsed(state),
            value = deltas,
        )
    }

    /**
     * One named kernel wakelock, with an explicit reason when it cannot be compared.
     *
     * The list functions above answer "what can I show?"; this answers "what happened to this
     * one?", which is the question that needs the reason rather than silence.
     */
    fun kernelWakelockDelta(
        state: SessionCounterState,
        window: AggregationWindow,
        name: String,
    ): CounterDeltaResult<KernelWakelockDelta> {
        comparability(state.baseline, state.latest)?.let { return it }

        val earlier = state.baseline.kernelWakelocks
            .firstOrNull { it.window == window && it.name == name }
            ?: return CounterDeltaResult.MissingData(
                CounterDeltaReason.COUNTER_MISSING_IN_BASELINE,
                "This wakelock was not present when the session's baseline was recorded, so " +
                    "how much of its total belongs to this session is unknown.",
            )
        val later = state.latest.kernelWakelocks
            .firstOrNull { it.window == window && it.name == name }
            ?: return CounterDeltaResult.MissingData(
                CounterDeltaReason.COUNTER_MISSING_IN_LATEST,
                "This wakelock is no longer being reported, which is not the same as it " +
                    "having stopped.",
            )

        val duration = later.totalTimeMillis - earlier.totalTimeMillis
        val count = later.count - earlier.count
        if (duration < 0L || count < 0L) {
            return CounterDeltaResult.NotComparable(
                CounterDeltaReason.COUNTER_DECREASED,
                "A cumulative counter went down, so something restarted it. The difference " +
                    "would not mean anything.",
            )
        }

        return CounterDeltaResult.Success(
            baselineCaptureId = state.baseline.captureId,
            latestCaptureId = state.latest.captureId,
            elapsedMillis = elapsed(state),
            value = KernelWakelockDelta(name, window, duration, count),
        )
    }

    /** The same, for one application wakelock. */
    fun partialWakelockDelta(
        state: SessionCounterState,
        window: AggregationWindow,
        uid: Int,
        name: String,
    ): CounterDeltaResult<PartialWakelockDelta> {
        comparability(state.baseline, state.latest)?.let { return it }

        val earlier = state.baseline.partialWakelocks
            .firstOrNull { it.window == window && it.uid == uid && it.name == name }
            ?: return CounterDeltaResult.MissingData(
                CounterDeltaReason.COUNTER_MISSING_IN_BASELINE,
                "This wakelock was not present at the session's baseline.",
            )
        val later = state.latest.partialWakelocks
            .firstOrNull { it.window == window && it.uid == uid && it.name == name }
            ?: return CounterDeltaResult.MissingData(
                CounterDeltaReason.COUNTER_MISSING_IN_LATEST,
                "This wakelock is no longer being reported.",
            )

        val duration = later.totalTimeMillis - earlier.totalTimeMillis
        val count = later.count - earlier.count
        if (duration < 0L || count < 0L) {
            return CounterDeltaResult.NotComparable(
                CounterDeltaReason.COUNTER_DECREASED,
                "A cumulative counter went down, so something restarted it.",
            )
        }

        return CounterDeltaResult.Success(
            baselineCaptureId = state.baseline.captureId,
            latestCaptureId = state.latest.captureId,
            elapsedMillis = elapsed(state),
            value = PartialWakelockDelta(uid, name, window, duration, count),
        )
    }

    private fun elapsed(state: SessionCounterState): Long =
        state.latest.captureElapsedRealtimeMillis - state.baseline.captureElapsedRealtimeMillis
}

package com.rmpsdroid.battinsight.session

/**
 * Whether two snapshots may be compared, and if not, why not.
 *
 * Never a boolean. A refusal that cannot explain itself is indistinguishable from a bug,
 * and the user-facing consequence of an unexplained refusal is an empty screen -- which is
 * the failure this whole project is a reaction to.
 */
sealed interface Comparability {

    /** The comparison is valid. */
    data object Comparable : Comparability

    /** The comparison is refused, with a reason that can be shown to a person. */
    data class NotComparable(val reason: Reason, val detail: String) : Comparability

    enum class Reason {
        /** Different boots. `elapsedRealtime` restarted, so ordering is meaningless. */
        DIFFERENT_BOOT,

        /** Platform counters restarted between the two. Raw subtraction would be nonsense. */
        DIFFERENT_COUNTER_GENERATION,

        /** The later snapshot has an earlier monotonic reading. */
        TIME_REVERSED,

        /** The snapshots use different versions of BattInsight's own model. */
        SCHEMA_INCOMPATIBLE,

        /** Counters came from different acquisition formats, or one has none. */
        SOURCE_INCOMPATIBLE,

        /** One or both lack an identity strong enough to compare. */
        MISSING_IDENTITY,

        /** Not established either way. */
        UNKNOWN,
    }

    val isComparable: Boolean get() = this is Comparable
}

/**
 * Decides whether two snapshots may be compared.
 *
 * Two questions, deliberately separate, because they have different requirements and
 * answering the stricter one when the looser was asked would refuse valid work:
 *
 *  - [forDuration] -- may we measure elapsed time between them?
 *  - [forCounters] -- may we subtract platform counters?
 *
 * Duration needs the same boot and correct ordering. Counters need all of that *and* the
 * same counter generation, the same schema, and a compatible source. A boot change fails
 * both; a counter reset fails only the second, because the wall and monotonic clocks are
 * unaffected by `dumpsys batterystats --reset`.
 *
 * Pure. No Android, no time source, no state.
 */
object SnapshotComparability {

    /**
     * Whether elapsed time between two snapshots can be measured.
     *
     * [earlier] and [later] are named by intent, not by assumption: if the arguments are
     * the wrong way round the result is [Comparability.Reason.TIME_REVERSED] rather than a
     * negative duration.
     */
    fun forDuration(earlier: BatterySnapshot, later: BatterySnapshot): Comparability {
        when (earlier.bootIdentity.relationTo(later.bootIdentity)) {
            BootRelation.DIFFERENT -> return Comparability.NotComparable(
                Comparability.Reason.DIFFERENT_BOOT,
                "The device restarted between these two readings, so the monotonic clock " +
                    "restarted with it.",
            )
            BootRelation.UNKNOWN -> return Comparability.NotComparable(
                Comparability.Reason.MISSING_IDENTITY,
                "It could not be established that both readings came from the same start-up" +
                    (if (!earlier.bootIdentity.canProveBootRelation || !later.bootIdentity.canProveBootRelation) {
                        ", because the kernel boot identifier was unavailable."
                    } else "."),
            )
            BootRelation.SAME -> Unit
        }

        if (later.time.elapsedRealtime < earlier.time.elapsedRealtime) {
            return Comparability.NotComparable(
                Comparability.Reason.TIME_REVERSED,
                "The later reading has an earlier monotonic time (" +
                    "${later.time.elapsedRealtime.millis} before ${earlier.time.elapsedRealtime.millis}).",
            )
        }

        return Comparability.Comparable
    }

    /**
     * Whether platform counters may be subtracted.
     *
     * Strictly stronger than [forDuration]. Everything that invalidates a duration also
     * invalidates a counter delta; the reverse is not true.
     */
    fun forCounters(earlier: BatterySnapshot, later: BatterySnapshot): Comparability {
        val duration = forDuration(earlier, later)
        if (duration is Comparability.NotComparable) return duration

        if (earlier.schemaVersion != later.schemaVersion) {
            return Comparability.NotComparable(
                Comparability.Reason.SCHEMA_INCOMPATIBLE,
                "These readings were recorded by different versions of BattInsight's " +
                    "snapshot model (${earlier.schemaVersion} and ${later.schemaVersion}).",
            )
        }

        if (earlier.counterGeneration != later.counterGeneration) {
            return Comparability.NotComparable(
                Comparability.Reason.DIFFERENT_COUNTER_GENERATION,
                "Android's own counters restarted between these readings " +
                    "(${earlier.counterGeneration} and ${later.counterGeneration}), so the " +
                    "difference between them would not mean anything.",
            )
        }

        // Checked after the generation, because a source change is expected to bring a
        // generation change with it and the generation is the more informative answer.
        if (earlier.counterSource != later.counterSource) {
            return Comparability.NotComparable(
                Comparability.Reason.SOURCE_INCOMPATIBLE,
                "These readings came from different sources " +
                    "(${earlier.counterSource} and ${later.counterSource}).",
            )
        }

        if (!earlier.hasCounters || !later.hasCounters) {
            return Comparability.NotComparable(
                Comparability.Reason.SOURCE_INCOMPATIBLE,
                "Neither reading carries platform counters; only battery state was captured.",
            )
        }

        return Comparability.Comparable
    }
}

/**
 * The result of a comparison that future collectors will produce.
 *
 * Three outcomes, because "no value" has two distinct causes that a user needs told apart:
 * the comparison was refused, or it was permitted and the data was not there. The
 * predecessor showed both as a blank cell.
 *
 * Phase 5 defines the shape and computes nothing beyond duration. Wakelock, CPU and network
 * deltas arrive with the decoder in Phase 7.
 */
sealed interface DeltaResult<out T> {

    /** A value, and the monotonic duration it accrued over. */
    data class Success<T>(val value: T, val durationMillis: Long) : DeltaResult<T>

    /** The comparison itself was refused. */
    data class NotComparable(val comparability: Comparability.NotComparable) : DeltaResult<Nothing>

    /** The comparison was valid but one side had no data to compare. */
    data class MissingData(val detail: String) : DeltaResult<Nothing>

    val valueOrNull: T? get() = (this as? Success)?.value

    /** Why there is no value, phrased for a person. Null when there is one. */
    val refusalDetail: String?
        get() = when (this) {
            is Success -> null
            is NotComparable -> comparability.detail
            is MissingData -> detail
        }
}

/**
 * Elapsed time between two snapshots, or an explained refusal.
 *
 * The only delta Phase 5 actually computes. It is here to prove the shape works end to end
 * against something real, rather than shipping an abstraction nothing has ever used.
 */
fun durationBetween(earlier: BatterySnapshot, later: BatterySnapshot): DeltaResult<Long> =
    when (val c = SnapshotComparability.forDuration(earlier, later)) {
        is Comparability.NotComparable -> DeltaResult.NotComparable(c)
        Comparability.Comparable -> {
            val millis = later.time.elapsedRealtime - earlier.time.elapsedRealtime
            DeltaResult.Success(millis, millis)
        }
    }

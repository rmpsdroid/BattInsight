package com.rmpsdroid.battinsight.session

import com.rmpsdroid.battinsight.collection.SourceFormat
import java.util.UUID

/**
 * Which run of the platform's counters a snapshot belongs to.
 *
 * Exists because Android's batterystats counters can reset independently of anything
 * BattInsight decides. A reboot resets them; so does `dumpsys batterystats --reset`, which
 * some manufacturer software and some other applications invoke. Two snapshots from
 * different generations must never have raw counters subtracted, no matter how sensible
 * the arithmetic looks.
 *
 * A generation is **not** a session and the two move independently:
 *
 *  - counters can reset in the middle of a discharge interval, which does not end the
 *    interval -- the device is still unplugged and the user's question is unchanged;
 *  - a session boundary is a power transition, which resets nothing.
 *
 * Conflating them is the reason predecessor tools produced impossible deltas after a
 * reset, and why they lost history at every reboot.
 */
@JvmInline
value class CounterGeneration(val value: Long) : Comparable<CounterGeneration> {

    override fun compareTo(other: CounterGeneration): Int = value.compareTo(other.value)

    /** The next generation. Monotonic so ordering is meaningful in diagnostics. */
    fun next(): CounterGeneration = CounterGeneration(value + 1)

    override fun toString(): String = "gen$value"

    companion object {
        val INITIAL = CounterGeneration(1)
    }
}

/** Why a counter generation changed. Recorded so a refused delta can explain itself. */
enum class CounterGenerationChange {
    /** A different boot was detected. Counters always restart. */
    BOOT_CHANGED,

    /**
     * Acquisition established that platform counters went backwards.
     *
     * **No production detector exists yet** -- that needs the decoder Phase 7 owns. The
     * transition is modelled now so the rest of the engine can be written and tested
     * against it, rather than retrofitted later around data that already exists.
     */
    PLATFORM_COUNTER_RESET,

    /** The acquisition format or its schema changed incompatibly. */
    SOURCE_CHANGED,

    /** Nothing changed it. */
    NONE,
}

/**
 * The version of *BattInsight's own* snapshot model.
 *
 * Deliberately not called `version`. At least four version domains exist in this project
 * and they change independently: this one, Android's batterystats checkin version, its
 * parcel version, and the platform build. Phase 1A measured the parcel version moving from
 * 1310906 on Android 10 to 215 on Android 16 -- downwards -- so anything that compared
 * versions by magnitude across domains would already be wrong.
 *
 * Defined from the first snapshot that exists, before there is anything to migrate, because
 * the predecessor stored snapshots as one opaque blob with no version and an update
 * destroyed every user's history.
 */
@JvmInline
value class SnapshotSchemaVersion(val value: Int) {
    override fun toString(): String = "schema$value"

    companion object {
        /** The first, and so far only, shape. */
        val CURRENT = SnapshotSchemaVersion(1)
    }
}

/**
 * Where a snapshot's counter payload came from, if it has one.
 *
 * [NONE] is the honest answer for Phase 5: battery state is captured, and no batterystats
 * payload is. Claiming a source that was never read would make a snapshot look
 * counter-comparable when it holds no counters at all.
 */
enum class CounterSource {
    NONE,
    PROTO,
    CHECKIN,
    ;

    companion object {
        fun forFormat(format: SourceFormat?): CounterSource = when (format) {
            SourceFormat.PROTO -> PROTO
            SourceFormat.CHECKIN -> CHECKIN
            else -> NONE
        }
    }
}

/**
 * An immutable observation of battery and session state at one moment.
 *
 * Every field earns its place by making one specific failure diagnosable rather than
 * mysterious, and each is recorded at capture because none can be reconstructed afterwards.
 *
 * This is **not** a capability report. A capability report describes what the environment
 * can currently do and is transient; a snapshot describes what was true and is kept. Phase
 * 3 named the capability type a *report* specifically to keep this word free.
 */
data class BatterySnapshot(
    /** Independent identity, so a snapshot is referable even before it is stored. */
    val id: UUID,
    /** The session this snapshot belongs to. */
    val sessionId: UUID,
    val bootIdentity: BootIdentity,
    val time: CaptureTime,
    val trigger: SessionTrigger,
    val battery: BatteryObservation,
    val counterGeneration: CounterGeneration,
    val schemaVersion: SnapshotSchemaVersion = SnapshotSchemaVersion.CURRENT,
    /**
     * Which acquisition format produced the counters, if any.
     *
     * [CounterSource.NONE] in Phase 5. Recorded because the routine format is not yet
     * fixed, and a later change would otherwise silently invalidate stored deltas.
     */
    val counterSource: CounterSource = CounterSource.NONE,
    /**
     * The Android release at capture.
     *
     * Catches an OS upgrade between two snapshots -- a comparability hazard that neither a
     * boot identity nor a counter generation detects, because an upgrade can preserve both
     * while changing what the counters mean.
     */
    val platformVersionAtCapture: String? = null,
    val appVersionAtCapture: String? = null,
) {
    /** Short form for diagnostics. Full identifiers are not logged by default. */
    val abbreviatedId: String get() = id.toString().take(8)

    /** Whether this snapshot carries counters at all. */
    val hasCounters: Boolean get() = counterSource != CounterSource.NONE
}

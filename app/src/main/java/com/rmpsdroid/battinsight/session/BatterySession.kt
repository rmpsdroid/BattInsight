package com.rmpsdroid.battinsight.session

import java.util.UUID

/** Why an observation was taken, or why a session boundary happened. */
enum class SessionTrigger {
    /** The application process started and read current state. */
    APP_START,

    /** `ACTION_POWER_CONNECTED` was observed. */
    POWER_CONNECTED,

    /** `ACTION_POWER_DISCONNECTED` was observed. */
    POWER_DISCONNECTED,

    /** `ACTION_BATTERY_CHANGED` was observed. */
    BATTERY_CHANGED,

    /** A scheduled sample. */
    PERIODIC,

    /** The user asked for one. */
    MANUAL,

    /**
     * State was reconstructed rather than observed.
     *
     * The distinction matters. A transition that happened while the process did not exist
     * was never seen by anything, and labelling the resulting boundary
     * [POWER_DISCONNECTED] would be claiming a broadcast was received. Recovery is an
     * inference, and it says so.
     */
    RECOVERY,

    /** A different boot was detected. */
    BOOT_CHANGED,

    /** Platform counters were established to have reset. */
    COUNTER_RESET,

    UNKNOWN,
    ;

    /** Whether this trigger came from something actually observed, rather than inferred. */
    val isObserved: Boolean
        get() = this == POWER_CONNECTED || this == POWER_DISCONNECTED ||
            this == BATTERY_CHANGED || this == APP_START || this == PERIODIC || this == MANUAL
}

/**
 * What kind of interval a session is.
 *
 * Follows [PowerAttachment], not [BatteryStatus] -- see the note on
 * [BatteryObservation.powerAttachment]. "Since unplugged" is the question users actually
 * ask, and it is answered by whether anything is plugged in.
 */
enum class SessionType {
    /** Running on battery. */
    DISCHARGE,

    /** On external power, whether or not current is currently flowing into the battery. */
    CHARGE,

    /** Power attachment could not be established. */
    UNKNOWN,
    ;

    companion object {
        fun forAttachment(attachment: PowerAttachment): SessionType = when (attachment) {
            PowerAttachment.ATTACHED -> CHARGE
            PowerAttachment.DETACHED -> DISCHARGE
            PowerAttachment.UNKNOWN -> UNKNOWN
        }
    }
}

/**
 * Why a session ended.
 *
 * Four distinct statements, deliberately not collapsed. Each says something different about
 * what BattInsight actually knows, and a user shown "the device restarted" deserves that to
 * be true rather than a stand-in for "something happened and we are not sure what".
 */
enum class SessionBoundaryReason {
    /** A power transition was observed directly. */
    POWER_TRANSITION,

    /**
     * A different boot was **proven**, so the monotonic interval cannot continue.
     *
     * Requires a kernel boot identifier. Nothing weaker may produce this.
     */
    BOOT_BOUNDARY,

    /**
     * A real change was reconstructed at cold start rather than witnessed.
     *
     * The boot is *known* to be the same, and the device is *known* to have changed
     * direction while the process did not exist. What is missing is only the broadcast, so
     * the transition is real and its moment is approximate.
     */
    RECOVERY,

    /**
     * Continuity could not be established either way.
     *
     * Distinct from [RECOVERY]: nothing here is known to have changed, and nothing is known
     * to be wrong. Without a kernel boot identifier the saved interval cannot be tied to the
     * current one, so it is not carried forward -- but no reboot is claimed, because none
     * was proven.
     */
    UNPROVEN_CONTINUITY,

    /**
     * Saved state contradicts the present and provably cannot continue.
     *
     * The case that reaches this is monotonic time having gone backwards. That disproves the
     * saved timeline, but it does not prove a reboot on its own: stale or corrupt saved state
     * produces the same reading, and only a kernel identifier could tell the two apart.
     */
    INCONSISTENT_STATE,

    /** Still running. */
    NONE,
}

/**
 * A logical battery interval.
 *
 * Has identity, a beginning, and possibly an end. An active session deliberately has no
 * end snapshot: requiring one would mean the current interval could not be described until
 * it was over, which is precisely the interval a user most wants to see.
 *
 * [id] is stable for the life of the interval. It survives duplicate observations, process
 * death and repeated identical broadcasts, and changes only on a real transition. A session
 * identifier that churned would make every stored delta ambiguous.
 */
data class BatterySession(
    val id: UUID,
    val type: SessionType,
    /** The observation that opened this interval. */
    val start: BatterySnapshot,
    /** The most recent observation attributed to it. Equals [start] until one arrives. */
    val latest: BatterySnapshot,
    /** Set only when the interval has ended. */
    val end: BatterySnapshot? = null,
    val endReason: SessionBoundaryReason = SessionBoundaryReason.NONE,
    /**
     * Which counter generation this session began in.
     *
     * Not the same thing as the session itself. Counters can reset mid-session, and a
     * session can end without any counter resetting.
     */
    val counterGeneration: CounterGeneration,
) {
    val isActive: Boolean get() = end == null

    /**
     * How long the interval has run, in milliseconds, from the monotonic clock only.
     *
     * Never negative: [SessionEngine] refuses observations that would move
     * `elapsedRealtime` backwards within a boot, so the subtraction cannot invert. The
     * guard here is belt and braces, and a negative result would indicate a bug rather
     * than a device behaviour.
     */
    val elapsedMillis: Long
        get() {
            val to = (end ?: latest).time.elapsedRealtime
            val raw = to - start.time.elapsedRealtime
            return if (raw < 0) 0 else raw
        }

    /** Short form for diagnostics. Never logs a full identifier by default. */
    val abbreviatedId: String get() = id.toString().take(8)
}

package com.rmpsdroid.battinsight.history

import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType

/**
 * Every rule about how history *reads*, kept out of the Composables.
 *
 * Pure functions on domain values. A branch buried in a `@Composable` is a branch that can
 * only be checked by looking at a screen; the same branch here is checked by a JVM test, which
 * is why the wording rules that matter most -- what an unavailable delta says, what a
 * recovered boundary is called -- live in this file rather than in the UI.
 *
 * No string here contains an enum name. A user reading "COUNTER_DECREASED" has been told
 * nothing, and a user reading "0" when the answer is "unknown" has been told something false.
 */
object HistoryPresentation {

    // ------------------------------------------------------------------- duration

    /**
     * Formats an elapsed duration.
     *
     * Built from arithmetic on a millisecond count, never from a date API. `Date` and
     * `Calendar` formatters answer "what time is it", which is a different question and one
     * that involves time zones and daylight saving -- neither of which has any bearing on how
     * long something took.
     *
     * Sub-second values keep their milliseconds rather than rounding to "0 s". A wakelock held
     * for 40 ms did happen, and displaying it as zero is the same lie as displaying missing
     * data as zero.
     */
    fun duration(millis: Long?): String {
        if (millis == null) return "unknown"
        if (millis < 0L) return "unknown"
        if (millis < 1_000L) return "$millis ms"

        val totalSeconds = millis / 1_000L
        val days = totalSeconds / 86_400L
        val hours = (totalSeconds % 86_400L) / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L

        return when {
            days > 0L -> "$days d ${pad(hours)} h"
            hours > 0L -> "$hours h ${pad(minutes)} min"
            minutes > 0L -> "$minutes min ${seconds} s"
            else -> "$seconds s"
        }
    }

    private fun pad(value: Long) = if (value < 10) "0$value" else "$value"

    // -------------------------------------------------------------------- battery

    /**
     * A battery level as a percentage, or an honest absence.
     *
     * Never assumes a scale of 100. A device reporting 50 out of 200 is at 25%, and a reader
     * that assumed otherwise would double every reading on it.
     */
    fun batteryPercent(level: BatteryLevel?): String =
        level?.percent?.let { "$it%" } ?: "unavailable"

    /** A range, when both ends are known; otherwise whichever end is. */
    fun batteryRange(start: BatteryLevel?, end: BatteryLevel?): String = when {
        start?.percent != null && end?.percent != null && start.percent != end.percent ->
            "${start.percent}% to ${end.percent}%"
        start?.percent != null -> "${start.percent}%"
        end?.percent != null -> "${end.percent}%"
        else -> "level unavailable"
    }

    // --------------------------------------------------------------- session title

    fun sessionTitle(type: SessionType, isActive: Boolean): String {
        val kind = when (type) {
            SessionType.CHARGE -> "Charging"
            SessionType.DISCHARGE -> "On battery"
            SessionType.UNKNOWN -> "Power state unknown"
        }
        return if (isActive) "$kind — now" else kind
    }

    // ---------------------------------------------------- boundary and provenance

    /**
     * How a session began, without claiming more than was observed.
     *
     * Phase 5 kept observed and reconstructed boundaries apart, and the wording has to keep
     * them apart too. "Unplugged from power" asserts a broadcast was received; if the change
     * happened while the process did not exist, nothing received anything, and saying
     * otherwise would make an inference look like a measurement.
     */
    fun startDescription(trigger: SessionTrigger): String = when (trigger) {
        SessionTrigger.POWER_CONNECTED -> "Plugged in"
        SessionTrigger.POWER_DISCONNECTED -> "Unplugged"
        SessionTrigger.BATTERY_CHANGED -> "Battery reading changed"
        SessionTrigger.APP_START -> "BattInsight opened"
        SessionTrigger.PERIODIC -> "Routine reading"
        SessionTrigger.MANUAL -> "You asked for a reading"
        SessionTrigger.RECOVERY -> "Recovered after BattInsight was not running"
        SessionTrigger.BOOT_CHANGED -> "New device start-up detected"
        SessionTrigger.COUNTER_RESET -> "Android's counters restarted"
        SessionTrigger.UNKNOWN -> "How this began was not recorded"
    }

    /**
     * Why a session ended.
     *
     * The four non-trivial reasons stay distinct rather than collapsing into "Restarted".
     * They mean genuinely different things -- one is a proven reboot, one is a real change we
     * did not witness, one is an absence of proof either way, and one is data that disagrees
     * with itself -- and a user troubleshooting a battery problem is exactly the person who
     * needs the difference.
     */
    fun endDescription(reason: SessionBoundaryReason, isActive: Boolean): String = when {
        isActive -> "Still running"
        else -> when (reason) {
            SessionBoundaryReason.POWER_TRANSITION -> "Power was connected or disconnected"
            SessionBoundaryReason.BOOT_BOUNDARY -> "The device restarted"
            SessionBoundaryReason.RECOVERY ->
                "The power state changed while BattInsight was not running"
            SessionBoundaryReason.UNPROVEN_CONTINUITY ->
                "BattInsight could not prove this was still the same period, so it started a new one"
            SessionBoundaryReason.INCONSISTENT_STATE ->
                "The saved reading disagreed with itself, so it was not carried forward"
            SessionBoundaryReason.NONE -> "Ended without a recorded reason"
        }
    }

    /** Whether a boundary was witnessed, for a badge that does not rely on colour alone. */
    fun observationLabel(observed: Boolean): String =
        if (observed) "Observed" else "Reconstructed"

    // ------------------------------------------------------- counter availability

    /** One line describing what counter data exists, for a list row. */
    fun counterSummary(availability: CounterAvailability): String = when (availability) {
        CounterAvailability.NoCapture ->
            "No battery statistics captured"
        CounterAvailability.BaselineOnly ->
            "Starting capture saved — capture again to see what changed"
        is CounterAvailability.DeltaAvailable ->
            if (availability.allZero) {
                "No increase recorded between captures"
            } else {
                "${availability.kernelWakelockCount} kernel and " +
                    "${availability.partialWakelockCount} app wakelocks changed"
            }
        is CounterAvailability.DeltaUnavailable ->
            "Changes unavailable — " + shortReason(availability.reason)
    }

    /**
     * Why a comparison is unavailable, in a sentence a person can act on.
     *
     * Every [CounterDeltaReason] is mapped. `when` over an enum without an `else` is checked
     * by the compiler, so a reason added later cannot quietly fall through to a generic
     * message -- which is how "something went wrong" ends up in front of users.
     */
    fun unavailableReason(reason: CounterDeltaReason): String = when (reason) {
        CounterDeltaReason.DIFFERENT_BOOT ->
            "These readings were taken across different device start-ups, so Android's " +
                "counters restarted in between."
        CounterDeltaReason.DIFFERENT_COUNTER_GENERATION ->
            "Android's own counters restarted between these readings, so the difference " +
                "between them would not mean anything."
        CounterDeltaReason.DIFFERENT_ACCOUNTING_WINDOW ->
            "These readings count from different starting points, so they are not measuring " +
                "the same thing."
        CounterDeltaReason.SOURCE_FORMAT_CHANGED ->
            "These readings came from different sources and cannot be compared directly."
        CounterDeltaReason.CHECKIN_VERSION_CHANGED ->
            "Android changed the format of its battery statistics between these readings, so " +
                "the numbers may not mean the same thing."
        CounterDeltaReason.PLATFORM_CHANGED ->
            "This period spans a system update. Counters from before and after an update are " +
                "not the same measurement."
        CounterDeltaReason.TIME_REVERSED ->
            "The later reading appears to have been taken before the earlier one."
        CounterDeltaReason.BASELINE_MISSING ->
            "No trusted starting capture is available for this period."
        CounterDeltaReason.LATEST_MISSING ->
            "No recent capture is available for this period."
        CounterDeltaReason.COUNTER_MISSING_IN_BASELINE ->
            "This counter was not present in the starting capture, so how much of its total " +
                "belongs to this period is unknown."
        CounterDeltaReason.COUNTER_MISSING_IN_LATEST ->
            "This counter is no longer being reported, which is not the same as it having " +
                "stopped."
        CounterDeltaReason.COUNTER_DECREASED ->
            "Android's battery accounting restarted during this period, so a reliable " +
                "difference cannot be calculated for any counter in this reading."
        CounterDeltaReason.MALFORMED_STORED_STATE ->
            "The saved readings for this period could not be read back correctly."
        CounterDeltaReason.UNKNOWN ->
            "These readings could not be compared, and the cause was not established."
    }

    /** A clause short enough for a list row. Same coverage, no enum names. */
    fun shortReason(reason: CounterDeltaReason): String = when (reason) {
        CounterDeltaReason.DIFFERENT_BOOT -> "the device restarted"
        CounterDeltaReason.DIFFERENT_COUNTER_GENERATION -> "Android's counters restarted"
        CounterDeltaReason.DIFFERENT_ACCOUNTING_WINDOW -> "different counting periods"
        CounterDeltaReason.SOURCE_FORMAT_CHANGED -> "the data source changed"
        CounterDeltaReason.CHECKIN_VERSION_CHANGED -> "the statistics format changed"
        CounterDeltaReason.PLATFORM_CHANGED -> "a system update happened"
        CounterDeltaReason.TIME_REVERSED -> "the readings are out of order"
        CounterDeltaReason.BASELINE_MISSING -> "no starting capture"
        CounterDeltaReason.LATEST_MISSING -> "no recent capture"
        CounterDeltaReason.COUNTER_MISSING_IN_BASELINE -> "not present at the start"
        CounterDeltaReason.COUNTER_MISSING_IN_LATEST -> "no longer reported"
        CounterDeltaReason.COUNTER_DECREASED -> "accounting continuity changed"
        CounterDeltaReason.MALFORMED_STORED_STATE -> "the saved data is unreadable"
        CounterDeltaReason.UNKNOWN -> "the cause was not established"
    }

    // -------------------------------------------------------------------- wakelocks

    /**
     * How an application wakelock is attributed.
     *
     * The UID is the identity and is always shown. A package name is *current* enrichment: it
     * says what runs under that UID now, which is not proof of what ran under it when the
     * capture was taken. Historical package attribution would need the mapping persisted, and
     * Phase 7B decided not to store it.
     *
     * So the name never replaces the UID, and never appears without it.
     */
    fun uidLabel(uid: Int, resolvedPackage: String?): String =
        if (resolvedPackage != null) "$resolvedPackage (UID $uid)" else "UID $uid"

    /** A delta as "+4 min 18 s over +12", or an explicit zero. */
    fun deltaLabel(durationDeltaMillis: Long, countDelta: Long): String = when {
        durationDeltaMillis == 0L && countDelta == 0L -> "no change"
        else -> "+${duration(durationDeltaMillis)} over +$countDelta"
    }

    /**
     * Orders deltas for a top-N list.
     *
     * Longest first, then most acquisitions, then by name. The name tiebreak is what makes the
     * list stable: without it two counters with identical figures could swap places between
     * recompositions, which looks like data changing when nothing did.
     */
    fun <T> topBy(
        items: List<T>,
        limit: Int,
        duration: (T) -> Long,
        count: (T) -> Long,
        name: (T) -> String,
    ): List<T> = items
        .sortedWith(
            compareByDescending<T> { duration(it) }
                .thenByDescending { count(it) }
                .thenBy { name(it) },
        )
        .take(limit)
}

package com.rmpsdroid.battinsight.session

/**
 * What Android says the battery is doing.
 *
 * Kept separate from [PlugSource] because they answer different questions and Android
 * routinely reports combinations that a single boolean cannot express: plugged in and
 * `FULL`, plugged in and `NOT_CHARGING` because a charge limit is in force, or plugged in
 * and `DISCHARGING` because the load exceeds what the supply provides.
 */
enum class BatteryStatus {
    CHARGING,
    DISCHARGING,
    FULL,
    NOT_CHARGING,
    UNKNOWN,
}

/**
 * Where external power is coming from, if anywhere.
 *
 * [NONE] is a measured answer -- nothing is attached. [UNKNOWN] is the absence of an
 * answer. Collapsing them would make "we did not look" indistinguishable from "we looked
 * and there is nothing", which is the same mistake the capability layer exists to avoid.
 */
enum class PlugSource {
    AC,
    USB,
    WIRELESS,
    DOCK,
    OTHER,
    NONE,
    UNKNOWN,
    ;

    /** Whether this names an actual supply. [NONE] and [UNKNOWN] do not. */
    val isAttached: Boolean
        get() = this == AC || this == USB || this == WIRELESS || this == DOCK || this == OTHER
}

/**
 * Whether the device is on external power.
 *
 * This, not [BatteryStatus], is what decides which kind of session is running. A device
 * plugged in at a charge limit reports `NOT_CHARGING`, and a device at 100% reports `FULL`;
 * in both the battery is not draining and the user has not unplugged anything, so neither
 * begins a discharge interval. "Since unplugged" means since unplugged.
 */
enum class PowerAttachment {
    ATTACHED,
    DETACHED,
    UNKNOWN,
}

/**
 * One platform-neutral reading of battery state.
 *
 * An *observation*: a thing that was true at one instant. It is not a session, and it is
 * not a snapshot. Deliberately free of Android types so the entire state machine runs on
 * the JVM.
 *
 * Fields beyond the ones the engine reads -- temperature, voltage, charge counter -- are
 * carried because they are free at the point of capture and impossible to recover
 * afterwards. None of them influences a session boundary.
 */
data class BatteryObservation(
    val time: CaptureTime,
    val bootIdentity: BootIdentity,
    val status: BatteryStatus,
    val plug: PlugSource,
    /** Raw level in [scale] units, or null when the platform did not report one. */
    val level: Int? = null,
    val scale: Int? = null,
    val present: Boolean? = null,
    /** Tenths of a degree Celsius, as Android reports it. Diagnostic only. */
    val temperatureDeciCelsius: Int? = null,
    val voltageMilliVolts: Int? = null,
    /** Microampere-hours, when `BatteryManager` exposes it. Diagnostic only in this phase. */
    val chargeCounterMicroAmpHours: Long? = null,
    val health: BatteryHealth = BatteryHealth.UNKNOWN,
    /** What caused this reading to be taken. */
    val trigger: SessionTrigger = SessionTrigger.UNKNOWN,
) {
    /**
     * Level as a percentage, when both parts are present and the scale is usable.
     *
     * Null rather than a guess. A missing level is not zero percent, and the predecessor's
     * habit of substituting zero is how a full battery could be displayed as empty.
     */
    val levelPercent: Int?
        get() {
            val l = level ?: return null
            val s = scale ?: return null
            if (s <= 0) return null
            return (l * 100) / s
        }

    /**
     * Whether external power is attached.
     *
     * [plug] is authoritative when it says anything at all, because Android sets it from
     * the actual supply. [status] only fills the gap when the plug is [PlugSource.UNKNOWN],
     * and even then only for the two statuses that imply an answer: `DISCHARGING` means
     * nothing is attached, `CHARGING` means something is.
     *
     * `FULL` and `NOT_CHARGING` deliberately do **not** imply attachment on their own. A
     * device can sit at `NOT_CHARGING` unplugged and idle, and `FULL` says nothing about
     * where the power came from.
     */
    val powerAttachment: PowerAttachment
        get() = when {
            plug.isAttached -> PowerAttachment.ATTACHED
            plug == PlugSource.NONE -> PowerAttachment.DETACHED
            status == BatteryStatus.DISCHARGING -> PowerAttachment.DETACHED
            status == BatteryStatus.CHARGING -> PowerAttachment.ATTACHED
            else -> PowerAttachment.UNKNOWN
        }

    /**
     * Whether the platform contradicted itself.
     *
     * Recorded rather than resolved silently: a device reporting `CHARGING` with nothing
     * plugged in, or `DISCHARGING` while plugged, is telling us something is odd, and a
     * diagnostics tool that smooths that over is throwing away the interesting part.
     * The plug still wins for [powerAttachment]; this just makes the disagreement visible.
     */
    val statusContradictsPlug: Boolean
        get() = (plug.isAttached && status == BatteryStatus.DISCHARGING) ||
            (plug == PlugSource.NONE && status == BatteryStatus.CHARGING)
}

/** Battery health, as Android reports it. Diagnostic only; never affects a session. */
enum class BatteryHealth {
    GOOD,
    OVERHEAT,
    DEAD,
    OVER_VOLTAGE,
    COLD,
    UNSPECIFIED_FAILURE,
    UNKNOWN,
}

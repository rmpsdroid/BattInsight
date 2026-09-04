package com.rmpsdroid.battinsight.session

import java.util.UUID

/**
 * Deterministic building blocks for session tests.
 *
 * Identifiers and clocks are supplied rather than generated, so every scenario is
 * reproducible and a failure names one cause instead of a race.
 */

/** UUIDs in a fixed sequence, so assertions can name the identity they expect. */
class SequentialIds : () -> UUID {
    private var next = 0L
    val issued = mutableListOf<UUID>()

    override fun invoke(): UUID {
        next += 1
        val id = UUID(0L, next)
        issued += id
        return id
    }
}

/** A boot identity strong enough to prove sameness. */
fun kernelBoot(name: String = "boot-a") = BootIdentity.Kernel(name)

/**
 * Builds an observation. Defaults describe an unplugged, discharging device.
 *
 * Wall clock defaults to a fixed epoch plus the monotonic reading, which is the ordinary
 * case; the wall-clock scenarios override it precisely because that relationship is what
 * they are testing.
 */
fun observation(
    elapsedMillis: Long,
    status: BatteryStatus = BatteryStatus.DISCHARGING,
    plug: PlugSource = PlugSource.NONE,
    boot: BootIdentity = kernelBoot(),
    wallClockMillis: Long = EPOCH + elapsedMillis,
    utcOffsetMinutes: Int = 0,
    level: Int? = 50,
    scale: Int? = 100,
    trigger: SessionTrigger = SessionTrigger.BATTERY_CHANGED,
) = BatteryObservation(
    time = CaptureTime(ElapsedRealtime(elapsedMillis), wallClockMillis, utcOffsetMinutes),
    bootIdentity = boot,
    status = status,
    plug = plug,
    level = level,
    scale = scale,
    trigger = trigger,
)

/** An unplugged, discharging device. */
fun discharging(
    elapsedMillis: Long,
    boot: BootIdentity = kernelBoot(),
    level: Int? = 50,
    trigger: SessionTrigger = SessionTrigger.BATTERY_CHANGED,
    wallClockMillis: Long = EPOCH + elapsedMillis,
    utcOffsetMinutes: Int = 0,
) = observation(
    elapsedMillis, BatteryStatus.DISCHARGING, PlugSource.NONE, boot,
    wallClockMillis, utcOffsetMinutes, level, 100, trigger,
)

/** A device on mains power and charging. */
fun charging(
    elapsedMillis: Long,
    boot: BootIdentity = kernelBoot(),
    level: Int? = 50,
    trigger: SessionTrigger = SessionTrigger.BATTERY_CHANGED,
    plug: PlugSource = PlugSource.AC,
) = observation(elapsedMillis, BatteryStatus.CHARGING, plug, boot, level = level, trigger = trigger)

/** Plugged in and full. Still on external power; still not a discharge interval. */
fun fullPlugged(elapsedMillis: Long, boot: BootIdentity = kernelBoot()) =
    observation(elapsedMillis, BatteryStatus.FULL, PlugSource.AC, boot, level = 100)

/** Plugged in and held at a charge limit. Also not a discharge interval. */
fun notChargingPlugged(elapsedMillis: Long, boot: BootIdentity = kernelBoot()) =
    observation(elapsedMillis, BatteryStatus.NOT_CHARGING, PlugSource.AC, boot, level = 80)

/** The platform said nothing useful about either field. */
fun unknownState(elapsedMillis: Long, boot: BootIdentity = kernelBoot()) =
    observation(elapsedMillis, BatteryStatus.UNKNOWN, PlugSource.UNKNOWN, boot, level = null)

/** A snapshot built directly, for comparability tests that need no engine. */
fun snapshot(
    elapsedMillis: Long,
    boot: BootIdentity = kernelBoot(),
    generation: CounterGeneration = CounterGeneration.INITIAL,
    schema: SnapshotSchemaVersion = SnapshotSchemaVersion.CURRENT,
    source: CounterSource = CounterSource.NONE,
    wallClockMillis: Long = EPOCH + elapsedMillis,
    sessionId: UUID = UUID(0L, 99L),
) = BatterySnapshot(
    id = UUID.randomUUID(),
    sessionId = sessionId,
    bootIdentity = boot,
    time = CaptureTime(ElapsedRealtime(elapsedMillis), wallClockMillis, 0),
    trigger = SessionTrigger.BATTERY_CHANGED,
    battery = observation(elapsedMillis, boot = boot),
    counterGeneration = generation,
    schemaVersion = schema,
    counterSource = source,
)

/** An arbitrary fixed wall-clock origin. Nothing depends on its value. */
const val EPOCH = 1_700_000_000_000L

/** One minute, in milliseconds. Named so durations in tests read as intent. */
const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE

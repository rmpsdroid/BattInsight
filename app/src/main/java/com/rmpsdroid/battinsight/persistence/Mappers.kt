package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.session.BatteryHealth
import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.BatterySession
import com.rmpsdroid.battinsight.session.BatterySnapshot
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CaptureTime
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.CounterSource
import com.rmpsdroid.battinsight.session.ElapsedRealtime
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType
import com.rmpsdroid.battinsight.session.SnapshotSchemaVersion
import java.util.UUID

/**
 * Thrown when stored data cannot be turned back into a domain object.
 *
 * Deliberately an exception rather than a null or a default. A row whose enum name no longer
 * resolves, or whose boot identity is internally inconsistent, is data we do not understand;
 * substituting a plausible value would silently reinterpret a user's history, which is worse
 * than refusing to read it. The store catches this and reports
 * [com.rmpsdroid.battinsight.session.PersistenceOutcome.MAPPING_FAILURE].
 */
class SnapshotMappingException(message: String) : IllegalStateException(message)

/**
 * Explicit conversion between the pure domain and the stored shape.
 *
 * Two directions, written out by hand. Reflection or a generic serializer would be shorter
 * and would remove the one place where an unreadable row can be *noticed*.
 */
object Mappers {

    // ------------------------------------------------------------------ domain -> entity

    fun toEntity(snapshot: BatterySnapshot): SnapshotEntity = SnapshotEntity(
        snapshotId = snapshot.id.toString(),
        sessionId = snapshot.sessionId.toString(),
        bootKind = bootKindOf(snapshot.bootIdentity),
        bootKernelId = (snapshot.bootIdentity as? BootIdentity.Kernel)?.id,
        bootDerivedMillis =
            (snapshot.bootIdentity as? BootIdentity.Derived)?.approximateBootWallClockMillis,
        elapsedRealtimeMillis = snapshot.time.elapsedRealtime.millis,
        wallClockMillis = snapshot.time.wallClockMillis,
        utcOffsetMinutes = snapshot.time.utcOffsetMinutes,
        trigger = snapshot.trigger.name,
        observationTrigger = snapshot.battery.trigger.name,
        batteryStatus = snapshot.battery.status.name,
        plugSource = snapshot.battery.plug.name,
        batteryHealth = snapshot.battery.health.name,
        level = snapshot.battery.level,
        scale = snapshot.battery.scale,
        present = snapshot.battery.present,
        temperatureDeciCelsius = snapshot.battery.temperatureDeciCelsius,
        voltageMilliVolts = snapshot.battery.voltageMilliVolts,
        chargeCounterMicroAmpHours = snapshot.battery.chargeCounterMicroAmpHours,
        counterGeneration = snapshot.counterGeneration.value,
        snapshotSchemaVersion = snapshot.schemaVersion.value,
        counterSource = snapshot.counterSource.name,
        platformVersionAtCapture = snapshot.platformVersionAtCapture,
        appVersionAtCapture = snapshot.appVersionAtCapture,
    )

    fun toEntity(session: BatterySession): SessionEntity = SessionEntity(
        sessionId = session.id.toString(),
        sessionType = session.type.name,
        startSnapshotId = session.start.id.toString(),
        latestSnapshotId = session.latest.id.toString(),
        endSnapshotId = session.end?.id?.toString(),
        endReason = session.endReason.name,
        counterGeneration = session.counterGeneration.value,
    )

    // ------------------------------------------------------------------ entity -> domain

    fun toDomain(entity: SnapshotEntity): BatterySnapshot {
        val time = CaptureTime(
            elapsedRealtime = ElapsedRealtime(entity.elapsedRealtimeMillis),
            wallClockMillis = entity.wallClockMillis,
            utcOffsetMinutes = entity.utcOffsetMinutes,
        )
        val boot = bootIdentityOf(entity)
        val observation = BatteryObservation(
            time = time,
            bootIdentity = boot,
            status = enumOf<BatteryStatus>(entity.batteryStatus, "battery status"),
            plug = enumOf<PlugSource>(entity.plugSource, "plug source"),
            level = entity.level,
            scale = entity.scale,
            present = entity.present,
            temperatureDeciCelsius = entity.temperatureDeciCelsius,
            voltageMilliVolts = entity.voltageMilliVolts,
            chargeCounterMicroAmpHours = entity.chargeCounterMicroAmpHours,
            health = enumOf<BatteryHealth>(entity.batteryHealth, "battery health"),
            trigger = enumOf<SessionTrigger>(entity.observationTrigger, "observation trigger"),
        )
        return BatterySnapshot(
            id = uuidOf(entity.snapshotId, "snapshot id"),
            sessionId = uuidOf(entity.sessionId, "session id"),
            bootIdentity = boot,
            time = time,
            trigger = enumOf<SessionTrigger>(entity.trigger, "snapshot trigger"),
            battery = observation,
            counterGeneration = CounterGeneration(entity.counterGeneration),
            schemaVersion = SnapshotSchemaVersion(entity.snapshotSchemaVersion),
            counterSource = enumOf<CounterSource>(entity.counterSource, "counter source"),
            platformVersionAtCapture = entity.platformVersionAtCapture,
            appVersionAtCapture = entity.appVersionAtCapture,
        )
    }

    /**
     * Rebuilds a session from its row plus the snapshots it references.
     *
     * The snapshots are passed in rather than looked up here, because a mapper that queried
     * would be a repository. A missing reference is [SnapshotMappingException]: a session
     * naming a snapshot that does not exist is corrupt state, not a session with a gap.
     */
    fun toDomain(
        entity: SessionEntity,
        snapshotsById: Map<String, BatterySnapshot>,
    ): BatterySession {
        fun require(id: String, role: String): BatterySnapshot = snapshotsById[id]
            ?: throw SnapshotMappingException(
                "session ${entity.sessionId} references a $role snapshot that is not stored",
            )

        return BatterySession(
            id = uuidOf(entity.sessionId, "session id"),
            type = enumOf<SessionType>(entity.sessionType, "session type"),
            start = require(entity.startSnapshotId, "start"),
            latest = require(entity.latestSnapshotId, "latest"),
            end = entity.endSnapshotId?.let { require(it, "end") },
            endReason = enumOf<SessionBoundaryReason>(entity.endReason, "end reason"),
            counterGeneration = CounterGeneration(entity.counterGeneration),
        )
    }

    // ------------------------------------------------------------------------- internals

    private fun bootKindOf(identity: BootIdentity): String = when (identity) {
        is BootIdentity.Kernel -> BOOT_KERNEL
        is BootIdentity.Derived -> BOOT_DERIVED
        BootIdentity.Unknown -> BOOT_UNKNOWN
    }

    /**
     * Reconstructs a boot identity **at its original strength**.
     *
     * The one rule that matters: a stored `DERIVED` estimate must never come back as a
     * `Kernel`. Evidence strength is the whole basis of every monotonic comparison, and
     * reloading a weak identity as a strong one would let the comparability layer prove
     * things the original data never supported.
     *
     * An inconsistent row -- a kind with the wrong value column populated -- is refused
     * rather than coerced.
     */
    private fun bootIdentityOf(entity: SnapshotEntity): BootIdentity = when (entity.bootKind) {
        BOOT_KERNEL -> BootIdentity.Kernel(
            entity.bootKernelId ?: throw SnapshotMappingException(
                "snapshot ${entity.snapshotId} claims a kernel boot identity with no value",
            ),
        )
        BOOT_DERIVED -> BootIdentity.Derived(
            entity.bootDerivedMillis ?: throw SnapshotMappingException(
                "snapshot ${entity.snapshotId} claims a derived boot identity with no value",
            ),
        )
        BOOT_UNKNOWN -> BootIdentity.Unknown
        else -> throw SnapshotMappingException(
            "snapshot ${entity.snapshotId} has an unrecognised boot identity kind " +
                "'${entity.bootKind}'",
        )
    }

    /**
     * Resolves a stored enum name.
     *
     * A name that no longer exists fails loudly. Falling back to `UNKNOWN` would be worse
     * than it sounds: it would turn "we cannot read this history" into "this history says
     * unknown", which is a different and false statement.
     */
    private inline fun <reified T : Enum<T>> enumOf(stored: String, role: String): T =
        enumValues<T>().firstOrNull { it.name == stored }
            ?: throw SnapshotMappingException(
                "stored $role '$stored' is not a recognised ${T::class.simpleName} value",
            )

    private fun uuidOf(stored: String, role: String): UUID = try {
        UUID.fromString(stored)
    } catch (t: IllegalArgumentException) {
        throw SnapshotMappingException("stored $role '$stored' is not a valid identifier")
    }

    internal const val BOOT_KERNEL = "KERNEL"
    internal const val BOOT_DERIVED = "DERIVED"
    internal const val BOOT_UNKNOWN = "UNKNOWN"
}

package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.series.BatterySampleStore
import com.rmpsdroid.battinsight.series.BatterySeries
import com.rmpsdroid.battinsight.series.BatterySeriesBuilder
import com.rmpsdroid.battinsight.series.BatterySeriesPoint
import com.rmpsdroid.battinsight.series.SampleResult
import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.PersistenceOutcome
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionTrigger
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * The sampled battery series, on disk.
 *
 * Thin by design: the interesting decisions live either in the DAO transaction (the cap and
 * the watermark) or in the pure builder (segments and gaps). What is left here is mapping,
 * and refusing to write a row that has nowhere to belong.
 */
class RoomBatterySampleStore(
    private val dao: BatterySampleDao,
    private val cap: Int = BatterySampleStore.MAX_BATTERY_SAMPLES_PER_SESSION,
    private val cadenceMillis: Long = BatterySampleStore.BATTERY_SAMPLE_CADENCE_MILLIS,
) : BatterySampleStore {

    override suspend fun record(
        sessionId: String,
        observation: BatteryObservation,
        trigger: SessionTrigger,
        counterGeneration: CounterGeneration,
    ): SampleResult {
        // Blank rather than merely null-checked: an empty session id would satisfy a Kotlin
        // non-null type and still be a row belonging to nothing.
        if (sessionId.isBlank()) return SampleResult.NoActiveSession

        val entity = BatterySampleEntity(
            sampleId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sampleElapsedRealtimeMillis = observation.time.elapsedRealtime.millis,
            sampleWallClockMillis = observation.time.wallClockMillis,
            sampleUtcOffsetMinutes = observation.time.utcOffsetMinutes,
            bootKind = when (observation.bootIdentity) {
                is BootIdentity.Kernel -> "KERNEL"
                is BootIdentity.Derived -> "DERIVED"
                BootIdentity.Unknown -> "UNKNOWN"
            },
            bootKernelId = (observation.bootIdentity as? BootIdentity.Kernel)?.id,
            bootDerivedMillis = (observation.bootIdentity as? BootIdentity.Derived)
                ?.approximateBootWallClockMillis,
            level = observation.level,
            scale = observation.scale,
            batteryStatus = observation.status.name,
            plugSource = observation.plug.name,
            temperatureDeciCelsius = observation.temperatureDeciCelsius,
            voltageMilliVolts = observation.voltageMilliVolts,
            chargeCounterMicroAmpHours = observation.chargeCounterMicroAmpHours,
            trigger = trigger.name,
            counterGeneration = counterGeneration.value,
        )

        return try {
            dao.insertAndRetain(entity, cap)
            SampleResult.Stored(entity.sampleId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            SampleResult.Failed(classify(t), t.message ?: t.javaClass.simpleName)
        }
    }

    override suspend fun samplesFor(sessionId: String): List<BatterySeriesPoint> =
        dao.samplesFor(sessionId).mapNotNull { it.toPoint() }

    override suspend fun lastSampleFor(sessionId: String): BatterySeriesPoint? =
        dao.lastSampleFor(sessionId)?.toPoint()

    override suspend fun countFor(sessionId: String): Int = dao.countFor(sessionId)

    override suspend fun evictedThroughElapsedMillis(sessionId: String): Long? =
        dao.watermarkFor(sessionId)

    override suspend fun seriesFor(sessionId: String): BatterySeries =
        BatterySeriesBuilder.build(
            sessionId = sessionId,
            samples = samplesFor(sessionId),
            cadenceMillis = cadenceMillis,
            evictedThroughElapsedMillis = evictedThroughElapsedMillis(sessionId),
        )

    /**
     * Rebuilds a stored row as a domain point.
     *
     * Null when an enum no longer parses -- a downgrade, or a row written by a build that knew
     * a value this one does not. Dropping the point is the honest outcome: the alternative is
     * substituting a default and charting a status the device never reported.
     *
     * Boot identity is rebuilt at its original strength, the rule Phase 6 established: a
     * stored `DERIVED` estimate must never come back as a `Kernel`, because every continuity
     * decision downstream rests on how strong that evidence actually was.
     */
    private fun BatterySampleEntity.toPoint(): BatterySeriesPoint? {
        val boot = when (bootKind) {
            "KERNEL" -> bootKernelId?.let { BootIdentity.Kernel(it) }
            "DERIVED" -> bootDerivedMillis?.let { BootIdentity.Derived(it) }
            "UNKNOWN" -> BootIdentity.Unknown
            else -> null
        } ?: return null

        return BatterySeriesPoint(
            elapsedRealtimeMillis = sampleElapsedRealtimeMillis,
            wallClockMillis = sampleWallClockMillis,
            utcOffsetMinutes = sampleUtcOffsetMinutes,
            bootIdentity = boot,
            level = level,
            scale = scale,
            status = enumOrNull<BatteryStatus>(batteryStatus) ?: return null,
            plug = enumOrNull<PlugSource>(plugSource) ?: return null,
            temperatureDeciCelsius = temperatureDeciCelsius,
            voltageMilliVolts = voltageMilliVolts,
            chargeCounterMicroAmpHours = chargeCounterMicroAmpHours,
            trigger = enumOrNull<SessionTrigger>(trigger) ?: SessionTrigger.UNKNOWN,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(stored: String): T? =
        enumValues<T>().firstOrNull { it.name == stored }

    private fun classify(t: Throwable): PersistenceOutcome = when (t) {
        is android.database.sqlite.SQLiteConstraintException -> PersistenceOutcome.CONSTRAINT_FAILURE
        is IllegalStateException -> PersistenceOutcome.DATABASE_UNAVAILABLE
        is android.database.sqlite.SQLiteException -> PersistenceOutcome.DATABASE_UNAVAILABLE
        else -> PersistenceOutcome.UNKNOWN
    }
}

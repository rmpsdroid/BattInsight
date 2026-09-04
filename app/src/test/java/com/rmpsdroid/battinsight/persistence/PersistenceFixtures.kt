package com.rmpsdroid.battinsight.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * Shared building blocks for the persistence tests.
 *
 * An in-memory Room database is used rather than a file: it exercises the real schema, real
 * SQLite and the real generated DAO, while leaving nothing behind between tests.
 */

/** A fresh database, isolated per test. */
fun testDatabase(): BattInsightDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext(),
    BattInsightDatabase::class.java,
)
    // Foreign keys are the point of several of these tests, and an in-memory builder does
    // not enable them by default the way the production builder path does.
    .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
    .build()

const val EPOCH = 1_700_000_000_000L
const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE

fun uuid(n: Long): UUID = UUID(0L, n)

/**
 * A snapshot with every optional field populated, so a round trip has something to lose.
 *
 * The variants below deliberately strip fields instead, because "did the nulls survive" is a
 * different question from "did the values survive".
 */
fun fullSnapshot(
    id: UUID = uuid(1),
    sessionId: UUID = uuid(100),
    boot: BootIdentity = BootIdentity.Kernel("11111111-2222-3333-4444-555555555555"),
    elapsedMillis: Long = 5 * MINUTE,
    wallClockMillis: Long = EPOCH + 5 * MINUTE,
    utcOffsetMinutes: Int = 330,
    trigger: SessionTrigger = SessionTrigger.APP_START,
    observationTrigger: SessionTrigger = SessionTrigger.BATTERY_CHANGED,
    status: BatteryStatus = BatteryStatus.DISCHARGING,
    plug: PlugSource = PlugSource.NONE,
    generation: CounterGeneration = CounterGeneration(3),
    schema: SnapshotSchemaVersion = SnapshotSchemaVersion(1),
    source: CounterSource = CounterSource.NONE,
): BatterySnapshot {
    val time = CaptureTime(ElapsedRealtime(elapsedMillis), wallClockMillis, utcOffsetMinutes)
    return BatterySnapshot(
        id = id,
        sessionId = sessionId,
        bootIdentity = boot,
        time = time,
        trigger = trigger,
        battery = BatteryObservation(
            time = time,
            bootIdentity = boot,
            status = status,
            plug = plug,
            level = 73,
            scale = 100,
            present = true,
            temperatureDeciCelsius = 251,
            voltageMilliVolts = 4123,
            chargeCounterMicroAmpHours = 3_210_000L,
            health = BatteryHealth.GOOD,
            trigger = observationTrigger,
        ),
        counterGeneration = generation,
        schemaVersion = schema,
        counterSource = source,
        platformVersionAtCapture = "16",
        appVersionAtCapture = "0.0.1-foundation",
    )
}

/** A snapshot whose optional measurements are all absent. */
fun sparseSnapshot(
    id: UUID = uuid(2),
    sessionId: UUID = uuid(100),
    boot: BootIdentity = BootIdentity.Unknown,
    elapsedMillis: Long = MINUTE,
): BatterySnapshot {
    val time = CaptureTime(ElapsedRealtime(elapsedMillis), EPOCH + elapsedMillis, -480)
    return BatterySnapshot(
        id = id,
        sessionId = sessionId,
        bootIdentity = boot,
        time = time,
        trigger = SessionTrigger.UNKNOWN,
        battery = BatteryObservation(
            time = time,
            bootIdentity = boot,
            status = BatteryStatus.UNKNOWN,
            plug = PlugSource.UNKNOWN,
            level = null,
            scale = null,
            present = null,
            temperatureDeciCelsius = null,
            voltageMilliVolts = null,
            chargeCounterMicroAmpHours = null,
            health = BatteryHealth.UNKNOWN,
            trigger = SessionTrigger.UNKNOWN,
        ),
        counterGeneration = CounterGeneration.INITIAL,
        counterSource = CounterSource.NONE,
        platformVersionAtCapture = null,
        appVersionAtCapture = null,
    )
}

/** An interval that is still running: no end snapshot, and none required. */
fun activeSession(
    id: UUID = uuid(100),
    type: SessionType = SessionType.DISCHARGE,
    start: BatterySnapshot = fullSnapshot(id = uuid(1), sessionId = id),
    latest: BatterySnapshot = start,
    generation: CounterGeneration = CounterGeneration(3),
) = BatterySession(
    id = id,
    type = type,
    start = start,
    latest = latest,
    end = null,
    endReason = SessionBoundaryReason.NONE,
    counterGeneration = generation,
)

/** An interval that has ended. */
fun closedSession(
    id: UUID = uuid(101),
    type: SessionType = SessionType.CHARGE,
    reason: SessionBoundaryReason = SessionBoundaryReason.POWER_TRANSITION,
): BatterySession {
    val start = fullSnapshot(id = uuid(10), sessionId = id, elapsedMillis = 0)
    val end = fullSnapshot(id = uuid(11), sessionId = id, elapsedMillis = HOUR)
    return BatterySession(
        id = id,
        type = type,
        start = start,
        latest = end,
        end = end,
        endReason = reason,
        counterGeneration = CounterGeneration(3),
    )
}

package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.batterystats.PartialWakelockStat
import com.rmpsdroid.battinsight.batterystats.SessionCounterState
import com.rmpsdroid.battinsight.batterystats.StoredCounterCapture
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.PersistenceOutcome
import com.rmpsdroid.battinsight.session.PersistenceResult
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Why a counter capture was not stored.
 *
 * Separate from [PersistenceOutcome], which describes database trouble. These describe a
 * capture that reached us intact and is still not fit to become a durable baseline.
 */
enum class CounterRejection {
    /** The decode did not succeed, or succeeded only partially. */
    NOT_A_COMPLETE_CAPTURE,

    /**
     * The checkin version has not been verified against a real capture.
     *
     * Deliberately stricter than the decoder. Phase 7A tolerates an unknown version so that a
     * future Android release does not simply fail, and shows the result with a warning. That
     * is the right call for a transient display and the wrong one for a durable baseline: a
     * baseline is subtracted from for the rest of the session, so a layout we have only
     * assumed would silently contaminate every later delta.
     */
    UNVERIFIED_CHECKIN_VERSION,

    /** The accounting window spans an OS update, so it can never be a comparable baseline. */
    PLATFORM_CHANGED,

    /**
     * Two counters shared one identity.
     *
     * Refused rather than deduplicated. Measured across both real captures there are no
     * duplicates on `(window, name)` or `(window, uid, name)` -- 68, 108, 315 and 231 rows
     * with zero collisions -- so a duplicate means something we do not understand, and
     * silently keeping the last one would discard a measurement to satisfy a primary key.
     */
    DUPLICATE_COUNTER_IDENTITY,
}

/** What happened when a capture was offered to the store. */
sealed interface CounterPersistResult {
    data class Stored(val captureId: String, val role: Role) : CounterPersistResult
    data class Rejected(val reason: CounterRejection, val detail: String) : CounterPersistResult
    data class Failed(val outcome: PersistenceOutcome, val detail: String) : CounterPersistResult

    enum class Role { BASELINE, LATEST }

    val succeeded: Boolean get() = this is Stored
}

/**
 * Durable storage for decoded counters, and the boundary everything above it uses.
 *
 * No view model and no Composable touches [CounterDao]; they come here. The store owns three
 * decisions that must not be spread around:
 *
 *  - whether a capture is fit to be stored at all;
 *  - whether it becomes the baseline or replaces the latest;
 *  - that either outcome is one transaction.
 *
 * Growth is bounded per session, not per refresh: a hundred refreshes leave one baseline and
 * one latest.
 */
class RoomCounterStore(private val dao: CounterDao) {

    /**
     * Stores a decoded capture against a battery session.
     *
     * The first acceptable capture becomes the baseline and the latest at once, sharing a
     * single row. Every later one replaces the latest and leaves the baseline untouched.
     *
     * @param newCaptureId injected so tests are deterministic; production passes nothing.
     */
    suspend fun store(
        capture: BatteryStatsCapture,
        batterySessionId: String,
        batterySnapshotId: String?,
        counterGeneration: CounterGeneration,
        bootIdentity: BootIdentity,
        newCaptureId: String = UUID.randomUUID().toString(),
    ): CounterPersistResult {
        rejectionFor(capture)?.let { return it }

        val entity = captureEntity(
            capture, newCaptureId, batterySessionId, batterySnapshotId,
            counterGeneration, bootIdentity,
        )
        val kernelRows = capture.kernelWakelocks.map { it.toEntity(newCaptureId) }
        val partialRows = capture.partialWakelocks.map { it.toEntity(newCaptureId) }

        // Duplicate identities are refused before any write, not resolved by the primary key.
        duplicateIn(kernelRows.map { it.accountingWindow to it.name }, "kernel wakelock")
            ?.let { return it }
        duplicateIn(
            partialRows.map { Triple(it.accountingWindow, it.uid, it.name) },
            "partial wakelock",
        )?.let { return it }

        return try {
            val existing = dao.state(batterySessionId)
            if (existing == null) {
                dao.establishBaseline(entity, kernelRows, partialRows)
                CounterPersistResult.Stored(newCaptureId, CounterPersistResult.Role.BASELINE)
            } else {
                dao.replaceLatest(
                    capture = entity,
                    kernelWakelocks = kernelRows,
                    partialWakelocks = partialRows,
                    baselineCaptureId = existing.baselineCaptureId,
                    supersededCaptureId = existing.latestCaptureId,
                )
                CounterPersistResult.Stored(newCaptureId, CounterPersistResult.Role.LATEST)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            CounterPersistResult.Failed(classify(t), t.message ?: t.javaClass.simpleName)
        }
    }

    /** Reads back baseline and latest for a session, or null when none has been stored. */
    suspend fun state(batterySessionId: String): SessionCounterState? {
        val state = dao.state(batterySessionId) ?: return null
        val baseline = load(state.baselineCaptureId) ?: return null
        val latest = if (state.latestCaptureId == state.baselineCaptureId) {
            baseline
        } else {
            load(state.latestCaptureId) ?: return null
        }
        return SessionCounterState(batterySessionId, baseline, latest)
    }

    /** How many capture rows exist. Used to prove bounded retention. */
    suspend fun captureCount(): Int = dao.captureCount()

    suspend fun captureCountFor(sessionId: String): Int = dao.captureCountFor(sessionId)

    suspend fun counterRowCounts(): Pair<Int, Int> =
        dao.kernelWakelockRowCount() to dao.partialWakelockRowCount()

    /** Forgets every stored counter. Session history is untouched. */
    suspend fun clear(): PersistenceResult = try {
        dao.clearAllCounters()
        PersistenceResult.Success
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        PersistenceResult.Failure(classify(t), t.message ?: t.javaClass.simpleName)
    }

    // ------------------------------------------------------------------------ internals

    private fun rejectionFor(capture: BatteryStatsCapture): CounterPersistResult.Rejected? {
        if (capture.metadata.truncated) {
            return CounterPersistResult.Rejected(
                CounterRejection.NOT_A_COMPLETE_CAPTURE,
                "a truncated capture is missing its late sections, not reporting them as absent",
            )
        }
        if (capture.version.spansPlatformChange) {
            return CounterPersistResult.Rejected(
                CounterRejection.PLATFORM_CHANGED,
                "the accounting window spans an OS update, so it can never be a comparable baseline",
            )
        }
        if (capture.version.checkinVersion !in VERIFIED_CHECKIN_VERSIONS) {
            return CounterPersistResult.Rejected(
                CounterRejection.UNVERIFIED_CHECKIN_VERSION,
                "checkin version ${capture.version.checkinVersion} has not been verified " +
                    "against a measured capture; it may be displayed but not stored as a baseline",
            )
        }
        return null
    }

    private fun <T> duplicateIn(keys: List<T>, what: String): CounterPersistResult.Rejected? {
        if (keys.size == keys.toSet().size) return null
        return CounterPersistResult.Rejected(
            CounterRejection.DUPLICATE_COUNTER_IDENTITY,
            "two $what records share one identity; refusing rather than discarding one",
        )
    }

    private suspend fun load(captureId: String): StoredCounterCapture? {
        val row = dao.capture(captureId) ?: return null
        return StoredCounterCapture(
            captureId = row.captureId,
            batterySessionId = row.batterySessionId,
            batterySnapshotId = row.batterySnapshotId,
            sourceFormat = enumOrNull<SourceFormat>(row.sourceFormat) ?: return null,
            backendKind = enumOrNull<BackendIdentity.Kind>(row.backendKind) ?: return null,
            version = CheckinVersionBlock(
                recordFormatVersion = row.recordFormatVersion,
                checkinVersion = row.checkinVersion,
                parcelVersion = row.parcelVersion,
                startPlatformVersion = row.platformStartFingerprint,
                endPlatformVersion = row.platformEndFingerprint,
            ),
            platformChanged = row.platformChanged,
            checkinVersionVerified = row.checkinVersionVerified,
            captureElapsedRealtimeMillis = row.captureElapsedRealtimeMillis,
            captureWallClockMillis = row.captureWallClockMillis,
            counterGeneration = CounterGeneration(row.counterGeneration),
            bootIdentity = bootIdentityOf(row) ?: return null,
            payloadByteCount = row.payloadByteCount,
            warningCount = row.warningCount,
            kernelWakelocks = dao.kernelWakelocks(captureId).mapNotNull { it.toDomain() },
            partialWakelocks = dao.partialWakelocks(captureId).mapNotNull { it.toDomain() },
        )
    }

    /**
     * Rebuilds boot identity at its original strength.
     *
     * The same rule Phase 6 established for snapshots: a stored `DERIVED` estimate must never
     * come back as a `Kernel`, because evidence strength is what every comparison rests on.
     */
    private fun bootIdentityOf(row: CounterCaptureEntity): BootIdentity? = when (row.bootKind) {
        "KERNEL" -> row.bootKernelId?.let { BootIdentity.Kernel(it) }
        "DERIVED" -> row.bootDerivedMillis?.let { BootIdentity.Derived(it) }
        "UNKNOWN" -> BootIdentity.Unknown
        else -> null
    }

    private fun captureEntity(
        capture: BatteryStatsCapture,
        captureId: String,
        batterySessionId: String,
        batterySnapshotId: String?,
        counterGeneration: CounterGeneration,
        bootIdentity: BootIdentity,
    ) = CounterCaptureEntity(
        captureId = captureId,
        batterySessionId = batterySessionId,
        batterySnapshotId = batterySnapshotId,
        sourceFormat = capture.metadata.sourceFormat.name,
        sourceFormatVersion = capture.metadata.sourceFormatVersion,
        backendKind = capture.metadata.backendKind.name,
        recordFormatVersion = capture.version.recordFormatVersion,
        checkinVersion = capture.version.checkinVersion,
        parcelVersion = capture.version.parcelVersion,
        platformStartFingerprint = capture.version.startPlatformVersion,
        platformEndFingerprint = capture.version.endPlatformVersion,
        platformChanged = capture.version.spansPlatformChange,
        captureElapsedRealtimeMillis = capture.metadata.captureElapsedRealtimeMillis,
        captureWallClockMillis = capture.metadata.captureWallClockMillis,
        counterGeneration = counterGeneration.value,
        bootKind = when (bootIdentity) {
            is BootIdentity.Kernel -> "KERNEL"
            is BootIdentity.Derived -> "DERIVED"
            BootIdentity.Unknown -> "UNKNOWN"
        },
        bootKernelId = (bootIdentity as? BootIdentity.Kernel)?.id,
        bootDerivedMillis = (bootIdentity as? BootIdentity.Derived)?.approximateBootWallClockMillis,
        payloadByteCount = capture.metadata.payloadByteCount,
        payloadHash = capture.metadata.payloadHash,
        warningCount = capture.warnings.size,
        checkinVersionVerified = capture.version.checkinVersion in VERIFIED_CHECKIN_VERSIONS,
    )

    private fun KernelWakelockStat.toEntity(captureId: String) = KernelWakelockCounterEntity(
        captureId = captureId,
        accountingWindow = window.name,
        name = name,
        totalDurationMillis = totalTimeMillis,
        count = count,
    )

    private fun PartialWakelockStat.toEntity(captureId: String) = PartialWakelockCounterEntity(
        captureId = captureId,
        accountingWindow = window.name,
        uid = uid,
        name = name,
        totalDurationMillis = totalTimeMillis,
        count = count,
    )

    private fun KernelWakelockCounterEntity.toDomain(): KernelWakelockStat? =
        enumOrNull<AggregationWindow>(accountingWindow)?.let {
            KernelWakelockStat(name, totalDurationMillis, count, it)
        }

    private fun PartialWakelockCounterEntity.toDomain(): PartialWakelockStat? =
        enumOrNull<AggregationWindow>(accountingWindow)?.let {
            PartialWakelockStat(uid, name, totalDurationMillis, count, it)
        }

    private inline fun <reified T : Enum<T>> enumOrNull(stored: String): T? =
        enumValues<T>().firstOrNull { it.name == stored }

    private fun classify(t: Throwable): PersistenceOutcome = when (t) {
        is android.database.sqlite.SQLiteConstraintException -> PersistenceOutcome.CONSTRAINT_FAILURE
        is IllegalStateException -> PersistenceOutcome.DATABASE_UNAVAILABLE
        is android.database.sqlite.SQLiteException -> PersistenceOutcome.DATABASE_UNAVAILABLE
        else -> PersistenceOutcome.UNKNOWN
    }

    private companion object {
        /**
         * Checkin versions whose record layouts have been verified against a real capture.
         *
         * The same set the decoder uses, applied more strictly: the decoder warns and carries
         * on, the store refuses to build a durable baseline.
         */
        val VERIFIED_CHECKIN_VERSIONS = setOf(34, 36)
    }
}

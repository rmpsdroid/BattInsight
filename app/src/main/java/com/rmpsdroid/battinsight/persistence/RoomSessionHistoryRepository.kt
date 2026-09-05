package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.batterystats.CounterDeltaEngine
import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.batterystats.CounterDeltaResult
import com.rmpsdroid.battinsight.history.BatteryLevel
import com.rmpsdroid.battinsight.history.CaptureSummary
import com.rmpsdroid.battinsight.history.CounterAvailability
import com.rmpsdroid.battinsight.history.SessionDetail
import com.rmpsdroid.battinsight.history.SessionHistoryRepository
import com.rmpsdroid.battinsight.history.SessionHistoryRow
import com.rmpsdroid.battinsight.history.SessionProvenance
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType
import kotlinx.coroutines.CancellationException

/**
 * Reads history out of Room and hands back domain models.
 *
 * The mapping happens here and nowhere above it. A `SessionEntity` never leaves this file, so
 * a column rename is a change to one mapper rather than to every screen that displayed it.
 *
 * ## Why a bounded query rather than Paging 3
 *
 * Phase 6 chose to retain session history indefinitely, so the table grows without a ceiling
 * and loading all of it would eventually be wrong. But "eventually" is doing real work in that
 * sentence: a session is roughly one charge cycle, so a heavy user produces a few hundred rows
 * a year.
 *
 * Paging 3 solves a problem this does not have -- thousands of rows arriving while the user
 * scrolls -- and costs a `PagingSource`, a `Pager`, differ-based list state and a testing
 * story for all of it. A bounded query with an explicit "load more" is a few lines, is
 * trivially testable on the JVM, and stops the unbounded read just as effectively. It is
 * revisited if the row count ever justifies it, which is a measurement nobody can make yet.
 */
class RoomSessionHistoryRepository(
    private val sessionDao: SessionDao,
    private val counterDao: CounterDao,
) : SessionHistoryRepository {

    private val counterStore = RoomCounterStore(counterDao)

    override suspend fun recentSessions(limit: Int, before: Long?): List<SessionHistoryRow> =
        sessionDao.recentSessions(limit, before).mapNotNull { row(it) }

    override suspend fun currentSession(): SessionHistoryRow? {
        // The engine-state row is the authoritative answer to "which session is current".
        // Scanning for sessions with no end snapshot would find the same thing most of the
        // time and would disagree at exactly the moment it mattered.
        val sessionId = sessionDao.engineState()?.sessionId ?: return null
        return sessionDao.session(sessionId)?.let { row(it) }
    }

    override suspend fun sessionCount(): Int = sessionDao.sessionCount()

    override suspend fun sessionDetail(sessionId: String): SessionDetail? {
        val entity = sessionDao.session(sessionId) ?: return null
        val row = row(entity) ?: return null
        val snapshots = snapshotsFor(entity)
        val start = snapshots[entity.startSnapshotId] ?: return null

        val provenance = SessionProvenance(
            startTrigger = enumOrNull<SessionTrigger>(start.trigger) ?: SessionTrigger.UNKNOWN,
            endReason = enumOrNull<SessionBoundaryReason>(entity.endReason)
                ?: SessionBoundaryReason.NONE,
            startObserved = (enumOrNull<SessionTrigger>(start.trigger) ?: SessionTrigger.UNKNOWN)
                .isObserved,
            counterGeneration = entity.counterGeneration,
            snapshotSchemaVersion = start.snapshotSchemaVersion,
            bootIdentityLabel = bootLabel(start),
        )

        val state = counterStore.state(sessionId)
            ?: return SessionDetail(row, provenance, null, emptyList(), emptyList(), null, null)

        val summary = CaptureSummary(
            baselineWallClockMillis = state.baseline.captureWallClockMillis,
            latestWallClockMillis = state.latest.captureWallClockMillis,
            baselineIsLatest = state.baselineIsLatest,
            sourceFormat = state.latest.sourceFormat,
            backendKind = state.latest.backendKind,
            checkinVersion = state.latest.version.checkinVersion,
            recordFormatVersion = state.latest.version.recordFormatVersion,
            checkinVersionVerified = state.latest.checkinVersionVerified,
            platformChanged = state.latest.platformChanged,
            warningCount = state.latest.warningCount,
            payloadByteCount = state.latest.payloadByteCount,
        )

        val kernel = CounterDeltaEngine.kernelWakelockDeltas(state)
        val partial = CounterDeltaEngine.partialWakelockDeltas(state)

        // If the pair is refused, both lists stay empty. Phase 7B.1 established that a
        // decrease invalidates every counter in the capture, so showing "the ones that still
        // look positive" would be showing numbers we have already decided are untrustworthy.
        val refusal = kernel as? CounterDeltaResult.NotComparable
        return SessionDetail(
            row = row,
            provenance = provenance,
            captures = summary,
            kernelDeltas = (kernel as? CounterDeltaResult.Success)?.value.orEmpty(),
            partialDeltas = (partial as? CounterDeltaResult.Success)?.value.orEmpty(),
            unavailableReason = refusal?.reason,
            continuityDetail = refusal?.detail?.takeIf {
                refusal.reason == CounterDeltaReason.COUNTER_DECREASED
            },
        )
    }

    // ------------------------------------------------------------------------ internals

    private suspend fun row(entity: SessionEntity): SessionHistoryRow? {
        val snapshots = snapshotsFor(entity)
        val start = snapshots[entity.startSnapshotId] ?: return null
        val last = snapshots[entity.endSnapshotId ?: entity.latestSnapshotId] ?: start

        return SessionHistoryRow(
            sessionId = entity.sessionId,
            type = enumOrNull<SessionType>(entity.sessionType) ?: SessionType.UNKNOWN,
            isActive = entity.endSnapshotId == null,
            startWallClockMillis = start.wallClockMillis,
            endWallClockMillis = entity.endSnapshotId?.let { last.wallClockMillis },
            // Monotonic, never the wall clocks above. A clock change between the two would
            // otherwise produce a duration that is simply wrong, including a negative one.
            durationMillis = (last.elapsedRealtimeMillis - start.elapsedRealtimeMillis)
                .takeIf { it >= 0L },
            startBattery = levelOf(start),
            endBattery = levelOf(last),
            startTrigger = enumOrNull<SessionTrigger>(start.trigger) ?: SessionTrigger.UNKNOWN,
            endReason = enumOrNull<SessionBoundaryReason>(entity.endReason)
                ?: SessionBoundaryReason.NONE,
            counters = availabilityFor(entity.sessionId),
        )
    }

    private suspend fun snapshotsFor(entity: SessionEntity): Map<String, SnapshotEntity> {
        val wanted = buildSet {
            add(entity.startSnapshotId)
            add(entity.latestSnapshotId)
            entity.endSnapshotId?.let { add(it) }
        }
        return sessionDao.snapshots(wanted.toList()).associateBy { it.snapshotId }
    }

    private suspend fun availabilityFor(sessionId: String): CounterAvailability {
        val state = try {
            counterStore.state(sessionId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        } ?: return CounterAvailability.NoCapture

        if (state.baselineIsLatest) return CounterAvailability.BaselineOnly

        val kernel = CounterDeltaEngine.kernelWakelockDeltas(state)
        if (kernel is CounterDeltaResult.NotComparable) {
            return CounterAvailability.DeltaUnavailable(kernel.reason)
        }
        val partial = CounterDeltaEngine.partialWakelockDeltas(state)
        val kernelValues = (kernel as? CounterDeltaResult.Success)?.value.orEmpty()
        val partialValues = (partial as? CounterDeltaResult.Success)?.value.orEmpty()

        return CounterAvailability.DeltaAvailable(
            kernelWakelockCount = kernelValues.size,
            partialWakelockCount = partialValues.size,
            // A comparable pair where nothing moved is a measurement of no activity, and the
            // UI says so rather than showing an empty list that looks like missing data.
            allZero = kernelValues.all { it.durationDeltaMillis == 0L && it.countDelta == 0L } &&
                partialValues.all { it.durationDeltaMillis == 0L && it.countDelta == 0L },
        )
    }

    private fun levelOf(snapshot: SnapshotEntity): BatteryLevel? {
        val level = snapshot.level ?: return null
        val scale = snapshot.scale ?: return null
        return BatteryLevel(level, scale)
    }

    private fun bootLabel(snapshot: SnapshotEntity): String = when (snapshot.bootKind) {
        "KERNEL" -> snapshot.bootKernelId?.take(8) ?: "kernel"
        "DERIVED" -> "estimated"
        else -> "unknown"
    }

    private inline fun <reified T : Enum<T>> enumOrNull(stored: String): T? =
        enumValues<T>().firstOrNull { it.name == stored }
}

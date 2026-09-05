package com.rmpsdroid.battinsight.history

import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.batterystats.KernelWakelockDelta
import com.rmpsdroid.battinsight.batterystats.PartialWakelockDelta
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType

/**
 * What the history screens are allowed to know.
 *
 * Domain types, not Room entities. Nothing above this line has ever seen a `SessionEntity`,
 * and it must stay that way: the predecessor rendered database rows directly, so a schema
 * change broke screens rather than one mapper.
 *
 * These are also deliberately *pure* -- no Android, no Compose, no formatting. Every rule
 * about what a session means is decidable from the values here, which is what lets the whole
 * presentation layer be tested on the JVM without a device.
 */

/**
 * One row in the history list.
 *
 * Carries the identity for navigation and enough state to render a row without a second
 * query. Deliberately not the whole session: a list of two hundred rows should not drag two
 * hundred sets of wakelock counters into memory.
 */
data class SessionHistoryRow(
    val sessionId: String,
    val type: SessionType,
    val isActive: Boolean,
    /** For display and ordering only. Never used for arithmetic. */
    val startWallClockMillis: Long,
    val endWallClockMillis: Long?,
    /**
     * Measured from the monotonic clock, never from the wall clocks above.
     *
     * Null when it cannot be measured -- which is a real state, not zero. A session whose
     * boundary was recovered across a process gap has a start and an end and no trustworthy
     * duration between them.
     */
    val durationMillis: Long?,
    val startBattery: BatteryLevel?,
    val endBattery: BatteryLevel?,
    /** How the interval began. */
    val startTrigger: SessionTrigger,
    /** Why it ended, or [SessionBoundaryReason.NONE] while it is still running. */
    val endReason: SessionBoundaryReason,
    val counters: CounterAvailability,
)

/**
 * A battery level as the device reported it.
 *
 * Level and scale are kept apart rather than pre-divided, because scale is not guaranteed to
 * be 100 and a percentage computed against an assumed scale is a wrong number that looks
 * right. A device reporting 50/200 is at 25%, not 50%.
 */
data class BatteryLevel(val level: Int, val scale: Int) {
    /** Null when the scale is unusable, rather than a division by zero or a false 0%. */
    val percent: Int? get() = if (scale > 0) (level * 100) / scale else null
}

/**
 * What counter data exists for a session, and whether it can be compared.
 *
 * Five states, not one boolean. "No capture", "a baseline and nothing to compare it with",
 * "two captures that cannot be compared" and "two captures showing no change" are four
 * genuinely different things, and a `hasData` flag would render them identically -- which is
 * how a user ends up believing their device recorded nothing when it recorded plenty.
 */
sealed interface CounterAvailability {

    /** No privileged capture has been stored for this session. */
    data object NoCapture : CounterAvailability

    /** A baseline exists and nothing has been captured since. Nothing to subtract yet. */
    data object BaselineOnly : CounterAvailability

    /** Two captures exist and may be subtracted. */
    data class DeltaAvailable(
        val kernelWakelockCount: Int,
        val partialWakelockCount: Int,
        /** True when both captures agree entirely -- a real measurement of no activity. */
        val allZero: Boolean,
    ) : CounterAvailability

    /** Two captures exist and may not be subtracted, for a stated reason. */
    data class DeltaUnavailable(val reason: CounterDeltaReason) : CounterAvailability

    val hasAnyCapture: Boolean get() = this !is NoCapture
}

/** Everything the detail screen shows about one session. */
data class SessionDetail(
    val row: SessionHistoryRow,
    val provenance: SessionProvenance,
    val captures: CaptureSummary?,
    /** Empty when the pair is not comparable. Never partially populated from a bad pair. */
    val kernelDeltas: List<KernelWakelockDelta>,
    val partialDeltas: List<PartialWakelockDelta>,
    /** Present when the deltas could not be computed. */
    val unavailableReason: CounterDeltaReason?,
    /**
     * Which counter first proved the accounting had restarted, when one did.
     *
     * Diagnostic only. Ordinary copy says the accounting changed rather than naming a
     * wakelock, because the wakelock did not cause it -- it is only where we noticed.
     */
    val continuityDetail: String?,
)

/** How a session began and what can be proven about it. */
data class SessionProvenance(
    val startTrigger: SessionTrigger,
    val endReason: SessionBoundaryReason,
    /** Whether the start was witnessed rather than reconstructed afterwards. */
    val startObserved: Boolean,
    val counterGeneration: Long,
    val snapshotSchemaVersion: Int,
    val bootIdentityLabel: String,
)

/** What was captured, and with what. */
data class CaptureSummary(
    val baselineWallClockMillis: Long,
    val latestWallClockMillis: Long,
    val baselineIsLatest: Boolean,
    val sourceFormat: SourceFormat,
    val backendKind: BackendIdentity.Kind,
    val checkinVersion: Int,
    val recordFormatVersion: Int,
    val checkinVersionVerified: Boolean,
    val platformChanged: Boolean,
    val warningCount: Int,
    val payloadByteCount: Int,
)

/**
 * Read-only access to stored history.
 *
 * Deliberately has no write operations. The screens that use this cannot insert, update or
 * delete anything, which is a stronger guarantee than remembering not to.
 *
 * Reads are suspending rather than reactive. History changes only when a capture is taken or
 * a session boundary occurs, both of which the view model already knows about, so a Flow
 * would add invalidation machinery for an event the caller triggers itself.
 */
interface SessionHistoryRepository {

    /**
     * Recent sessions, newest first.
     *
     * Bounded, with [before] paging further back. See the note in the Room implementation
     * about why this is a simple bounded query rather than Paging 3.
     */
    suspend fun recentSessions(limit: Int = DEFAULT_PAGE, before: Long? = null): List<SessionHistoryRow>

    /** The session currently running, if there is one. */
    suspend fun currentSession(): SessionHistoryRow?

    /** Everything the detail screen needs, or null when the session is unknown. */
    suspend fun sessionDetail(sessionId: String): SessionDetail?

    /** How many sessions are stored in total. */
    suspend fun sessionCount(): Int

    companion object {
        /**
         * How many rows one page holds.
         *
         * Fifty is enough to fill several screens of scrolling and small enough that the
         * query cost is irrelevant. See the Room implementation for why this is bounded at
         * all.
         */
        const val DEFAULT_PAGE = 50
    }
}

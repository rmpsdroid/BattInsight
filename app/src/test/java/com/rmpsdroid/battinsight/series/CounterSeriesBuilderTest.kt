package com.rmpsdroid.battinsight.series

import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.CounterDeltaEngine
import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.batterystats.CounterDeltaResult
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.batterystats.PartialWakelockStat
import com.rmpsdroid.battinsight.batterystats.SessionCounterState
import com.rmpsdroid.battinsight.batterystats.StoredCounterCapture
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adjacent-interval counter series, and how it differs from the whole-session summary.
 *
 * The distinction is the reason this type exists: baseline→N intervals are cumulative and
 * would produce a monotonically non-decreasing chart *by construction*, which looks like a
 * trend and is an artefact of the arithmetic.
 */
class CounterSeriesBuilderTest {

    @Test
    fun `adjacent captures produce one interval per pair`() {
        val series = build(capture("c0", 0, 100), capture("c1", 1000, 150), capture("c2", 2000, 175))

        assertEquals(2, series.intervals.size)
        assertEquals(2, series.comparableIntervals.size)
    }

    @Test
    fun `an interval measures only its own span, not everything since the baseline`() {
        // 100 -> 150 -> 175. Adjacent intervals are +50 and +25. A baseline-relative reading
        // would give +50 and +75, which is the artefact this separation prevents.
        val series = build(capture("c0", 0, 100), capture("c1", 1000, 150), capture("c2", 2000, 175))

        val deltas = series.comparableIntervals.map { it.kernelDeltas.single().durationDeltaMillis }
        assertEquals(listOf(50L, 25L), deltas)
    }

    @Test
    fun `a decreased counter refuses that interval and nothing else`() {
        // 100 -> 50 -> 120. The first interval is refused; the second is evaluated on its own
        // merits and is perfectly comparable. A refusal must not propagate forward forever.
        val series = build(capture("c0", 0, 100), capture("c1", 1000, 50), capture("c2", 2000, 120))

        assertEquals(2, series.intervals.size)
        val refused = series.intervals[0] as CounterInterval.Refused
        assertEquals(CounterDeltaReason.COUNTER_DECREASED, refused.reason)

        val next = series.intervals[1]
        assertTrue("the following interval is independent", next is CounterInterval.Comparable)
        assertEquals(70L, (next as CounterInterval.Comparable).kernelDeltas.single().durationDeltaMillis)
    }

    @Test
    fun `a refusal is pair-level, so no counter in that pair reports a delta`() {
        // One counter decreases while another rises. Phase 7B.1 established that the whole
        // pair is untrustworthy: the accounting origin moved underneath both, so the riser's
        // "+5" would be a number with no meaning.
        val from = capture(
            "c0", 0,
            kernel = listOf(kwl("a", 100), kwl("b", 5)),
        )
        val to = capture(
            "c1", 1000,
            kernel = listOf(kwl("a", 50), kwl("b", 10)),
        )
        val series = build(from, to)

        val refused = series.intervals.single() as CounterInterval.Refused
        assertEquals(CounterDeltaReason.COUNTER_DECREASED, refused.reason)
    }

    @Test
    fun `a different boot refuses the interval`() {
        val series = build(
            capture("c0", 0, 100, boot = BootIdentity.Kernel("boot-a")),
            capture("c1", 1000, 150, boot = BootIdentity.Kernel("boot-b")),
        )

        assertEquals(
            CounterDeltaReason.DIFFERENT_BOOT,
            (series.intervals.single() as CounterInterval.Refused).reason,
        )
    }

    @Test
    fun `a zero delta is a measurement, not missing data`() {
        val series = build(capture("c0", 0, 100), capture("c1", 1000, 100))

        val interval = series.comparableIntervals.single()
        assertEquals(
            "an unchanged counter still reports, with zero",
            0L,
            interval.kernelDeltas.single().durationDeltaMillis,
        )
    }

    @Test
    fun `a counter absent from one side does not appear, and does not become zero`() {
        val from = capture("c0", 0, kernel = listOf(kwl("a", 100)))
        val to = capture("c1", 1000, kernel = listOf(kwl("a", 150), kwl("newcomer", 40)))
        val series = build(from, to)

        val names = series.comparableIntervals.single().kernelDeltas.map { it.name }
        assertEquals("only the matched counter has an interval", listOf("a"), names)
    }

    @Test
    fun `fewer than two captures is no series at all`() {
        assertTrue(build(capture("c0", 0, 100)).intervals.isEmpty())
        assertTrue(CounterSeriesBuilder.build(SESSION, emptyList()).intervals.isEmpty())
    }

    @Test
    fun `captures are ordered by elapsed time regardless of input order`() {
        val series = build(capture("c2", 2000, 175), capture("c0", 0, 100), capture("c1", 1000, 150))

        assertEquals(listOf("c0", "c1"), series.intervals.map { it.fromCaptureId })
    }

    @Test
    fun `whole-session and adjacent readings answer different questions`() {
        // The same three captures, read both ways. The session total is 100 -> 175 = +75; the
        // adjacent series is +50 then +25. Both are correct answers to different questions,
        // and Phase 9B must never substitute one for the other.
        val c0 = capture("c0", 0, 100)
        val c1 = capture("c1", 1000, 150)
        val c2 = capture("c2", 2000, 175)

        val whole = CounterDeltaEngine.kernelWakelockDeltas(SessionCounterState(SESSION, c0, c2))
        assertEquals(
            75L,
            (whole as CounterDeltaResult.Success).value.single().durationDeltaMillis,
        )

        val adjacent = build(c0, c1, c2).comparableIntervals
            .map { it.kernelDeltas.single().durationDeltaMillis }
        assertEquals(listOf(50L, 25L), adjacent)
        assertEquals("and they sum to the same total", 75L, adjacent.sum())
    }

    // --------------------------------------------------------------------------- helpers

    private fun build(vararg captures: StoredCounterCapture) =
        CounterSeriesBuilder.build(SESSION, captures.toList())

    private fun kwl(name: String, millis: Long) =
        KernelWakelockStat(name, millis, 1L, AggregationWindow.SINCE_CHARGED)

    private fun capture(
        id: String,
        elapsed: Long,
        kernelMillis: Long? = null,
        kernel: List<KernelWakelockStat> = kernelMillis?.let { listOf(kwl("k", it)) } ?: emptyList(),
        boot: BootIdentity = BootIdentity.Kernel("boot-a"),
        generation: CounterGeneration = CounterGeneration(3),
        checkin: Int = 36,
        platform: String = "BUILD.A",
    ) = StoredCounterCapture(
        captureId = id,
        batterySessionId = SESSION,
        batterySnapshotId = null,
        sourceFormat = SourceFormat.CHECKIN,
        backendKind = BackendIdentity.Kind.SHELL,
        version = CheckinVersionBlock(9, checkin, 215L, platform, platform),
        platformChanged = false,
        checkinVersionVerified = true,
        captureElapsedRealtimeMillis = elapsed,
        captureWallClockMillis = 1_700_000_000_000L + elapsed,
        counterGeneration = generation,
        bootIdentity = boot,
        payloadByteCount = 900_000,
        warningCount = 0,
        kernelWakelocks = kernel,
        partialWakelocks = emptyList<PartialWakelockStat>(),
    )

    private companion object {
        const val SESSION = "00000000-0000-0000-0000-0000000000aa"
    }
}

package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When two counter captures may be subtracted, and what happens when they may not.
 *
 * Pure JVM. The whole comparison policy is decidable from two values, which is why it lives
 * outside Room and can be tested exhaustively here rather than through a database.
 *
 * The single idea behind almost every case below: **a missing counter never becomes zero.**
 * Every convenient shortcut in a delta engine is some version of substituting zero for
 * absent, and each one produces a confident wrong number rather than an honest refusal.
 */
class CounterDeltaEngineTest {

    // ------------------------------------------------------------------ comparability

    @Test
    fun `two ordinary captures from one boot are comparable`() {
        assertNull(CounterDeltaEngine.comparability(baseline(), latest()))
    }

    @Test
    fun `a reboot between captures refuses the comparison`() {
        val result = CounterDeltaEngine.comparability(
            baseline(boot = BootIdentity.Kernel("boot-a")),
            latest(boot = BootIdentity.Kernel("boot-b")),
        )
        assertEquals(CounterDeltaReason.DIFFERENT_BOOT, result!!.reason)
    }

    /**
     * A boot identity too weak to prove sameness refuses rather than assuming.
     *
     * Phase 5.1 established this: an unproven relation is not the same as a proven identical
     * one, and treating it as such would let a delta span a reboot nobody noticed.
     */
    @Test
    fun `an unprovable boot relation refuses the comparison`() {
        val result = CounterDeltaEngine.comparability(
            baseline(boot = BootIdentity.Unknown),
            latest(boot = BootIdentity.Unknown),
        )
        assertEquals(CounterDeltaReason.UNKNOWN, result!!.reason)
    }

    @Test
    fun `a counter generation change refuses the comparison`() {
        val result = CounterDeltaEngine.comparability(
            baseline(generation = CounterGeneration(1)),
            latest(generation = CounterGeneration(2)),
        )
        assertEquals(CounterDeltaReason.DIFFERENT_COUNTER_GENERATION, result!!.reason)
    }

    @Test
    fun `time running backwards refuses the comparison`() {
        val result = CounterDeltaEngine.comparability(
            baseline(elapsed = 10_000L),
            latest(elapsed = 5_000L),
        )
        assertEquals(CounterDeltaReason.TIME_REVERSED, result!!.reason)
    }

    @Test
    fun `a source format change refuses the comparison`() {
        val result = CounterDeltaEngine.comparability(
            baseline(),
            latest(format = SourceFormat.PROTO),
        )
        assertEquals(CounterDeltaReason.SOURCE_FORMAT_CHANGED, result!!.reason)
    }

    @Test
    fun `a checkin version change refuses the comparison`() {
        val result = CounterDeltaEngine.comparability(
            baseline(),
            latest(version = versionBlock(checkin = 37)),
        )
        assertEquals(CounterDeltaReason.CHECKIN_VERSION_CHANGED, result!!.reason)
    }

    @Test
    fun `a record format version change refuses the comparison`() {
        val result = CounterDeltaEngine.comparability(
            baseline(),
            latest(version = versionBlock(recordFormat = 10)),
        )
        assertEquals(CounterDeltaReason.CHECKIN_VERSION_CHANGED, result!!.reason)
    }

    /**
     * A window spanning an OS update is never subtracted.
     *
     * Counters either side of an update are not the same measurement, whatever the format
     * version says, and the platform fingerprints are the only evidence of it.
     */
    @Test
    fun `a platform change refuses the comparison from either side`() {
        assertEquals(
            CounterDeltaReason.PLATFORM_CHANGED,
            CounterDeltaEngine.comparability(baseline(platformChanged = true), latest())!!.reason,
        )
        assertEquals(
            CounterDeltaReason.PLATFORM_CHANGED,
            CounterDeltaEngine.comparability(baseline(), latest(platformChanged = true))!!.reason,
        )
    }

    /**
     * A reboot is reported ahead of a version change when both are true.
     *
     * Telling a user "the batterystats format changed" when their device actually restarted
     * is technically true and useless. The most fundamental objection wins.
     */
    @Test
    fun `the most fundamental objection is the one reported`() {
        val result = CounterDeltaEngine.comparability(
            baseline(boot = BootIdentity.Kernel("boot-a")),
            latest(boot = BootIdentity.Kernel("boot-b"), version = versionBlock(checkin = 99)),
        )
        assertEquals(CounterDeltaReason.DIFFERENT_BOOT, result!!.reason)
    }

    // -------------------------------------------------------------------- arithmetic

    @Test
    fun `a matched kernel wakelock yields its difference`() {
        val state = state(
            baselineKwl = listOf(kwl("bt_read", 1_000L, 10L)),
            latestKwl = listOf(kwl("bt_read", 3_500L, 25L)),
        )

        val result = CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "bt_read")

        val delta = (result as CounterDeltaResult.Success).value
        assertEquals(2_500L, delta.durationDeltaMillis)
        assertEquals(15L, delta.countDelta)
        assertEquals(ELAPSED, result.elapsedMillis)
    }

    @Test
    fun `a matched partial wakelock yields its difference, keyed by uid`() {
        val state = state(
            baselinePwl = listOf(pwl(10234, "SyncLock", 500L, 2L)),
            latestPwl = listOf(pwl(10234, "SyncLock", 900L, 5L)),
        )

        val delta = (
            CounterDeltaEngine.partialWakelockDelta(state, WINDOW, 10234, "SyncLock")
                as CounterDeltaResult.Success
            ).value

        assertEquals(400L, delta.durationDeltaMillis)
        assertEquals(3L, delta.countDelta)
        assertEquals(10234, delta.uid)
    }

    /**
     * The same wakelock name under two UIDs is two counters.
     *
     * Matching on name alone would add one application's time to another's.
     */
    @Test
    fun `the same wakelock name under different uids does not cross-match`() {
        val state = state(
            baselinePwl = listOf(pwl(1000, "Lock", 100L, 1L), pwl(10234, "Lock", 700L, 7L)),
            latestPwl = listOf(pwl(1000, "Lock", 150L, 2L), pwl(10234, "Lock", 900L, 9L)),
        )

        assertEquals(
            50L,
            (CounterDeltaEngine.partialWakelockDelta(state, WINDOW, 1000, "Lock")
                as CounterDeltaResult.Success).value.durationDeltaMillis,
        )
        assertEquals(
            200L,
            (CounterDeltaEngine.partialWakelockDelta(state, WINDOW, 10234, "Lock")
                as CounterDeltaResult.Success).value.durationDeltaMillis,
        )
    }

    /**
     * Zero to zero is a delta of zero, and that is a real answer.
     *
     * The Android 16 emulator reports 68 kernel wakelocks all at zero because it never truly
     * suspends. "Present throughout and idle" is a measurement; it must not be confused with
     * the absent case immediately below.
     */
    @Test
    fun `zero to zero is a valid zero delta, not missing data`() {
        val state = state(
            baselineKwl = listOf(kwl("idle", 0L, 0L)),
            latestKwl = listOf(kwl("idle", 0L, 0L)),
        )

        val result = CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "idle")

        assertTrue("a measured zero is a success", result.succeeded)
        assertEquals(0L, (result as CounterDeltaResult.Success).value.durationDeltaMillis)
    }

    @Test
    fun `an accounting window mismatch does not match counters across windows`() {
        val state = state(
            baselineKwl = listOf(kwl("x", 100L, 1L, AggregationWindow.SINCE_UNPLUGGED)),
            latestKwl = listOf(kwl("x", 500L, 5L, AggregationWindow.SINCE_CHARGED)),
        )

        val result = CounterDeltaEngine.kernelWakelockDelta(state, AggregationWindow.SINCE_CHARGED, "x")

        assertEquals(
            "the baseline has no counter in this window",
            CounterDeltaReason.COUNTER_MISSING_IN_BASELINE,
            (result as CounterDeltaResult.MissingData).reason,
        )
    }

    // ------------------------------------------------------------- missing is not zero

    @Test
    fun `a counter absent from the baseline is unavailable, not zero`() {
        val state = state(
            baselineKwl = emptyList(),
            latestKwl = listOf(kwl("newcomer", 4_000L, 3L)),
        )

        val result = CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "newcomer")

        assertEquals(
            CounterDeltaReason.COUNTER_MISSING_IN_BASELINE,
            (result as CounterDeltaResult.MissingData).reason,
        )
        assertTrue("it must not report 4000 as this session's usage", !result.succeeded)
    }

    @Test
    fun `a counter absent from the latest is unavailable, not zero`() {
        val state = state(
            baselineKwl = listOf(kwl("vanished", 4_000L, 3L)),
            latestKwl = emptyList(),
        )

        val result = CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "vanished")

        assertEquals(
            CounterDeltaReason.COUNTER_MISSING_IN_LATEST,
            (result as CounterDeltaResult.MissingData).reason,
        )
    }

    /**
     * An unmatched counter does not spoil the matched ones.
     *
     * A device that starts reporting a new kernel wakelock mid-session must not lose every
     * other delta because of it.
     */
    @Test
    fun `unmatched counters are omitted while matched ones still compute`() {
        val state = state(
            baselineKwl = listOf(kwl("stable", 100L, 1L), kwl("vanished", 50L, 1L)),
            latestKwl = listOf(kwl("stable", 400L, 4L), kwl("newcomer", 900L, 9L)),
        )

        val all = (CounterDeltaEngine.kernelWakelockDeltas(state) as CounterDeltaResult.Success).value

        assertEquals("only the matched counter is reported", 1, all.size)
        assertEquals("stable", all.single().name)
        assertEquals(300L, all.single().durationDeltaMillis)
    }

    // ------------------------------------------------------------------ decreases

    @Test
    fun `a decreasing counter is refused, never reported as negative`() {
        val state = state(
            baselineKwl = listOf(kwl("reset", 5_000L, 50L)),
            latestKwl = listOf(kwl("reset", 100L, 1L)),
        )

        val result = CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "reset")

        assertEquals(
            CounterDeltaReason.COUNTER_DECREASED,
            (result as CounterDeltaResult.NotComparable).reason,
        )
    }

    @Test
    fun `a decreasing count with a rising duration is still refused`() {
        val state = state(
            baselineKwl = listOf(kwl("odd", 100L, 50L)),
            latestKwl = listOf(kwl("odd", 900L, 3L)),
        )

        assertEquals(
            CounterDeltaReason.COUNTER_DECREASED,
            (CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "odd")
                as CounterDeltaResult.NotComparable).reason,
        )
    }

    /**
     * One decrease condemns the pair, including the counters that still look fine.
     *
     * The case the reviewer named, and the reason the earlier per-counter handling was not
     * conservative enough:
     *
     * ```
     * baseline   A = 100   B = 5
     * latest     A =  50   B = 10
     * ```
     *
     * A is visibly broken. B's +5 is not a session delta -- it is 10 accumulated since the
     * accounting restarted, measured against 5 from before it. The old behaviour dropped A and
     * happily reported B.
     */
    @Test
    fun `a decrease in one counter refuses the whole pair, including the positive ones`() {
        val state = state(
            baselineKwl = listOf(kwl("A", 100L, 10L), kwl("B", 5L, 1L)),
            latestKwl = listOf(kwl("A", 50L, 5L), kwl("B", 10L, 2L)),
        )

        val list = CounterDeltaEngine.kernelWakelockDeltas(state)
        assertEquals(
            CounterDeltaReason.COUNTER_DECREASED,
            (list as CounterDeltaResult.NotComparable).reason,
        )
        assertNull("no delta list may be exposed at all", list.valueOrNull)

        // And B must not be obtainable through the single-counter route either.
        val b = CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "B")
        assertTrue("B looked like a clean +5 and is not", !b.succeeded)
        assertEquals(
            CounterDeltaReason.COUNTER_DECREASED,
            (b as CounterDeltaResult.NotComparable).reason,
        )
    }

    /** The same, for application wakelocks. */
    @Test
    fun `a decrease in one partial wakelock refuses the whole pair`() {
        val state = state(
            baselinePwl = listOf(pwl(1000, "A", 100L, 10L), pwl(1000, "B", 5L, 1L)),
            latestPwl = listOf(pwl(1000, "A", 50L, 5L), pwl(1000, "B", 10L, 2L)),
        )

        val list = CounterDeltaEngine.partialWakelockDeltas(state)
        assertEquals(
            CounterDeltaReason.COUNTER_DECREASED,
            (list as CounterDeltaResult.NotComparable).reason,
        )
        assertTrue(!CounterDeltaEngine.partialWakelockDelta(state, WINDOW, 1000, "B").succeeded)
    }

    /**
     * A decrease in one family refuses the other family too.
     *
     * Both come from one batterystats accounting capture. A kernel wakelock going backwards
     * says that capture's accounting restarted; it says nothing reassuring about the
     * application wakelocks sitting beside it in the same payload.
     */
    @Test
    fun `a kernel decrease refuses the app wakelock deltas as well`() {
        val state = state(
            baselineKwl = listOf(kwl("kernel", 100L, 10L)),
            latestKwl = listOf(kwl("kernel", 50L, 5L)),
            baselinePwl = listOf(pwl(1000, "app", 5L, 1L)),
            latestPwl = listOf(pwl(1000, "app", 10L, 2L)),
        )

        assertEquals(
            CounterDeltaReason.COUNTER_DECREASED,
            (CounterDeltaEngine.partialWakelockDeltas(state) as CounterDeltaResult.NotComparable).reason,
        )
        assertEquals(
            CounterDeltaReason.COUNTER_DECREASED,
            (CounterDeltaEngine.kernelWakelockDeltas(state) as CounterDeltaResult.NotComparable).reason,
        )
    }

    /** And the reverse direction. */
    @Test
    fun `an app wakelock decrease refuses the kernel deltas as well`() {
        val state = state(
            baselineKwl = listOf(kwl("kernel", 5L, 1L)),
            latestKwl = listOf(kwl("kernel", 10L, 2L)),
            baselinePwl = listOf(pwl(1000, "app", 100L, 10L)),
            latestPwl = listOf(pwl(1000, "app", 50L, 5L)),
        )

        assertEquals(
            CounterDeltaReason.COUNTER_DECREASED,
            (CounterDeltaEngine.kernelWakelockDeltas(state) as CounterDeltaResult.NotComparable).reason,
        )
    }

    /** The refusal names the counter that broke, so a bug report can start somewhere. */
    @Test
    fun `the refusal identifies which counter lost continuity`() {
        val state = state(
            baselineKwl = listOf(kwl("bt_read_wake_lock", 100L, 10L)),
            latestKwl = listOf(kwl("bt_read_wake_lock", 50L, 5L)),
        )

        val detail = (CounterDeltaEngine.kernelWakelockDeltas(state)
            as CounterDeltaResult.NotComparable).detail
        assertTrue("names the counter: $detail", detail.contains("bt_read_wake_lock"))
        assertTrue("and says the others are untrustworthy too", detail.contains("none of them"))
    }

    /**
     * A decrease does not become a generation change or a new baseline.
     *
     * The evidence proves discontinuity, not its cause. Reporting it as
     * DIFFERENT_COUNTER_GENERATION would be claiming to know Android reset its counters, which
     * a decrease alone does not establish.
     */
    @Test
    fun `a decrease is reported as a decrease, not as a generation change`() {
        val state = state(
            baselineKwl = listOf(kwl("k", 100L, 10L)),
            latestKwl = listOf(kwl("k", 50L, 5L)),
        )

        val reason = (CounterDeltaEngine.kernelWakelockDeltas(state)
            as CounterDeltaResult.NotComparable).reason
        assertEquals(CounterDeltaReason.COUNTER_DECREASED, reason)
        assertNotEquals(CounterDeltaReason.DIFFERENT_COUNTER_GENERATION, reason)
        // The generations themselves are untouched and still equal.
        assertEquals(state.baseline.counterGeneration, state.latest.counterGeneration)
    }

    /**
     * A merely *unmatched* counter still leaves the others usable.
     *
     * The stricter pair-level rule is specifically about a matched counter going backwards.
     * Over-applying it would refuse every capture in which a device started or stopped
     * reporting a wakelock, which is ordinary.
     */
    @Test
    fun `an unmatched counter does not trigger the pair-level refusal`() {
        val state = state(
            baselineKwl = listOf(kwl("stable", 100L, 1L), kwl("vanished", 50L, 1L)),
            latestKwl = listOf(kwl("stable", 400L, 4L), kwl("newcomer", 900L, 9L)),
        )

        val all = (CounterDeltaEngine.kernelWakelockDeltas(state) as CounterDeltaResult.Success).value
        assertEquals(1, all.size)
        assertEquals("stable", all.single().name)
        assertNull(CounterDeltaEngine.continuityBreak(state.baseline, state.latest))
    }

    // ------------------------------------------------------------------- invariants

    /**
     * No delta this engine produces is ever negative.
     *
     * Checked over a deterministic sweep rather than a single case, because a negative
     * duration downstream is indistinguishable from a counter reset and would send a future
     * reset detector chasing a bug in this file.
     */
    @Test
    fun `no produced delta is ever negative, and decreases refuse instead`() {
        var successes = 0
        var refusals = 0
        for (b in longArrayOf(0, 1, 500, 10_000, Long.MAX_VALUE / 4)) {
            for (l in longArrayOf(0, 1, 500, 10_000, Long.MAX_VALUE / 4)) {
                val state = state(
                    baselineKwl = listOf(kwl("k", b, b)),
                    latestKwl = listOf(kwl("k", l, l)),
                )
                val single = CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "k")
                val list = CounterDeltaEngine.kernelWakelockDeltas(state)

                if (l < b) {
                    // A decrease refuses, and refuses both entry points identically.
                    refusals++
                    assertEquals(
                        "$b -> $l must refuse",
                        CounterDeltaReason.COUNTER_DECREASED,
                        (single as CounterDeltaResult.NotComparable).reason,
                    )
                    assertTrue("$b -> $l must not produce a list", !list.succeeded)
                } else {
                    successes++
                    val value = (single as CounterDeltaResult.Success).value
                    assertTrue(
                        "$b -> $l produced ${value.durationDeltaMillis}",
                        value.durationDeltaMillis >= 0L && value.countDelta >= 0L,
                    )
                    (list as CounterDeltaResult.Success).value.forEach {
                        assertTrue(it.durationDeltaMillis >= 0L && it.countDelta >= 0L)
                    }
                }
            }
        }
        // The sweep must actually exercise both sides, or it proves only one of them.
        assertTrue("the sweep covered $successes increases", successes > 0)
        assertTrue("and $refusals decreases", refusals > 0)
    }

    /** An incomparable pair never yields a success, whichever entry point is used. */
    @Test
    fun `incomparable captures never return success from any entry point`() {
        val state = SessionCounterState(
            batterySessionId = SESSION,
            baseline = baseline(boot = BootIdentity.Kernel("a")),
            latest = latest(boot = BootIdentity.Kernel("b")),
        )

        assertTrue(!CounterDeltaEngine.kernelWakelockDeltas(state).succeeded)
        assertTrue(!CounterDeltaEngine.partialWakelockDeltas(state).succeeded)
        assertTrue(!CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "any").succeeded)
        assertTrue(!CounterDeltaEngine.partialWakelockDelta(state, WINDOW, 0, "any").succeeded)
    }

    /**
     * The first capture of a session compares with itself and yields zeros.
     *
     * Baseline and latest are the same row until a second capture arrives, and the honest
     * answer then is "nothing has accumulated yet" rather than a refusal.
     */
    @Test
    fun `a session with only a baseline reports zero, not a refusal`() {
        val only = baseline(kwl = listOf(kwl("k", 1_000L, 10L)))
        val state = SessionCounterState(SESSION, only, only)

        assertTrue(state.baselineIsLatest)
        val delta = (CounterDeltaEngine.kernelWakelockDelta(state, WINDOW, "k")
            as CounterDeltaResult.Success).value
        assertEquals(0L, delta.durationDeltaMillis)
        assertEquals(0L, delta.countDelta)
    }

    /** Phase 5's refusals map through without being restated. */
    @Test
    fun `every phase 5 comparability reason has a counter equivalent`() {
        com.rmpsdroid.battinsight.session.Comparability.Reason.entries.forEach {
            // Throws on an unmapped value, which is the point: adding a reason upstream must
            // not silently fall through to UNKNOWN here without someone deciding it should.
            CounterDeltaReason.from(it)
        }
    }

    // ------------------------------------------------------------------------ helpers

    private fun versionBlock(checkin: Int = 36, recordFormat: Int = 9) = CheckinVersionBlock(
        recordFormatVersion = recordFormat,
        checkinVersion = checkin,
        parcelVersion = 215L,
        startPlatformVersion = "BUILD.A",
        endPlatformVersion = "BUILD.A",
    )

    private fun kwl(
        name: String,
        millis: Long,
        count: Long,
        window: AggregationWindow = WINDOW,
    ) = KernelWakelockStat(name, millis, count, window)

    private fun pwl(
        uid: Int,
        name: String,
        millis: Long,
        count: Long,
        window: AggregationWindow = WINDOW,
    ) = PartialWakelockStat(uid, name, millis, count, window)

    private fun baseline(
        boot: BootIdentity = BOOT,
        generation: CounterGeneration = CounterGeneration(1),
        elapsed: Long = 1_000L,
        format: SourceFormat = SourceFormat.CHECKIN,
        version: CheckinVersionBlock = versionBlock(),
        platformChanged: Boolean = false,
        kwl: List<KernelWakelockStat> = emptyList(),
        pwl: List<PartialWakelockStat> = emptyList(),
    ) = capture("baseline-id", boot, generation, elapsed, format, version, platformChanged, kwl, pwl)

    private fun latest(
        boot: BootIdentity = BOOT,
        generation: CounterGeneration = CounterGeneration(1),
        elapsed: Long = 1_000L + ELAPSED,
        format: SourceFormat = SourceFormat.CHECKIN,
        version: CheckinVersionBlock = versionBlock(),
        platformChanged: Boolean = false,
        kwl: List<KernelWakelockStat> = emptyList(),
        pwl: List<PartialWakelockStat> = emptyList(),
    ) = capture("latest-id", boot, generation, elapsed, format, version, platformChanged, kwl, pwl)

    private fun capture(
        id: String,
        boot: BootIdentity,
        generation: CounterGeneration,
        elapsed: Long,
        format: SourceFormat,
        version: CheckinVersionBlock,
        platformChanged: Boolean,
        kwl: List<KernelWakelockStat>,
        pwl: List<PartialWakelockStat>,
    ) = StoredCounterCapture(
        captureId = id,
        batterySessionId = SESSION,
        batterySnapshotId = null,
        sourceFormat = format,
        backendKind = BackendIdentity.Kind.SHELL,
        version = version,
        platformChanged = platformChanged,
        checkinVersionVerified = true,
        captureElapsedRealtimeMillis = elapsed,
        captureWallClockMillis = 1_700_000_000_000L + elapsed,
        counterGeneration = generation,
        bootIdentity = boot,
        payloadByteCount = 1000,
        warningCount = 0,
        kernelWakelocks = kwl,
        partialWakelocks = pwl,
    )

    private fun state(
        baselineKwl: List<KernelWakelockStat> = emptyList(),
        latestKwl: List<KernelWakelockStat> = emptyList(),
        baselinePwl: List<PartialWakelockStat> = emptyList(),
        latestPwl: List<PartialWakelockStat> = emptyList(),
    ) = SessionCounterState(
        batterySessionId = SESSION,
        baseline = baseline(kwl = baselineKwl, pwl = baselinePwl),
        latest = latest(kwl = latestKwl, pwl = latestPwl),
    )

    private companion object {
        const val SESSION = "session-1"
        const val ELAPSED = 60_000L
        val WINDOW = AggregationWindow.SINCE_CHARGED
        val BOOT = BootIdentity.Kernel("boot-under-test")
    }
}

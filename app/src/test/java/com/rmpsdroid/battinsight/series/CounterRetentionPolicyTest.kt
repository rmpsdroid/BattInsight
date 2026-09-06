package com.rmpsdroid.battinsight.series

import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.batterystats.StoredCounterCapture
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-comparison eviction rule, exercised directly.
 *
 * Tested here rather than only through the store because the store legitimately refuses some
 * of these inputs before they can ever be written -- an unverified checkin version and a
 * platform change are rejected at persist time -- and a rule that is only reachable through a
 * gate cannot have its own edges tested.
 *
 * Every case is a sequence where the naive rule gets it wrong. The rule shipped in Phase 9A.1
 * evaluated only `(prev, next)`; each row below is a way for a refusal to hide from it.
 */
class CounterRetentionPolicyTest {

    // ----------------------------------------------------------------- eligible at all

    @Test
    fun `a clean rising run allows its middle to be evicted`() {
        assertTrue(evictable(cap("a", 0, 100), cap("b", 1, 110), cap("c", 2, 120)))
    }

    // -------------------------------------------------- refusals that hide from prev-next

    @Test
    fun `the named case - 100 then 50 then 120`() {
        // A refused (decrease), B fine, C fine. The C-only rule evicts here and manufactures
        // a clean +20 across a counter reset.
        assertFalse(evictable(cap("a", 0, 100), cap("b", 1, 50), cap("c", 2, 120)))
    }

    @Test
    fun `a later decrease - 100 then 110 then 90`() {
        assertFalse(evictable(cap("a", 0, 100), cap("b", 1, 110), cap("c", 2, 90)))
    }

    @Test
    fun `a boot round trip - b1 then b2 then b1`() {
        // A and B refused for DIFFERENT_BOOT; C compares b1 to b1 and sees nothing wrong.
        assertFalse(
            evictable(
                cap("a", 0, 100, boot = BootIdentity.Kernel("b1")),
                cap("b", 1, 110, boot = BootIdentity.Kernel("b2")),
                cap("c", 2, 120, boot = BootIdentity.Kernel("b1")),
            ),
        )
    }

    @Test
    fun `a generation round trip - 3 then 4 then 3`() {
        assertFalse(
            evictable(
                cap("a", 0, 100, generation = 3),
                cap("b", 1, 110, generation = 4),
                cap("c", 2, 120, generation = 3),
            ),
        )
    }

    @Test
    fun `a checkin version round trip - 36 then 37 then 36`() {
        assertFalse(
            evictable(
                cap("a", 0, 100, checkin = 36),
                cap("b", 1, 110, checkin = 37),
                cap("c", 2, 120, checkin = 36),
            ),
        )
    }

    @Test
    fun `a capture flagged as spanning a platform change protects itself`() {
        // Worth being precise about what the engine actually tests. PLATFORM_CHANGED is driven
        // by the per-capture `platformChanged` flag -- "this capture's own accounting window
        // spans an OS update" -- not by comparing two captures' fingerprints to each other. So
        // the refusal attaches to the flagged capture, refusing both intervals that touch it.
        //
        // (In production such a capture never reaches storage at all: the store rejects it at
        // persist time. This asserts the policy is correct regardless, for data written by an
        // older build.)
        assertFalse(
            evictable(
                cap("a", 0, 100),
                cap("b", 1, 110, platformChanged = true),
                cap("c", 2, 120),
            ),
        )
    }

    @Test
    fun `an elapsed round trip - 0 then 5 then 2`() {
        // B is TIME_REVERSED. C reads 0 -> 2 and looks perfectly ordered.
        assertFalse(
            evictable(
                cap("a", 0, 100, elapsed = 0),
                cap("b", 1, 110, elapsed = 5_000),
                cap("c", 2, 120, elapsed = 2_000),
            ),
        )
    }

    @Test
    fun `derived boot identities throughout`() {
        val derived = BootIdentity.Derived(1_700_000_000_000L)
        assertFalse(
            evictable(
                cap("a", 0, 100, boot = derived),
                cap("b", 1, 110, boot = derived),
                cap("c", 2, 120, boot = derived),
            ),
        )
    }

    // ------------------------------------------------- the case only the third comparison sees

    @Test
    fun `A and B comparable but C refused`() {
        // Constructed through a counter that disappears and returns. "y" is present in prev
        // and next but absent from the candidate:
        //
        //   prev -> candidate   y unmatched, skipped        -> comparable
        //   candidate -> next   y unmatched, skipped        -> comparable
        //   prev -> next        y matched, 100 -> 50        -> COUNTER_DECREASED
        //
        // Removing the candidate would join two captures that must never be subtracted. This
        // is the case "preserve known discontinuities" alone would miss, and the reason the
        // third comparison is not redundant.
        val prev = capWith("a", 0, mapOf("x" to 10L, "y" to 100L))
        val candidate = capWith("b", 1, mapOf("x" to 20L))
        val next = capWith("c", 2, mapOf("x" to 30L, "y" to 50L))

        assertTrue("A is comparable", comparable(prev, candidate))
        assertTrue("B is comparable", comparable(candidate, next))
        assertFalse("C is not", comparable(prev, next))
        assertFalse("so the candidate must stay", CounterRetentionPolicy.isEvictable(prev, candidate, next))
    }

    // ------------------------------------------------------------------------- the plan

    @Test
    fun `a clean series is trimmed to the target`() {
        val series = (0 until 12).map { cap("c$it", it, 100L + it * 10L) }

        val plan = CounterRetentionPolicy.evictionPlan(series, baselineCaptureId = "c0")

        assertEquals("enough to reach the target with one incoming", 5, plan.size)
        assertTrue("the baseline is never a candidate", "c0" !in plan)
        assertTrue("nor is the last capture", "c11" !in plan)
    }

    @Test
    fun `a series full of discontinuities is not trimmed at all`() {
        // Every capture alternates down and up, so no candidate can pass all three tests.
        var value = 1_000L
        val series = (0 until 12).map { i ->
            value = if (i % 2 == 0) value - 500L else value + 900L
            cap("c$i", i, value)
        }

        val plan = CounterRetentionPolicy.evictionPlan(series, baselineCaptureId = "c0")

        assertTrue("truth wins over the target", plan.isEmpty())
    }

    @Test
    fun `the plan never proposes the baseline even when it looks evictable`() {
        val series = (0 until 12).map { cap("c$it", it, 100L + it * 10L) }

        // Naming a middle capture as the baseline makes it structurally untouchable.
        val plan = CounterRetentionPolicy.evictionPlan(series, baselineCaptureId = "c3")

        assertTrue("c3" !in plan)
    }

    @Test
    fun `a session already at the target proposes nothing`() {
        val series = (0 until 7).map { cap("c$it", it, 100L + it * 10L) }

        assertTrue(CounterRetentionPolicy.evictionPlan(series, "c0").isEmpty())
    }

    @Test
    fun `the shipped target is eight`() {
        assertEquals(8, CounterRetentionPolicy.TARGET_COUNTER_CAPTURES_PER_SESSION)
    }

    // --------------------------------------------------------------------------- helpers

    private fun evictable(a: StoredCounterCapture, b: StoredCounterCapture, c: StoredCounterCapture) =
        CounterRetentionPolicy.isEvictable(a, b, c)

    private fun comparable(a: StoredCounterCapture, b: StoredCounterCapture) =
        com.rmpsdroid.battinsight.batterystats.CounterDeltaEngine.comparability(a, b) == null

    private fun cap(
        id: String,
        index: Int,
        millis: Long,
        elapsed: Long = index * 1_000L,
        boot: BootIdentity = BootIdentity.Kernel("boot-a"),
        generation: Long = 3,
        checkin: Int = 36,
        platform: String = "BUILD.A",
        platformChanged: Boolean = false,
    ) = capWith(
        id, index, mapOf("k" to millis), elapsed, boot, generation, checkin, platform,
        platformChanged,
    )

    private fun capWith(
        id: String,
        index: Int,
        counters: Map<String, Long>,
        elapsed: Long = index * 1_000L,
        boot: BootIdentity = BootIdentity.Kernel("boot-a"),
        generation: Long = 3,
        checkin: Int = 36,
        platform: String = "BUILD.A",
        platformChanged: Boolean = false,
    ) = StoredCounterCapture(
        captureId = id,
        batterySessionId = SESSION,
        batterySnapshotId = null,
        sourceFormat = SourceFormat.CHECKIN,
        backendKind = BackendIdentity.Kind.SHELL,
        version = CheckinVersionBlock(9, checkin, 215L, platform, platform),
        platformChanged = platformChanged,
        checkinVersionVerified = true,
        captureElapsedRealtimeMillis = elapsed,
        captureWallClockMillis = 1_700_000_000_000L + elapsed,
        counterGeneration = CounterGeneration(generation),
        bootIdentity = boot,
        payloadByteCount = 900_000,
        warningCount = 0,
        kernelWakelocks = counters.map {
            KernelWakelockStat(it.key, it.value, 1L, AggregationWindow.SINCE_CHARGED)
        },
        partialWakelocks = emptyList(),
    )

    private companion object {
        const val SESSION = "00000000-0000-0000-0000-0000000000aa"
    }
}

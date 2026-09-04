package com.rmpsdroid.battinsight.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When two snapshots may be compared, and what the refusal says when they may not.
 *
 * Every refusal here keeps the underlying records. The predecessor's answer to a confusing
 * comparison was to delete the data behind it, which is the single most damaging behaviour
 * in its issue tracker.
 */
class SnapshotComparabilityTest {

    // ------------------------------------------------------------------------- duration

    @Test
    fun `two snapshots from the same boot, in order, are comparable`() {
        val a = snapshot(0)
        val b = snapshot(10 * MINUTE)
        assertTrue(SnapshotComparability.forDuration(a, b).isComparable)
    }

    @Test
    fun `snapshots from different boots are never comparable`() {
        val a = snapshot(HOUR, boot = kernelBoot("boot-a"))
        val b = snapshot(30_000, boot = kernelBoot("boot-b"))

        val c = SnapshotComparability.forDuration(a, b) as Comparability.NotComparable
        assertEquals(Comparability.Reason.DIFFERENT_BOOT, c.reason)
        assertTrue("the refusal must be legible: ${c.detail}", c.detail.contains("restarted"))
    }

    @Test
    fun `a later snapshot with an earlier monotonic time is refused, not negated`() {
        val a = snapshot(10 * MINUTE)
        val b = snapshot(5 * MINUTE)

        val c = SnapshotComparability.forDuration(a, b) as Comparability.NotComparable
        assertEquals(Comparability.Reason.TIME_REVERSED, c.reason)
    }

    @Test
    fun `an unknown boot identity refuses comparison rather than assuming sameness`() {
        val a = snapshot(0, boot = BootIdentity.Unknown)
        val b = snapshot(MINUTE, boot = BootIdentity.Unknown)

        val c = SnapshotComparability.forDuration(a, b) as Comparability.NotComparable
        assertEquals(Comparability.Reason.MISSING_IDENTITY, c.reason)
    }

    @Test
    fun `a derived boot identity cannot establish sameness even when values match`() {
        // Two separate boots can easily produce the same approximate boot time, so equality
        // here is not proof and must not be treated as any.
        val a = snapshot(0, boot = BootIdentity.Derived(EPOCH))
        val b = snapshot(MINUTE, boot = BootIdentity.Derived(EPOCH))

        val c = SnapshotComparability.forDuration(a, b) as Comparability.NotComparable
        assertEquals(Comparability.Reason.MISSING_IDENTITY, c.reason)
        assertTrue(
            "the reason should name the missing kernel identifier: ${c.detail}",
            c.detail.contains("kernel boot identifier"),
        )
    }

    @Test
    fun `a kernel identity and a derived identity are never comparable to each other`() {
        val a = snapshot(0, boot = kernelBoot())
        val b = snapshot(MINUTE, boot = BootIdentity.Derived(EPOCH))
        assertTrue(SnapshotComparability.forDuration(a, b) !is Comparability.Comparable)
    }

    @Test
    fun `wall clock disagreement never affects duration comparability`() {
        val a = snapshot(0, wallClockMillis = EPOCH)
        val b = snapshot(10 * MINUTE, wallClockMillis = EPOCH - 5 * HOUR)
        assertTrue(SnapshotComparability.forDuration(a, b).isComparable)
    }

    // -------------------------------------------------------------------------- counters

    @Test
    fun `counters from the same generation and source are comparable`() {
        val a = snapshot(0, source = CounterSource.PROTO)
        val b = snapshot(HOUR, source = CounterSource.PROTO)
        assertTrue(SnapshotComparability.forCounters(a, b).isComparable)
    }

    @Test
    fun `counters from different generations are never comparable`() {
        val a = snapshot(0, generation = CounterGeneration(1), source = CounterSource.PROTO)
        val b = snapshot(HOUR, generation = CounterGeneration(2), source = CounterSource.PROTO)

        val c = SnapshotComparability.forCounters(a, b) as Comparability.NotComparable
        assertEquals(Comparability.Reason.DIFFERENT_COUNTER_GENERATION, c.reason)
    }

    @Test
    fun `counters from an incompatible schema are refused`() {
        val a = snapshot(0, schema = SnapshotSchemaVersion(1), source = CounterSource.PROTO)
        val b = snapshot(HOUR, schema = SnapshotSchemaVersion(2), source = CounterSource.PROTO)

        val c = SnapshotComparability.forCounters(a, b) as Comparability.NotComparable
        assertEquals(Comparability.Reason.SCHEMA_INCOMPATIBLE, c.reason)
    }

    @Test
    fun `counters from different acquisition formats are refused`() {
        val a = snapshot(0, source = CounterSource.PROTO)
        val b = snapshot(HOUR, source = CounterSource.CHECKIN)

        val c = SnapshotComparability.forCounters(a, b) as Comparability.NotComparable
        assertEquals(Comparability.Reason.SOURCE_INCOMPATIBLE, c.reason)
    }

    @Test
    fun `snapshots carrying no counters are not counter-comparable`() {
        // The Phase 5 case: battery state was captured and no batterystats payload was.
        // Claiming comparability would let a future collector subtract nothing from nothing.
        val a = snapshot(0)
        val b = snapshot(HOUR)

        assertTrue(SnapshotComparability.forDuration(a, b).isComparable)
        val c = SnapshotComparability.forCounters(a, b) as Comparability.NotComparable
        assertEquals(Comparability.Reason.SOURCE_INCOMPATIBLE, c.reason)
        assertTrue(c.detail.contains("only battery state"))
    }

    @Test
    fun `everything that fails a duration comparison also fails a counter comparison`() {
        val pairs = listOf(
            snapshot(HOUR, boot = kernelBoot("a")) to snapshot(0, boot = kernelBoot("b")),
            snapshot(10 * MINUTE) to snapshot(MINUTE),
            snapshot(0, boot = BootIdentity.Unknown) to snapshot(MINUTE, boot = BootIdentity.Unknown),
        )
        pairs.forEach { (a, b) ->
            assertTrue(SnapshotComparability.forDuration(a, b) !is Comparability.Comparable)
            assertTrue(
                "counter comparability must be at least as strict",
                SnapshotComparability.forCounters(a, b) !is Comparability.Comparable,
            )
        }
    }

    // ---------------------------------------------------------------------- delta results

    @Test
    fun `a valid duration delta carries the value and the duration it accrued over`() {
        val result = durationBetween(snapshot(0), snapshot(25 * MINUTE))
        val success = result as DeltaResult.Success
        assertEquals(25 * MINUTE, success.value)
        assertEquals(25 * MINUTE, success.durationMillis)
        assertNull("a success has nothing to explain", result.refusalDetail)
    }

    @Test
    fun `a refused delta explains itself instead of returning zero`() {
        val result = durationBetween(
            snapshot(HOUR, boot = kernelBoot("a")),
            snapshot(0, boot = kernelBoot("b")),
        )
        val refused = result as DeltaResult.NotComparable
        assertEquals(Comparability.Reason.DIFFERENT_BOOT, refused.comparability.reason)
        assertNull(result.valueOrNull)
        assertTrue(result.refusalDetail!!.isNotBlank())
    }

    @Test
    fun `missing data is distinguishable from a refused comparison`() {
        // Two different causes of "no number", which the predecessor rendered identically
        // as a blank cell.
        val refused: DeltaResult<Long> = DeltaResult.NotComparable(
            Comparability.NotComparable(Comparability.Reason.DIFFERENT_BOOT, "restarted"),
        )
        val missing: DeltaResult<Long> = DeltaResult.MissingData("no wakelock records captured")

        assertTrue(refused is DeltaResult.NotComparable)
        assertTrue(missing is DeltaResult.MissingData)
        assertEquals("restarted", refused.refusalDetail)
        assertEquals("no wakelock records captured", missing.refusalDetail)
    }

    // ------------------------------------------------------------------- boot relations

    @Test
    fun `boot relations answer three ways, never two`() {
        assertEquals(BootRelation.SAME, kernelBoot("x").relationTo(kernelBoot("x")))
        assertEquals(BootRelation.DIFFERENT, kernelBoot("x").relationTo(kernelBoot("y")))
        assertEquals(BootRelation.UNKNOWN, BootIdentity.Unknown.relationTo(kernelBoot("x")))
        assertEquals(BootRelation.UNKNOWN, kernelBoot("x").relationTo(BootIdentity.Unknown))
    }

    @Test
    fun `only a kernel identity claims it can establish a boot relation`() {
        assertTrue(kernelBoot().canProveBootRelation)
        assertTrue(!BootIdentity.Derived(EPOCH).canProveBootRelation)
        assertTrue(!BootIdentity.Unknown.canProveBootRelation)
    }

    /**
     * Replaces a test that asserted the defect.
     *
     * It previously required a derived estimate differing by more than a tolerance to
     * report [BootRelation.DIFFERENT], which encoded exactly the unsound rule: the estimate
     * is built from a wall clock that can jump by hours on one uninterrupted boot, so a
     * large difference is not evidence of a reboot. The estimate now decides nothing, in
     * either direction, at any distance.
     */
    @Test
    fun `a derived identity is inconclusive at every distance`() {
        val base = BootIdentity.Derived(EPOCH)
        listOf(0L, 1L, MINUTE, HOUR, 6 * HOUR, 24 * HOUR, 365 * 24 * HOUR).forEach { delta ->
            val other = BootIdentity.Derived(EPOCH + delta)
            assertEquals(
                "a difference of ${delta}ms must remain unproven",
                BootRelation.UNKNOWN,
                base.relationTo(other),
            )
            // Symmetric: direction of travel does not change the answer.
            assertEquals(BootRelation.UNKNOWN, other.relationTo(base))
        }
    }

    @Test
    fun `abbreviated identities never expose a full identifier`() {
        val full = "6f3d1c2e-1234-4a5b-8c9d-000000000000"
        assertEquals(8, BootIdentity.Kernel(full).abbreviated.length)
        assertTrue(!BootIdentity.Kernel(full).abbreviated.contains("000000000000"))
    }
}

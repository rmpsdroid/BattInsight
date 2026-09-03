package com.rmpsdroid.batterydiagnostics.capability

import com.rmpsdroid.batterydiagnostics.collection.CollectionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the layer that is allowed to make semantic judgements.
 *
 * The point of the split: `AvailableNoEvents` remains a real and necessary state, but it is
 * reached here, from evidence about what was inside a successful collection, rather than
 * being invented by the process layer from a bare empty result.
 */
class CapabilityInterpreterTest {

    // ---- H. a known no-event case still reaches AvailableNoEvents ----

    /**
     * The measured Android 16 kernel-wakelock case: 68 named records, every counter zero,
     * because that environment never suspends. The source is healthy and has nothing to
     * report, which is a different thing from being absent.
     */
    @Test
    fun `records present with no values becomes AvailableNoEvents`() {
        val state = CapabilityInterpreter.interpret(
            outcome = CollectionOutcome.Data(bytes = 818_491),
            reading = SourceReading.Records(total = 68, withValues = 0),
        )

        assertTrue("expected AvailableNoEvents but was $state", state is CapabilityState.AvailableNoEvents)
        assertTrue((state as CapabilityState.AvailableNoEvents).detail.contains("68"))
    }

    @Test
    fun `records with values becomes Available`() {
        // The Android 10 device: 111 kernel wakelocks, 43 carrying real time and counts.
        val state = CapabilityInterpreter.interpret(
            outcome = CollectionOutcome.Data(bytes = 228_695),
            reading = SourceReading.Records(total = 111, withValues = 43),
        )

        assertEquals(CapabilityState.Available, state)
    }

    @Test
    fun `partial records become AvailableDegraded`() {
        // The measured package-visibility case: an app UID resolved 152 of 180 UID name
        // mappings. Acquisition succeeded; naming did not.
        val state = CapabilityInterpreter.interpret(
            outcome = CollectionOutcome.Data(bytes = 817_983),
            reading = SourceReading.Records(
                total = 152,
                withValues = 152,
                completeness = SourceReading.Completeness.PARTIAL,
            ),
        )

        assertTrue("expected AvailableDegraded but was $state", state is CapabilityState.AvailableDegraded)
    }

    // ---- the generic-empty rule ----

    @Test
    fun `empty output that nobody inspected is Unknown, never AvailableNoEvents`() {
        val state = CapabilityInterpreter.interpret(
            outcome = CollectionOutcome.Empty,
            reading = SourceReading.NotInspected,
        )

        assertEquals(CapabilityState.Unknown, state)
    }

    @Test
    fun `data that nobody inspected is Available but not a no-events claim`() {
        val state = CapabilityInterpreter.interpret(
            outcome = CollectionOutcome.Data(bytes = 1000),
            reading = SourceReading.NotInspected,
        )

        assertEquals(CapabilityState.Available, state)
    }

    @Test
    fun `a section absent from otherwise valid data is SourceUnavailable`() {
        val state = CapabilityInterpreter.interpret(
            outcome = CollectionOutcome.Data(bytes = 818_491),
            reading = SourceReading.SectionAbsent,
        )

        assertTrue(state is CapabilityState.SourceUnavailable)
    }

    // ---- outcome mapping ----

    @Test
    fun `permission denial carries the grantable permission through to the capability state`() {
        val state = CapabilityInterpreter.interpret(
            CollectionOutcome.PermissionDenied(
                permission = "android.permission.INTERACT_ACROSS_USERS",
                alternatives = listOf("android.permission.INTERACT_ACROSS_USERS_FULL"),
                rawDetail = "Security exception: MATCH_ANY_USER flag requires ...",
            ),
        )

        assertEquals(
            CapabilityState.PermissionMissing("android.permission.INTERACT_ACROSS_USERS"),
            state,
        )
    }

    @Test
    fun `execution failure retains the exit code in its detail`() {
        val state = CapabilityInterpreter.interpret(
            CollectionOutcome.ExecutionFailed(exitCode = 2, detail = "Permission denied"),
        )

        assertTrue(state is CapabilityState.ExecutionFailed)
        assertTrue((state as CapabilityState.ExecutionFailed).detail.contains("exit 2"))
    }

    @Test
    fun `unrecognised output is Unknown, not Available`() {
        val state = CapabilityInterpreter.interpret(
            CollectionOutcome.Unrecognised("something entirely unexpected"),
        )

        assertEquals(CapabilityState.Unknown, state)
    }

    @Test
    fun `source error becomes SourceUnavailable`() {
        val state = CapabilityInterpreter.interpret(
            CollectionOutcome.SourceError("Unknown option: --nonsense"),
        )

        assertTrue(state is CapabilityState.SourceUnavailable)
    }
}

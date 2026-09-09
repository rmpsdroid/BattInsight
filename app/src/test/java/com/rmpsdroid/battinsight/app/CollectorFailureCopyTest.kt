package com.rmpsdroid.battinsight.app

import com.rmpsdroid.battinsight.batterystats.DecodeOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user is told when a capture fails, asserted as text they would read.
 *
 * ## Why this exists
 *
 * Phase 10A.3 fixed a false statement — a refused Binder transaction reported as "Android
 * returned nothing at all", about a platform that had produced 1,066,676 bytes. The first
 * replacement wording introduced a different unsupported claim in the opposite direction:
 * *"Your device is fine"*. An execution failure establishes that BattInsight did not finish
 * reading. It establishes nothing whatever about the health of the device.
 *
 * Reassurance is the easy failure mode here, and it is still a claim. These tests pin both
 * halves: the old false statement must not come back, and the correction must not have
 * replaced it with a new one.
 */
class CollectorFailureCopyTest {

    private val executionFailed get() = describeFailure(DecodeOutcome.EXECUTION_FAILED)

    @Test
    fun `an execution failure does not claim Android returned nothing`() {
        assertFalse(
            "a transport failure is not the platform returning nothing: " + executionFailed,
            executionFailed.contains("Android returned nothing", ignoreCase = true),
        )
        assertFalse(
            "and must not blame Android at all",
            executionFailed.contains("Android", ignoreCase = true),
        )
    }

    @Test
    fun `an execution failure does not claim the device is healthy`() {
        assertFalse(
            "the outcome does not establish that the device is fine: " + executionFailed,
            executionFailed.contains("device is fine", ignoreCase = true),
        )
        assertFalse(
            "nor any other reassurance about the hardware",
            executionFailed.contains("your device is", ignoreCase = true),
        )
    }

    @Test
    fun `an execution failure says the capture did not complete`() {
        assertTrue(
            "the user must be told what actually happened: " + executionFailed,
            executionFailed.contains("did not complete"),
        )
    }

    @Test
    fun `an execution failure keeps the retry and setup guidance`() {
        assertTrue("offer the retry", executionFailed.contains("Try again"))
        assertTrue("and the setup path", executionFailed.contains("Manage access"))
    }

    /**
     * The wording says what was measured, and stops there.
     *
     * BattInsight not finishing the read is the whole of what an execution failure proves.
     * Anything about a refusal, a permission or the state of the hardware would be inferred
     * rather than observed.
     */
    @Test
    fun `an execution failure attributes the failure only to BattInsight`() {
        assertTrue(
            "say who could not finish: " + executionFailed,
            executionFailed.contains("BattInsight could not"),
        )
        listOf("refused", "denied", "permission", "blocked").forEach {
            assertFalse(
                "must not assert " + it + ": " + executionFailed,
                executionFailed.contains(it, ignoreCase = true),
            )
        }
    }

    @Test
    fun `no failure wording leaks engineer-facing text`() {
        DecodeOutcome.entries.forEach { outcome ->
            val copy = describeFailure(outcome)
            listOf("Exception", "Binder", "parcel", "stdout", "stderr", "null", "0x").forEach {
                assertFalse(
                    outcome.name + " must not contain '" + it + "': " + copy,
                    copy.contains(it, ignoreCase = true),
                )
            }
            assertTrue(outcome.name + " must say something", copy.isNotBlank())
        }
    }

    /**
     * The neighbouring outcomes are unchanged, which is what makes the correction narrow.
     *
     * `EMPTY` in particular keeps its wording: a command that ran, exited cleanly and
     * produced nothing really is the platform returning nothing, and that sentence is only
     * false when an execution failure is routed into it — which is what Phase 10A.3 stopped.
     */
    @Test
    fun `the neighbouring outcomes are untouched`() {
        assertTrue(
            describeFailure(DecodeOutcome.EMPTY).contains("Android returned nothing at all"),
        )
        assertTrue(
            describeFailure(DecodeOutcome.PERMISSION_DENIAL_PAYLOAD).contains("Manage access"),
        )
        assertTrue(
            describeFailure(DecodeOutcome.TRUNCATED).contains("Nothing is missing from"),
        )
    }
}

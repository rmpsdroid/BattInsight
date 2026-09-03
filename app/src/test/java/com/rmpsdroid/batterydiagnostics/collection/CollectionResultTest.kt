package com.rmpsdroid.batterydiagnostics.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification tests built from strings the platform actually emitted during Phase 1B on
 * Android 16, not from invented examples.
 *
 * Two properties are under guard here:
 *  - exit status 0 must not be read as success, because every measured denial had it;
 *  - the collection layer must report mechanics only, and must never manufacture a
 *    capability judgement such as `AvailableNoEvents` from a generic empty result.
 */
class CollectionResultTest {

    private fun result(
        exitCode: Int? = 0,
        stdout: String = "",
        stderr: String = "",
        format: SourceFormat = SourceFormat.CHECKIN,
    ) = CollectionResult(
        backend = BackendIdentity.Kind.APP_UID,
        sourceFormat = format,
        exitCode = exitCode,
        stdoutBytes = stdout.toByteArray().size,
        stderrBytes = stderr.toByteArray().size,
        stdoutHead = stdout,
        stderrText = stderr,
        durationMillis = 10,
        timestampMillis = 0,
    )

    // ---- A. permission denial with exit 0 ----

    @Test
    fun `denial naming DUMP is PermissionDenied despite exit 0`() {
        val outcome = result(
            exitCode = 0,
            stdout = "Permission Denial: can't dump BatteryStatsService from from " +
                "pid=22446, uid=10241 due to missing android.permission.DUMP permission",
        ).outcome()

        assertTrue(outcome is CollectionOutcome.PermissionDenied)
        outcome as CollectionOutcome.PermissionDenied
        assertEquals("android.permission.DUMP", outcome.permission)
        assertTrue(outcome.alternatives.isEmpty())
        assertTrue(outcome.rawDetail.contains("Permission Denial"))
    }

    // ---- B. PACKAGE_USAGE_STATS denial with exit 0 ----

    @Test
    fun `denial naming PACKAGE_USAGE_STATS is distinguished from the DUMP denial`() {
        val outcome = result(
            exitCode = 0,
            stdout = "Permission Denial: can't dump BatteryStatsService from from " +
                "pid=22548, uid=10241 due to missing android.permission.PACKAGE_USAGE_STATS permission",
        ).outcome()

        assertEquals(
            "android.permission.PACKAGE_USAGE_STATS",
            (outcome as CollectionOutcome.PermissionDenied).permission,
        )
    }

    // ---- C. MATCH_ANY_USER: the actionable permission is the non-FULL form ----

    /**
     * Regression test for the Phase 2A defect.
     *
     * The message names both permissions. The earlier classifier matched the longer string
     * first and reported `INTERACT_ACROSS_USERS_FULL`, which onboarding cannot grant:
     * `_FULL` does not carry the `development` protection level, so `pm grant` cannot
     * deliver it. Phase 1B measured that granting the non-FULL form alone was sufficient.
     *
     * String reproduced verbatim from the Phase 1B fixture.
     */
    @Test
    fun `MATCH_ANY_USER denial reports the grantable permission, not the FULL variant`() {
        val outcome = result(
            exitCode = 0,
            stdout = "Security exception: MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS " +
                "permission: UID 10241 requires android.permission.INTERACT_ACROSS_USERS_FULL " +
                "or android.permission.INTERACT_ACROSS_USERS to access user 0.",
        ).outcome()

        assertTrue(outcome is CollectionOutcome.PermissionDenied)
        outcome as CollectionOutcome.PermissionDenied

        assertEquals("android.permission.INTERACT_ACROSS_USERS", outcome.permission)
        assertFalse(
            "must never ask the user to grant the FULL variant",
            outcome.permission == "android.permission.INTERACT_ACROSS_USERS_FULL",
        )

        // The platform's mention of _FULL is preserved, but only as an alternative.
        assertEquals(listOf("android.permission.INTERACT_ACROSS_USERS_FULL"), outcome.alternatives)

        // Raw platform text is retained verbatim for diagnostics.
        assertTrue(outcome.rawDetail.contains("MATCH_ANY_USER"))
        assertTrue(outcome.rawDetail.contains("INTERACT_ACROSS_USERS_FULL"))
    }

    // ---- D. non-zero exit + empty stdout ----

    @Test
    fun `non-zero exit with no output is ExecutionFailed`() {
        val outcome = result(exitCode = 1, stdout = "").outcome()

        assertTrue(outcome is CollectionOutcome.ExecutionFailed)
        assertEquals(1, (outcome as CollectionOutcome.ExecutionFailed).exitCode)
    }

    // ---- E. non-zero exit + stderr, detail retained ----

    @Test
    fun `non-zero exit retains stderr detail`() {
        val outcome = result(
            exitCode = 2,
            stdout = "",
            stderr = "ls: /sys/class/wakeup/: Permission denied",
        ).outcome()

        assertTrue(outcome is CollectionOutcome.ExecutionFailed)
        outcome as CollectionOutcome.ExecutionFailed
        assertEquals(2, outcome.exitCode)
        assertTrue("detail must be retained", outcome.detail.contains("Permission denied"))
    }

    // ---- F. exit 0 + empty stdout is Empty, NOT a capability judgement ----

    @Test
    fun `exit 0 with empty output is Empty and nothing more`() {
        val outcome = result(exitCode = 0, stdout = "").outcome()

        assertEquals(CollectionOutcome.Empty, outcome)
        // The collection layer must not conclude the source is healthy-but-idle. Only a
        // capability-specific reader can know that; see CapabilityInterpreterTest.
    }

    // ---- G. exit 0 + valid checkin data ----

    @Test
    fun `exit 0 with a checkin vers record is Data`() {
        val stdout = "9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1\n" +
            "9,hsp,0,0,\"wifi-off\"\n9,0,l,kwl,\"inotify\",0,0,-1,-1"
        val outcome = result(exitCode = 0, stdout = stdout).outcome()

        assertTrue(outcome is CollectionOutcome.Data)
        assertEquals(stdout.toByteArray().size, (outcome as CollectionOutcome.Data).bytes)
    }

    // ---- precedence and edge cases ----

    @Test
    fun `unrecognised output is not reported as success`() {
        val outcome = result(exitCode = 0, stdout = "something entirely unexpected").outcome()

        assertTrue(
            "expected Unrecognised but was $outcome",
            outcome is CollectionOutcome.Unrecognised,
        )
    }

    @Test
    fun `unknown option is a SourceError, not a denial`() {
        val outcome = result(exitCode = 0, stdout = "Unknown option: --nonsense").outcome()

        assertTrue(outcome is CollectionOutcome.SourceError)
    }

    @Test
    fun `null exit code means the process never completed`() {
        val outcome = result(exitCode = null, stdout = "partial").outcome()

        assertTrue(outcome is CollectionOutcome.ExecutionFailed)
        assertEquals(null, (outcome as CollectionOutcome.ExecutionFailed).exitCode)
    }

    @Test
    fun `denial text arriving on stderr is classified the same as on stdout`() {
        val outcome = result(
            exitCode = 0,
            stdout = "",
            stderr = "Permission Denial: missing android.permission.DUMP permission",
        ).outcome()

        assertEquals(
            "android.permission.DUMP",
            (outcome as CollectionOutcome.PermissionDenied).permission,
        )
    }

    @Test
    fun `a text error masquerading as proto output is not accepted as Data`() {
        // Proto is binary; a short text denial must not pass the format check.
        val outcome = result(
            exitCode = 0,
            stdout = "Permission Denial: missing android.permission.DUMP permission",
            format = SourceFormat.PROTO,
        ).outcome()

        assertTrue(outcome is CollectionOutcome.PermissionDenied)
    }
}

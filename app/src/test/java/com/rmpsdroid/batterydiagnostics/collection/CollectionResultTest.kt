package com.rmpsdroid.batterydiagnostics.collection

import com.rmpsdroid.batterydiagnostics.capability.CapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification tests built from strings the platform actually emitted during Phase 1B
 * on Android 16, not from invented examples.
 *
 * The recurring theme is that every failure mode returned exit status 0.
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

    @Test
    fun `denial naming DUMP is classified as that permission missing, despite exit 0`() {
        // Measured verbatim, app UID with no permissions granted.
        val r = result(
            exitCode = 0,
            stdout = "Permission Denial: can't dump BatteryStatsService from from " +
                "pid=22446, uid=10241 due to missing android.permission.DUMP permission",
        )
        assertEquals(
            CapabilityState.PermissionMissing("android.permission.DUMP"),
            r.classify(),
        )
    }

    @Test
    fun `denial naming PACKAGE_USAGE_STATS is distinguished from the DUMP denial`() {
        // Measured after DUMP alone was granted. Proves DUMP is not sufficient.
        val r = result(
            exitCode = 0,
            stdout = "Permission Denial: can't dump BatteryStatsService from from " +
                "pid=22548, uid=10241 due to missing android.permission.PACKAGE_USAGE_STATS permission",
        )
        assertEquals(
            CapabilityState.PermissionMissing("android.permission.PACKAGE_USAGE_STATS"),
            r.classify(),
        )
    }

    @Test
    fun `MATCH_ANY_USER security exception resolves to INTERACT_ACROSS_USERS_FULL first`() {
        // Measured after DUMP + PACKAGE_USAGE_STATS. The text names both the _FULL and the
        // plain permission; the more specific name must win so we do not report the wrong one.
        val r = result(
            exitCode = 0,
            stdout = "Security exception: MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS " +
                "permission: UID 10241 requires android.permission.INTERACT_ACROSS_USERS_FULL " +
                "or android.permission.INTERACT_ACROSS_USERS to access user 0.",
        )
        assertEquals(
            CapabilityState.PermissionMissing("android.permission.INTERACT_ACROSS_USERS_FULL"),
            r.classify(),
        )
    }

    @Test
    fun `real payload with exit 0 is Available`() {
        val r = result(
            exitCode = 0,
            stdout = "9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1\n9,hsp,0,0,\"wifi-off\"",
        )
        assertEquals(CapabilityState.Available, r.classify())
    }

    @Test
    fun `empty output with exit 0 is AvailableNoEvents, not a failure`() {
        // A healthy source with nothing to report must not be presented as broken.
        val state = result(exitCode = 0, stdout = "").classify()
        assertTrue("expected AvailableNoEvents but was $state", state is CapabilityState.AvailableNoEvents)
    }

    @Test
    fun `empty output with non-zero exit is ExecutionFailed`() {
        val state = result(exitCode = 1, stdout = "").classify()
        assertTrue("expected ExecutionFailed but was $state", state is CapabilityState.ExecutionFailed)
    }

    @Test
    fun `null exit code means the process never completed`() {
        val state = result(exitCode = null, stdout = "partial").classify()
        assertTrue("expected ExecutionFailed but was $state", state is CapabilityState.ExecutionFailed)
    }

    @Test
    fun `an unrecognised denial is ExecutionFailed rather than silently Available`() {
        val state = result(
            exitCode = 0,
            stdout = "Permission Denial: can't dump SomeOtherService from uid=10241",
        ).classify()
        assertTrue("expected ExecutionFailed but was $state", state is CapabilityState.ExecutionFailed)
    }

    @Test
    fun `denial text arriving on stderr is classified the same as on stdout`() {
        // Phase 1B measured denials on stdout, but a backend may separate streams differently.
        val r = result(
            exitCode = 0,
            stdout = "",
            stderr = "Permission Denial: missing android.permission.DUMP permission",
        )
        assertEquals(
            CapabilityState.PermissionMissing("android.permission.DUMP"),
            r.classify(),
        )
    }
}

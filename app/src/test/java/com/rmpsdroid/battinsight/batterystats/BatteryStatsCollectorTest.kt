package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipeline from backend to normalised capture.
 *
 * The decoder's own tests prove it reads bytes correctly. These prove the steps around it:
 * that the fixed command is the one that runs, that a denial is caught before decoding, that
 * a truncated or timed-out capture cannot present as a complete one, and that the backend
 * reaches the model only as provenance.
 */
class BatteryStatsCollectorTest {

    private val clock = object : CaptureClock {
        override fun elapsedRealtimeMillis() = 5_000L
        override fun wallClockMillis() = 1_700_000_000_000L
    }

    /** A runner that returns whatever it was built with, and records what it was asked. */
    private class FakeRunner(
        private val stdout: ByteArray,
        private val exitCode: Int? = 0,
        private val truncated: Boolean = false,
        private val timedOut: Boolean = false,
        private val throws: Throwable? = null,
    ) : ProcessRunner {
        var ranCommand: ProbeCommand? = null
            private set

        override suspend fun isReady() = true

        override suspend fun run(command: ProbeCommand, timeoutMillis: Long): ExecutionOutput {
            ranCommand = command
            throws?.let { throw it }
            return ExecutionOutput(
                command = command,
                exitCode = exitCode,
                stdout = stdout,
                stderr = ByteArray(0),
                durationMillis = 12L,
                timedOut = timedOut,
                truncated = truncated,
            )
        }
    }

    @Test
    fun `the collector runs the fixed checkin command, never a constructed one`() = runTest {
        val runner = FakeRunner(VALID)

        BatteryStatsCollector(clock).collect(runner, BackendIdentity.Kind.SHELL)

        assertEquals(ProbeCommand.BatteryStatsCheckinCurrent, runner.ranCommand)
        assertEquals(
            "-c, never --checkin: the latter is documented to clear old stats",
            listOf("dumpsys", "batterystats", "-c"),
            runner.ranCommand!!.argv,
        )
    }

    @Test
    fun `a good capture decodes end to end`() = runTest {
        val result = BatteryStatsCollector(clock).collect(FakeRunner(VALID), BackendIdentity.Kind.SHELL)

        val capture = (result as DecodeResult.Success).capture
        assertEquals(36, capture.version.checkinVersion)
        assertEquals(1, capture.kernelWakelockCount)
        assertEquals(VALID.size, capture.metadata.payloadByteCount)
        assertEquals(5_000L, capture.metadata.captureElapsedRealtimeMillis)
        assertEquals(BackendIdentity.Kind.SHELL, capture.metadata.backendKind)
    }

    /**
     * A denial is caught by the existing collection classifier, not by a second one.
     *
     * The measured shape is the trap: exit status 0, empty stderr, denial on stdout. Anything
     * keying off the exit code sees success.
     */
    @Test
    fun `a denial with exit status zero is still a denial`() = runTest {
        val denial = "Permission Denial: can't dump BatteryStats from pid=1234, uid=10234"
        val result = BatteryStatsCollector(clock)
            .collect(FakeRunner(denial.toByteArray(), exitCode = 0), BackendIdentity.Kind.APP_UID)

        assertEquals(DecodeOutcome.PERMISSION_DENIAL_PAYLOAD, result.outcome)
        assertNull("a denial is never a capture", result.captureOrNull)
    }

    @Test
    fun `a truncated capture never presents as complete`() = runTest {
        val result = BatteryStatsCollector(clock)
            .collect(FakeRunner(VALID, truncated = true), BackendIdentity.Kind.SHELL)

        assertEquals(DecodeOutcome.TRUNCATED, result.outcome)
        assertNull(result.captureOrNull)
    }

    @Test
    fun `a timed-out capture is truncated, not empty`() = runTest {
        val result = BatteryStatsCollector(clock)
            .collect(FakeRunner(ByteArray(0), timedOut = true), BackendIdentity.Kind.SHELL)

        assertEquals(
            "a timeout means we stopped reading, not that the device sent nothing",
            DecodeOutcome.TRUNCATED,
            result.outcome,
        )
    }

    @Test
    fun `a runner that throws becomes a typed failure, not a crash`() = runTest {
        val result = BatteryStatsCollector(clock).collect(
            FakeRunner(ByteArray(0), throws = IllegalStateException("binder died")),
            BackendIdentity.Kind.SHELL,
        )

        assertEquals(DecodeOutcome.UNKNOWN_FAILURE, result.outcome)
        assertTrue((result as DecodeResult.Failure).detail.contains("could not be run"))
    }

    /**
     * The backend changes the provenance and nothing else.
     *
     * Backend selection is Phase 4's responsibility; the decoder must not be able to behave
     * differently because of it.
     */
    @Test
    fun `the same bytes from either backend give the same model`() = runTest {
        val collector = BatteryStatsCollector(clock)

        val shell = collector.collect(FakeRunner(VALID), BackendIdentity.Kind.SHELL).captureOrNull!!
        val app = collector.collect(FakeRunner(VALID), BackendIdentity.Kind.APP_UID).captureOrNull!!

        assertEquals(shell.kernelWakelocks, app.kernelWakelocks)
        assertEquals(shell.partialWakelocks, app.partialWakelocks)
        assertEquals(shell.version, app.version)
        assertEquals(BackendIdentity.Kind.SHELL, shell.metadata.backendKind)
        assertEquals(BackendIdentity.Kind.APP_UID, app.metadata.backendKind)
    }

    private companion object {
        val VALID = (
            "9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1\n" +
                "9,0,i,uid,1000,com.android.settings\n" +
                "9,0,l,kwl,\"bt_read_wake_lock\",681038,678,-1,-1\n" +
                "9,1000,l,wl,WindowManager,7713,f,3,-1,-1,-1,120,p,2,0,0,0,bp,0,0,0,0,0,w,0,0,0,0\n"
            ).toByteArray()
    }
}

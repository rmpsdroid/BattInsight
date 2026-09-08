package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import com.rmpsdroid.battinsight.collection.SourceFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed execution must never be reported as a device that produced nothing.
 *
 * ## The defect this pins
 *
 * Phase 10A measured a Samsung SM-M156B whose `dumpsys batterystats -c` produced 1,066,676
 * bytes -- a healthy source -- while the application told the user "Android returned nothing
 * at all". The collector had already classified the attempt correctly as
 * `CollectionOutcome.ExecutionFailed`; it then discarded that classification, passed the
 * zero bytes a failed transport leaves behind to the decoder, and the decoder truthfully
 * reported `EMPTY` about the bytes it was given.
 *
 * Every step was locally reasonable and the result was a false statement about the user's
 * platform. That is the shape of failure this project exists to avoid, so the assertions
 * here are about which outcome the *user* is shown, not about which branch runs.
 */
class CollectorFailureClassificationTest {

    private val clock = object : CaptureClock {
        override fun elapsedRealtimeMillis() = 5_000L
        override fun wallClockMillis() = 1_700_000_000_000L
    }

    /** Records whether the decoder was reached at all. */
    private class SpyDecoder(
        private val delegate: BatteryStatsDecoder = CheckinDecoder(),
    ) : BatteryStatsDecoder {
        var calls = 0
            private set

        override val supportedFormats: Set<SourceFormat> get() = delegate.supportedFormats

        override fun decode(payload: ByteArray, metadata: CaptureMetadata): DecodeResult {
            calls++
            return delegate.decode(payload, metadata)
        }
    }

    private class FakeRunner(
        private val stdout: ByteArray = ByteArray(0),
        private val stderr: ByteArray = ByteArray(0),
        private val exitCode: Int? = 0,
    ) : ProcessRunner {
        override suspend fun isReady() = true

        override suspend fun run(command: ProbeCommand, timeoutMillis: Long) = ExecutionOutput(
            command = command,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMillis = 40L,
        )
    }

    /**
     * The physical failure, reproduced at the collector.
     *
     * A refused Binder transaction leaves exactly this: no exit status, no bytes, and a
     * transport reason on stderr.
     */
    @Test
    fun `a transport failure is an execution failure, never an empty device`() = runTest {
        val decoder = SpyDecoder()
        val result = BatteryStatsCollector(clock, decoder).collect(
            FakeRunner(
                stderr = "the privileged service ended before reporting a result".toByteArray(),
                exitCode = null,
            ),
            BackendIdentity.Kind.SHELL,
        )

        assertEquals(DecodeOutcome.EXECUTION_FAILED, result.outcome)
        assertEquals("the decoder must not be asked to explain a failed execution", 0, decoder.calls)
    }

    @Test
    fun `a non-zero exit status is an execution failure`() = runTest {
        val result = BatteryStatsCollector(clock).collect(
            FakeRunner(exitCode = 1),
            BackendIdentity.Kind.SHELL,
        )

        assertEquals(DecodeOutcome.EXECUTION_FAILED, result.outcome)
    }

    @Test
    fun `a non-zero exit with output on standard error is still an execution failure`() = runTest {
        val decoder = SpyDecoder()
        val result = BatteryStatsCollector(clock, decoder).collect(
            FakeRunner(stderr = "dumpsys: something went wrong".toByteArray(), exitCode = 255),
            BackendIdentity.Kind.SHELL,
        )

        assertEquals(DecodeOutcome.EXECUTION_FAILED, result.outcome)
        assertEquals(0, decoder.calls)
    }

    /**
     * The fix must not swallow the case it is distinguished from.
     *
     * A command that genuinely ran, exited cleanly and produced nothing is still EMPTY. That
     * is a fact about the source, and collapsing it into an execution failure would trade one
     * false statement for another.
     */
    @Test
    fun `a command that ran cleanly and produced nothing is still empty`() = runTest {
        val result = BatteryStatsCollector(clock).collect(
            FakeRunner(exitCode = 0),
            BackendIdentity.Kind.SHELL,
        )

        assertEquals(DecodeOutcome.EMPTY, result.outcome)
    }

    @Test
    fun `a healthy capture is unaffected`() = runTest {
        val result = BatteryStatsCollector(clock).collect(
            FakeRunner(stdout = VALID, exitCode = 0),
            BackendIdentity.Kind.SHELL,
        )

        assertEquals(DecodeOutcome.SUCCESS, result.outcome)
    }

    @Test
    fun `a denial is still a denial and not an execution failure`() = runTest {
        val denial = (
            "Permission Denial: can't dump BatteryStatsService from pid=1 due to missing " +
                "android.permission.DUMP permission"
            ).toByteArray()

        val result = BatteryStatsCollector(clock).collect(
            FakeRunner(stdout = denial, exitCode = 0),
            BackendIdentity.Kind.SHELL,
        )

        assertEquals(DecodeOutcome.PERMISSION_DENIAL_PAYLOAD, result.outcome)
    }

    @Test
    fun `the failure detail names no exception and carries no payload`() = runTest {
        val result = BatteryStatsCollector(clock).collect(
            FakeRunner(stderr = "TransactionTooLargeException: data parcel size".toByteArray(), exitCode = 7),
            BackendIdentity.Kind.SHELL,
        )

        val detail = (result as DecodeResult.Failure).detail
        assertTrue("the detail should say what happened", detail.contains("did not complete"))
        assertFalse("and must not surface a raw exception", detail.contains("Exception"))
        assertFalse(detail.contains("parcel"))
    }

    @Test
    fun `metadata still describes the attempt when it failed`() = runTest {
        val result = BatteryStatsCollector(clock).collect(
            FakeRunner(exitCode = null),
            BackendIdentity.Kind.APP_UID,
        ) as DecodeResult.Failure

        val metadata = requireNotNull(result.metadata)
        assertEquals(BackendIdentity.Kind.APP_UID, metadata.backendKind)
        assertEquals(5_000L, metadata.captureElapsedRealtimeMillis)
    }

    private companion object {
        val VALID = (
            "9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1\n" +
                "9,0,i,uid,1000,com.android.settings\n" +
                "9,0,l,kwl,\"bt_read_wake_lock\",681038,678,-1,-1\n"
            ).toByteArray()
    }
}

package com.rmpsdroid.battinsight

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.harness.ITransportHarness
import com.rmpsdroid.battinsight.harness.TransportHarnessService
import com.rmpsdroid.battinsight.harness.TransportPayload
import com.rmpsdroid.battinsight.shizuku.ProbeCompletion
import com.rmpsdroid.battinsight.shizuku.ProbeStreamProtocol
import com.rmpsdroid.battinsight.shizuku.ProbeStreamReader
import com.rmpsdroid.battinsight.shizuku.ProbeStreamResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The transport across a **real** Binder, at sizes the old one could not carry.
 *
 * ## Why this test has to exist separately from the JVM ones
 *
 * The JVM tests prove ordering, exactness, truncation and cancellation over real operating
 * system pipes, and they run at four megabytes. What no JVM test can reproduce is the thing
 * that actually failed: a kernel refusing an oversized transaction. A parcel size limit is a
 * property of Binder, so proving anything about it requires Binder.
 *
 * The Phase 10A investigation found this gap was total. The largest byte array in the whole
 * suite was 900,000 bytes and never left the JVM; the only test that crossed a real Binder
 * ran the `id` probe, whose output is one line. Nothing had ever moved a megabyte across the
 * privileged boundary, so the transport worked in tests and failed on a phone.
 *
 * ## Why a harness rather than the real service
 *
 * The production path needs a Shizuku server the device has authorised, which ordinary CI
 * cannot provide, so a test depending on it would be skipped exactly where it is most needed.
 * The harness runs in its own process using the **production** streamer and protocol, so the
 * Binder boundary, the parcel and the descriptor transfer are all genuine. Only the source of
 * the bytes is substituted, which is also what lets the test choose a size.
 *
 * It is declared in the **debug** source set, not in the test APK. A service declared in the
 * test APK starts a process whose dex path is the test APK alone, which carries neither the
 * production classes nor the Kotlin standard library, so it cannot load the streamer it is
 * supposed to be exercising. The release APK declares no services.
 *
 * ## The negative proof is a test, not a mutation
 *
 * [theOldByValueTransportStillFailsAtTheSizeThatFailedOnHardware] calls the harness method
 * that returns the payload the way `ProbeService` used to, and asserts it fails at the size
 * measured on the Samsung. Keeping it permanently is better than mutating the code once: the
 * pair of tests states the regression as a property, and a future change that quietly
 * reintroduces a by-value reply has something standing against it rather than a note in a
 * report that the mutation once failed.
 */
@RunWith(AndroidJUnit4::class)
class ProbeStreamBinderTest {

    /**
     * Bound once for the whole class, not once per test.
     *
     * Per-test binding churned the harness process -- last client unbinds, the process is
     * killed, the next test binds again -- and produced intermittent
     * `DeadObjectException`s from connections handed out while the previous process was
     * still going away. That is the same teardown race `ShizukuUserServiceRunner` documents
     * against `bindUserService`, arriving here for the same reason, and the cheapest way not
     * to have it is not to create it: nothing in these tests requires a fresh process, so one
     * process serves the class. A bounded retry covers the first bind for the same reason the
     * production runner has one.
     */
    companion object {
        private lateinit var connection: ServiceConnection
        private lateinit var harness: ITransportHarness

        @BeforeClass
        @JvmStatic
        fun bindHarness() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            var binder: IBinder? = null
            repeat(BIND_ATTEMPTS) {
                val bound = ArrayBlockingQueue<IBinder>(1)
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        service?.let { bound.offer(it) }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                val intent = Intent(context, TransportHarnessService::class.java)
                assertTrue(
                    "the harness service must bind",
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE),
                )
                val candidate = bound.poll(30, TimeUnit.SECONDS)
                if (candidate != null && candidate.pingBinder()) {
                    binder = candidate
                    return@repeat
                }
                runCatching { context.unbindService(connection) }
            }
            assertNotNull("the harness did not connect", binder)
            harness = ITransportHarness.Stub.asInterface(binder)
        }

        @AfterClass
        @JvmStatic
        fun unbindHarness() {
            runCatching {
                InstrumentationRegistry.getInstrumentation().targetContext.unbindService(connection)
            }
        }

        private const val BIND_ATTEMPTS = 3

        /** The Samsung SM-M156B payload the by-value transport could not carry. */
        private const val MEASURED_FAILING_BYTES = 1_066_676
        private const val FOUR_MEGABYTES = 4 * 1024 * 1024
    }

    // ------------------------------------------------------------------ the fix

    /**
     * The exact size that failed on hardware, across a real Binder.
     *
     * 1,066,676 bytes is the measured `dumpsys batterystats -c` output of the Samsung
     * SM-M156B. Under the old transport this produced a 1,048,800-byte reply parcel and
     * `FAILED BINDER TRANSACTION`.
     */
    @Test
    fun thePayloadSizeThatFailedOnHardwareNowCrossesBinderExactly() {
        val result = stream(stdoutBytes = MEASURED_FAILING_BYTES)

        assertEquals(MEASURED_FAILING_BYTES, result.stdout.size)
        assertArrayEquals(
            "the capture must survive the journey byte for byte",
            TransportPayload.of(MEASURED_FAILING_BYTES, TransportHarnessService.STDOUT_SEED),
            result.stdout,
        )
        assertFalse("nothing was cut short", result.truncated)
        assertEquals(0, completed(result).exitCode)
    }

    @Test
    fun severalMegabytesCrossBinderExactly() {
        val result = stream(stdoutBytes = FOUR_MEGABYTES)

        assertEquals(FOUR_MEGABYTES, result.stdout.size)
        assertArrayEquals(
            TransportPayload.of(FOUR_MEGABYTES, TransportHarnessService.STDOUT_SEED),
            result.stdout,
        )
        assertFalse(result.truncated)
    }

    @Test
    fun aLargeStandardErrorCrossesAlongsideALargeCapture() {
        val result = stream(stdoutBytes = FOUR_MEGABYTES, stderrBytes = 512 * 1024)

        assertArrayEquals(
            TransportPayload.of(FOUR_MEGABYTES, TransportHarnessService.STDOUT_SEED),
            result.stdout,
        )
        assertArrayEquals(
            TransportPayload.of(512 * 1024, TransportHarnessService.STDERR_SEED),
            result.stderr,
        )
    }

    @Test
    fun anExitStatusSurvivesTheBinderBoundary() {
        val completion = completed(stream(stdoutBytes = 8192, exitCode = 42))

        assertTrue(completion.hasExitCode)
        assertEquals(42, completion.exitCode)
    }

    @Test
    fun aSmallCaptureIsUnchangedByTheNewTransport() {
        val result = stream(stdoutBytes = 32 * 1024)

        assertArrayEquals(
            TransportPayload.of(32 * 1024, TransportHarnessService.STDOUT_SEED),
            result.stdout,
        )
        assertEquals(0, completed(result).exitCode)
    }

    // ------------------------------------------------------------------ the negative proof

    /**
     * The old transport, at the size that failed, still fails. That is the point.
     *
     * The failure surfaces differently on different platform versions -- a
     * `TransactionTooLargeException`, a `DeadObjectException`, or a bare `RuntimeException`
     * from the parcel -- so the assertion is that the call does not return a usable payload,
     * not that it names a particular exception.
     */
    @Test
    fun theOldByValueTransportStillFailsAtTheSizeThatFailedOnHardware() {
        try {
            val bundle = harness.returnByValue(MEASURED_FAILING_BYTES)
            val stdout = bundle?.getByteArray(ProbeStreamProtocol.KEY_STDOUT)
            fail(
                "the by-value transport must not carry " + MEASURED_FAILING_BYTES +
                    " bytes across a Binder; it returned " + (stdout?.size ?: -1),
            )
        } catch (expected: Exception) {
            // The transport refuses. This is the defect, reproduced on demand.
        }
    }

    /**
     * And it still works below the limit, so the test above is about size and nothing else.
     *
     * Without this, a harness that was simply broken would make the negative proof pass for
     * the wrong reason.
     */
    @Test
    fun theOldByValueTransportStillWorksWellBelowTheLimit() {
        val bundle = harness.returnByValue(32 * 1024)

        assertNotNull(bundle)
        assertArrayEquals(
            TransportPayload.of(32 * 1024, TransportHarnessService.STDOUT_SEED),
            bundle!!.getByteArray(ProbeStreamProtocol.KEY_STDOUT),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun stream(
        stdoutBytes: Int,
        stderrBytes: Int = 0,
        exitCode: Int = 0,
    ): ProbeStreamResult {
        val bundle = requireNotNull(harness.openStream(stdoutBytes, stderrBytes, exitCode)) {
            "the harness returned no reply"
        }
        assertEquals(
            "the harness must speak the production protocol",
            ProbeStreamProtocol.PROTOCOL_VERSION,
            bundle.getInt(ProbeStreamProtocol.KEY_PROTOCOL, 0),
        )
        val fdType = ParcelFileDescriptor::class.java
        val out = requireNotNull(bundle.getParcelable(ProbeStreamProtocol.KEY_STDOUT_FD, fdType))
        val err = requireNotNull(bundle.getParcelable(ProbeStreamProtocol.KEY_STDERR_FD, fdType))
        val status = requireNotNull(bundle.getParcelable(ProbeStreamProtocol.KEY_STATUS_FD, fdType))

        return runBlocking {
            withTimeout(120_000) {
                ProbeStreamReader.consume(
                    ParcelFileDescriptor.AutoCloseInputStream(out),
                    ParcelFileDescriptor.AutoCloseInputStream(err),
                    ParcelFileDescriptor.AutoCloseInputStream(status),
                )
            }
        }
    }

    private fun completed(result: ProbeStreamResult): ProbeCompletion.Complete {
        val completion = result.completion
        assertTrue(
            "expected a completion frame, got " + completion,
            completion is ProbeCompletion.Complete,
        )
        return completion as ProbeCompletion.Complete
    }
}

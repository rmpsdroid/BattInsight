package com.rmpsdroid.battinsight.shizuku

import com.rmpsdroid.battinsight.collection.CaptureLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The transport regression for the Phase 10A P1.
 *
 * ## What was not covered before, and why the old suite could not have caught it
 *
 * The failing capture was 1,066,676 bytes. The largest byte array anywhere in the suite was
 * `ByteArray(900_000)`, in a JVM test that never touched a transport at all, and the only
 * test that crossed a real Binder ran the `id` probe, whose output is one short line. So
 * nothing in the project had ever moved more than a megabyte through the privileged path,
 * and the 1 MiB ceiling sat 15% above the largest payload ever exercised. The defect was not
 * missed through oversight in a test; there was no test in that size range to miss it.
 *
 * These tests run over **real operating-system pipes** ([Pipe]), not in-memory fakes, so the
 * flow control that makes streaming work -- and that makes a serial producer deadlock -- is
 * genuinely present. The Binder boundary itself is proven separately and on a device by
 * `ProbeStreamBinderTest`, because no JVM test can prove a parcel size limit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeStreamTransportTest {

    // ------------------------------------------------------------------ size progression

    @Test
    fun `a small capture arrives exactly`() {
        val payload = pattern(64 * 1024)
        val result = streamed(stdout = payload)

        assertArrayEquals("stdout must be byte for byte", payload, result.stdout)
        assertFalse("nothing was cut short", result.truncated)
        assertEquals(0, completed(result).exitCode)
    }

    /**
     * The size that actually failed, plus the margin that made it fail.
     *
     * 1,066,676 bytes is the measured `dumpsys batterystats -c` output of the Samsung
     * SM-M156B that exposed the defect. Under the old by-value transport this exact payload
     * produced a 1,048,800-byte reply parcel and a refused transaction.
     */
    @Test
    fun `the payload size that failed on hardware now arrives exactly`() {
        val payload = pattern(MEASURED_FAILING_BYTES)
        val result = streamed(stdout = payload)

        assertEquals(MEASURED_FAILING_BYTES, result.stdout.size)
        assertArrayEquals(payload, result.stdout)
        assertFalse(result.truncated)
        assertEquals(0, completed(result).exitCode)
    }

    @Test
    fun `several megabytes arrive exactly`() {
        val payload = pattern(4 * 1024 * 1024)
        val result = streamed(stdout = payload)

        assertEquals(4 * 1024 * 1024, result.stdout.size)
        assertArrayEquals("four megabytes, byte for byte", payload, result.stdout)
        assertFalse(result.truncated)
    }

    // ------------------------------------------------------------------ the deadlock

    /**
     * Both streams are large, and both must arrive intact.
     *
     * This one is about volume rather than ordering: a megabyte of standard error must not
     * cost any of the four megabytes of capture, in either direction. The test below it is
     * the one that pins concurrent draining, and it needs a child that can block to do so.
     */
    @Test
    fun `a large stderr is preserved alongside a large stdout without deadlocking`() {
        val out = pattern(4 * 1024 * 1024)
        val err = pattern(1024 * 1024, seed = 11)

        val result = streamed(stdout = out, stderr = err, timeoutMillis = DEADLOCK_TIMEOUT_MS)

        assertArrayEquals("stdout intact", out, result.stdout)
        assertArrayEquals("stderr intact", err, result.stderr)
        assertFalse(result.truncated)
    }

    /**
     * The deadlock as it actually occurs: a child that fills standard error first.
     *
     * The test above cannot produce it, and that is worth being precise about. Its child is
     * an in-memory stream that never blocks, so a producer draining stdout before stderr
     * still finishes. The hazard needs a child whose own pipes push back -- which is every
     * real one, because a process writing to a full pipe is suspended by the kernel until
     * somebody reads.
     *
     * So this child is given real pipes and writes a megabyte to standard error *before*
     * writing anything to standard output. A producer that drains stdout to its end first --
     * the single-threaded shape both backends had before Phase 10A.3 -- waits for output the
     * child cannot produce until its stderr is drained, and the child waits for a reader that
     * will not arrive until stdout ends. Neither side is at fault alone.
     */
    @Test
    fun `a child that fills standard error first does not deadlock the producer`() {
        val childOut = Pipe.open()
        val childErr = Pipe.open()
        val errPayload = pattern(1024 * 1024, seed = 3)
        val outPayload = pattern(1024 * 1024, seed = 4)

        Thread({
            Channels.newOutputStream(childErr.sink()).use {
                it.write(errPayload)
                it.flush()
            }
            Channels.newOutputStream(childOut.sink()).use {
                it.write(outPayload)
                it.flush()
            }
        }, "test-child").start()

        val result = streamProcess(
            FakeProcess(
                stdout = Channels.newInputStream(childOut.source()),
                stderr = Channels.newInputStream(childErr.source()),
                exit = 0,
            ),
            timeoutMillis = DEADLOCK_TIMEOUT_MS,
        )

        assertArrayEquals("stdout intact", outPayload, result.stdout)
        assertArrayEquals("stderr intact", errPayload, result.stderr)
        assertEquals(0, completed(result).exitCode)
    }

    // ------------------------------------------------------------------ completion metadata

    @Test
    fun `a non-zero exit status survives the stream`() {
        val result = streamed(stdout = pattern(4096), exit = 13)

        val completion = completed(result)
        assertTrue(completion.hasExitCode)
        assertEquals(13, completion.exitCode)
        assertEquals("", completion.failure)
    }

    @Test
    fun `the completion frame reports the byte counts it moved`() {
        val result = streamed(stdout = pattern(300_000), stderr = pattern(1_000, seed = 5))

        val completion = completed(result)
        assertEquals(300_000L, completion.stdoutByteCount)
        assertEquals(1_000L, completion.stderrByteCount)
    }

    // ------------------------------------------------------------------ the safety ceiling

    /**
     * The ceiling is a memory bound, and hitting it is reported, never silently absorbed.
     *
     * A small limit is injected rather than producing 16 MiB, because what is under test is
     * the behaviour at the ceiling, not the value of the constant. The constant is asserted
     * separately, and the two together are the policy.
     */
    @Test
    fun `reaching the safety ceiling is reported as truncation, not as a short success`() {
        val payload = pattern(200_000)
        val result = streamed(stdout = payload, limit = 50_000)

        assertEquals("only the prefix was kept", 50_000, result.stdout.size)
        assertArrayEquals(payload.copyOf(50_000), result.stdout)
        assertTrue("and it must say so", result.truncated)
        assertTrue("the producer knew it cut the stream", completed(result).stdoutTruncated)
    }

    @Test
    fun `the production ceiling admits payloads far above the size that failed`() {
        assertTrue(
            "the ceiling must clear the measured failing payload by a wide margin",
            CaptureLimits.MAX_CAPTURE_BYTES > MEASURED_FAILING_BYTES * 8,
        )
        assertTrue(
            "and must stay bounded: the decoder materialises this once in the app process",
            CaptureLimits.MAX_CAPTURE_BYTES <= 32 * 1024 * 1024,
        )
    }

    // ------------------------------------------------------------------ abnormal endings

    /**
     * A remote that dies mid-capture must not look like a capture that was merely short.
     *
     * The pipes close with the process and no completion frame is ever written, so the
     * distinction rests entirely on the frame's absence -- which is why completion is framed
     * explicitly instead of being inferred from end-of-file.
     */
    @Test
    fun `a remote that dies mid-stream is a typed failure, not a short capture`() {
        val out = Pipe.open()
        val err = Pipe.open()
        val status = Pipe.open()

        Thread {
            val sink = Channels.newOutputStream(out.sink())
            sink.write(pattern(8192))
            sink.flush()
            // Death: every descriptor closes, and no frame is written.
            sink.close()
            Channels.newOutputStream(err.sink()).close()
            Channels.newOutputStream(status.sink()).close()
        }.start()

        val result = runBlocking {
            withTimeout(DEADLOCK_TIMEOUT_MS) {
                ProbeStreamReader.consume(
                    Channels.newInputStream(out.source()),
                    Channels.newInputStream(err.source()),
                    Channels.newInputStream(status.source()),
                )
            }
        }

        val completion = result.completion
        assertTrue(
            "an unreported ending must be Missing, got " + completion,
            completion is ProbeCompletion.Missing,
        )
    }

    @Test
    fun `a stream that ends early takes the exit status down with it`() {
        // The child produced output, then the reader vanished: the producer reports the
        // failure, and a process that could not be streamed properly has no usable status.
        val process = FakeProcess(
            stdout = ByteArrayInputStream(pattern(4096)),
            stderr = failingStream(),
            exit = 0,
        )
        val result = streamProcess(process)

        val completion = completed(result)
        assertTrue("the producer must name the broken stream", completion.failure.isNotEmpty())
        assertTrue("and must have killed the child", process.destroyed)
    }

    // ------------------------------------------------------------------ cancellation

    /**
     * Cancelling must reach all the way to the privileged child.
     *
     * A blocking pipe read does not observe coroutine cancellation, so without the guard in
     * [ProbeStreamReader] this would sit until the remote happened to finish -- and the
     * child would keep running behind it. The assertions are the three things that must
     * follow: the reader returns, the producer stops, and the child is destroyed.
     */
    @Test
    fun `cancelling closes the descriptors, stops the producer and kills the child`() {
        val forever = endlessStream()
        val process = FakeProcess(
            stdout = forever,
            stderr = ByteArrayInputStream(ByteArray(0)),
            exit = 0,
            waitUntil = CountDownLatch(1),
        )

        val out = Pipe.open()
        val err = Pipe.open()
        val status = Pipe.open()
        val producerDone = CountDownLatch(1)
        val producer = Thread {
            try {
                ProbeProcessStreamer().stream(
                    process,
                    Channels.newOutputStream(out.sink()),
                    Channels.newOutputStream(err.sink()),
                    Channels.newOutputStream(status.sink()),
                    System.currentTimeMillis(),
                )
            } finally {
                producerDone.countDown()
            }
        }
        producer.start()

        runBlocking {
            val consumer = launch(Dispatchers.IO) {
                ProbeStreamReader.consume(
                    Channels.newInputStream(out.source()),
                    Channels.newInputStream(err.source()),
                    Channels.newInputStream(status.source()),
                )
            }
            // Let the pipes actually start moving before pulling the plug.
            while (forever.produced < 256 * 1024) Thread.sleep(5)
            consumer.cancelAndJoinWithin(DEADLOCK_TIMEOUT_MS)
        }

        assertTrue(
            "the producer must exit once its reader is gone",
            producerDone.await(DEADLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue("the child must not be left running", process.destroyed)
    }

    // ------------------------------------------------------------------ the frame itself

    @Test
    fun `a completion frame round-trips`() {
        val original = ProbeCompletion.Complete(
            hasExitCode = true,
            exitCode = 7,
            stdoutTruncated = true,
            stderrTruncated = false,
            durationMillis = 4321,
            stdoutByteCount = 999_999,
            stderrByteCount = 12,
            failure = "something to carry",
        )
        val buffer = java.io.ByteArrayOutputStream()
        ProbeStreamProtocol.writeCompletion(buffer, original)

        val read = ProbeStreamProtocol.readCompletion(ByteArrayInputStream(buffer.toByteArray()))
        assertEquals(original, read)
    }

    @Test
    fun `a half-written frame is missing, not a completion`() {
        val buffer = java.io.ByteArrayOutputStream()
        ProbeStreamProtocol.writeCompletion(
            buffer,
            ProbeCompletion.Complete(true, 0, false, false, 1, 2, 3, ""),
        )
        val half = buffer.toByteArray().copyOf(buffer.size() / 2)

        val read = ProbeStreamProtocol.readCompletion(ByteArrayInputStream(half))
        assertTrue("a partial frame is not a result", read is ProbeCompletion.Missing)
    }

    @Test
    fun `bytes from some other contract are missing, not a completion`() {
        val read = ProbeStreamProtocol.readCompletion(
            ByteArrayInputStream("not a completion frame at all".toByteArray()),
        )
        assertTrue(read is ProbeCompletion.Missing)
    }

    @Test
    fun `an empty status stream is missing, not a completion`() {
        val read = ProbeStreamProtocol.readCompletion(ByteArrayInputStream(ByteArray(0)))
        assertTrue(read is ProbeCompletion.Missing)
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the real producer and the real consumer against real pipes. */
    private fun streamed(
        stdout: ByteArray,
        stderr: ByteArray = ByteArray(0),
        exit: Int = 0,
        limit: Int = CaptureLimits.MAX_CAPTURE_BYTES,
        timeoutMillis: Long = DEADLOCK_TIMEOUT_MS,
    ): ProbeStreamResult = streamProcess(
        FakeProcess(ByteArrayInputStream(stdout), ByteArrayInputStream(stderr), exit),
        limit,
        timeoutMillis,
    )

    private fun streamProcess(
        process: Process,
        limit: Int = CaptureLimits.MAX_CAPTURE_BYTES,
        timeoutMillis: Long = DEADLOCK_TIMEOUT_MS,
    ): ProbeStreamResult {
        val out = Pipe.open()
        val err = Pipe.open()
        val status = Pipe.open()

        Thread({
            ProbeProcessStreamer(limit).stream(
                process,
                Channels.newOutputStream(out.sink()),
                Channels.newOutputStream(err.sink()),
                Channels.newOutputStream(status.sink()),
                System.currentTimeMillis(),
            )
        }, "test-producer").start()

        return runBlocking {
            withTimeout(timeoutMillis) {
                ProbeStreamReader.consume(
                    Channels.newInputStream(out.source()),
                    Channels.newInputStream(err.source()),
                    Channels.newInputStream(status.source()),
                    limit,
                )
            }
        }
    }

    private fun completed(result: ProbeStreamResult): ProbeCompletion.Complete {
        val completion = result.completion
        assertTrue("expected a completion frame, got " + completion, completion is ProbeCompletion.Complete)
        return completion as ProbeCompletion.Complete
    }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinWithin(millis: Long) {
        cancel()
        withTimeout(millis) { join() }
    }

    /** Deterministic, non-repeating enough that a misaligned copy would not compare equal. */
    private fun pattern(size: Int, seed: Int = 1): ByteArray {
        val bytes = ByteArray(size)
        var value = seed
        for (i in 0 until size) {
            value = value * 1_103_515_245 + 12_345
            bytes[i] = (value ushr 16).toByte()
        }
        return bytes
    }

    /** Never ends, and counts what it gave out so a test can wait for real movement. */
    private fun endlessStream() = object : InputStream() {
        @Volatile
        var produced: Long = 0

        override fun read(): Int {
            produced++
            return 0x41
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Arrays.fill(b, off, off + len, 0x41.toByte())
            produced += len
            return len
        }
    }

    /** Fails part way through, standing in for a descriptor that went away. */
    private fun failingStream() = object : InputStream() {
        private var served = 0

        override fun read(): Int = throw java.io.IOException("gone")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served > 0) throw java.io.IOException("gone")
            served++
            return minOf(len, 128).also { java.util.Arrays.fill(b, off, off + it, 0x42.toByte()) }
        }
    }

    /**
     * A [Process] whose streams and exit status the test controls.
     *
     * Real enough for the streamer, which only ever asks for the three streams, the exit
     * status and forcible destruction.
     */
    private class FakeProcess(
        private val stdout: InputStream,
        private val stderr: InputStream,
        private val exit: Int,
        private val waitUntil: CountDownLatch = CountDownLatch(0),
    ) : Process() {

        @Volatile
        var destroyed = false

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun exitValue(): Int = exit

        override fun waitFor(): Int {
            waitUntil.await()
            return exit
        }

        override fun destroy() {
            destroyed = true
            waitUntil.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private companion object {
        /**
         * Generous on purpose. Every timeout in this class is a deadlock detector, not a
         * performance assertion: the failures it guards against never finish at all, so the
         * only thing a tight bound buys is flakiness when the machine is busy building.
         */
        const val DEADLOCK_TIMEOUT_MS = 120_000L

        /** The Samsung SM-M156B payload that the by-value transport could not carry. */
        const val MEASURED_FAILING_BYTES = 1_066_676
    }
}

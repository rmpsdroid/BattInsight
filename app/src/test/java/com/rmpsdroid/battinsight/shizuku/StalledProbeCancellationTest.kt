package com.rmpsdroid.battinsight.shizuku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A privileged child that produces nothing must still die when the caller cancels.
 *
 * ## The gap this closes
 *
 * [ProbeProcessStreamer] kills its child when a pump loses its reader -- but a pump only
 * discovers that **on its next write**. A child producing no output never gets that far, so
 * closing the caller's descriptors, which is all cancellation used to do, did not reach it.
 * Phase 10A.3 measured a cancelled capture leaving a privileged `dumpsys` alive until the
 * whole service was torn down. R.131.
 *
 * A descriptor could not carry the missing signal, and that is worth stating because it is
 * the non-obvious part: every pipe end handed back inside the reply Bundle stays open in the
 * privileged process until the capture ends, because `Bundle` does not propagate
 * `PARCELABLE_WRITE_RETURN_VALUE` to the descriptors it carries and nothing else closes them
 * after marshalling. A pipe whose writer never closes cannot report end of stream. So the
 * signal is a call, and [ProbeChildRegistry] is what it reaches.
 *
 * ## What is proven here, and what is proven on a device
 *
 * These tests drive the **production** registry and the **production** streamer against a
 * child that stalls, and assert the child dies because of the cancellation rather than
 * because anything expired. What they cannot cover is the Binder call that carries the
 * signal; `ProbeStreamBinderTest` does that on a device, against a real remote process.
 *
 * Nothing here sleeps to establish state. The pumps are observed genuinely parked before
 * anything is cancelled, so a child that died for some other reason could not be mistaken
 * for one this cancelled.
 */
class StalledProbeCancellationTest {

    // ------------------------------------------------------------------ the registry

    @Test
    fun `cancelling ends a tracked child and reports how many it ended`() {
        val registry = ProbeChildRegistry()
        val child = StalledProcess()
        registry.add(child)

        assertEquals(1, registry.size)
        assertTrue("the child must be running before cancellation", child.isAlive)

        assertEquals("one child was ended", 1, registry.cancelAll())
        assertTrue("and it must be destroyed", child.destroyed)
    }

    @Test
    fun `cancelling nothing is not an error`() {
        val registry = ProbeChildRegistry()
        assertEquals(0, registry.cancelAll())
        assertEquals(0, registry.cancelAll())
    }

    /**
     * Racing normal completion must be harmless.
     *
     * A capture that finished a moment before the cancellation has already removed its
     * child, and cancelling something that has just succeeded is not a failure.
     */
    @Test
    fun `cancelling after the capture already finished ends nothing`() {
        val registry = ProbeChildRegistry()
        val child = StalledProcess()
        registry.add(child)
        registry.remove(child)

        assertEquals("nothing left to end", 0, registry.cancelAll())
        assertFalse("and the finished child is not touched", child.destroyed)
    }

    // ------------------------------------------------------------------ end to end

    /**
     * The whole path, with a child that produces nothing and never exits on its own.
     *
     * Before the fix this could only end when the service was torn down. The assertions are
     * the four things that must now hold: the child was genuinely running and the pumps
     * genuinely parked; cancellation ended it; the producer finished; and the reader was
     * released with a typed failure rather than a short success.
     */
    @Test
    fun `cancelling a stalled capture kills the child and releases everyone`() {
        val registry = ProbeChildRegistry()
        val child = StalledProcess()
        registry.add(child)

        val out = BlockingTestPipe()
        val err = BlockingTestPipe()
        val status = BlockingTestPipe()

        val producerDone = CountDownLatch(1)
        val producer = Thread({
            try {
                ProbeProcessStreamer().stream(
                    child, out.sink, err.sink, status.sink, System.currentTimeMillis(),
                )
            } finally {
                registry.remove(child)
                producerDone.countDown()
            }
        }, "test-producer")
        producer.isDaemon = true
        producer.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var result: ProbeStreamResult? = null
        val consumer = scope.launch {
            result = ProbeStreamReader.consume(out.source, err.source, status.source)
        }

        // The stall has to be real before anything is cancelled: both pumps parked on a child
        // with nothing to give, and a reader parked on the empty payload pipe.
        awaitParked("battinsight-probe-output")
        awaitParked("battinsight-probe-error")
        awaitBlockedReader(out)

        assertTrue("the child must be alive before cancellation", child.isAlive)
        assertEquals("and tracked", 1, registry.size)
        assertEquals("the producer must not have finished on its own", 1L, producerDone.count)

        // The cancellation itself. Nothing else happens between here and the assertions.
        assertEquals("cancellation ends exactly this child", 1, registry.cancelAll())

        assertTrue(
            "the producer must finish because the child was ended",
            producerDone.await(BOUND_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue("the child must not be left running", child.destroyed)
        assertFalse("and must report itself dead", child.isAlive)
        assertEquals("nothing left tracked", 0, registry.size)

        runBlocking { withTimeout(BOUND_MS) { consumer.join() } }

        val completion = requireNotNull(result).completion
        assertTrue(
            "a killed capture is a typed failure, never a short success: " + completion,
            completion is ProbeCompletion.Missing ||
                (completion is ProbeCompletion.Complete && completion.failure.isNotEmpty()),
        )

        assertEquals(
            "no pump thread may outlive the capture",
            emptyList<String>(),
            liveThreadNames("battinsight-probe"),
        )
        listOf(out, err, status).forEach { it.close() }
    }

    /**
     * A capture that is producing normally must not be disturbed by the new mechanism.
     *
     * The registry is emptied by the streamer when the child exits, so a cancellation that
     * arrives afterwards finds nothing. Without this the fix could quietly start killing
     * healthy captures.
     */
    @Test
    fun `a capture that completes normally leaves nothing for cancellation to end`() {
        val registry = ProbeChildRegistry()
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val child = CompletingProcess(payload, exit = 0)
        registry.add(child)

        val out = BlockingTestPipe()
        val err = BlockingTestPipe()
        val status = BlockingTestPipe()

        Thread({
            try {
                ProbeProcessStreamer().stream(
                    child, out.sink, err.sink, status.sink, System.currentTimeMillis(),
                )
            } finally {
                registry.remove(child)
            }
        }, "test-producer").also { it.isDaemon = true }.start()

        val result = runBlocking {
            withTimeout(BOUND_MS) {
                ProbeStreamReader.consume(out.source, err.source, status.source)
            }
        }

        assertEquals("the payload is intact", payload.size, result.stdout.size)
        val completion = result.completion
        assertTrue(completion is ProbeCompletion.Complete)
        assertEquals("", (completion as ProbeCompletion.Complete).failure)
        assertEquals("nothing is left for a cancellation to find", 0, registry.cancelAll())
        assertFalse("and the healthy child was never destroyed", child.destroyed)
    }

    // ------------------------------------------------------------------ helpers

    private fun awaitParked(threadName: String) {
        val deadline = System.nanoTime() + BOUND_NANOS
        while (System.nanoTime() < deadline) {
            val parked = Thread.getAllStackTraces().keys.any {
                it.name == threadName && it.state == Thread.State.WAITING
            }
            if (parked) return
            Thread.yield()
        }
        throw AssertionError("$threadName never parked")
    }

    private fun awaitBlockedReader(pipe: BlockingTestPipe) {
        val deadline = System.nanoTime() + BOUND_NANOS
        while (System.nanoTime() < deadline) {
            if (pipe.blockedReaders > 0) return
            Thread.yield()
        }
        throw AssertionError("no reader ever parked on the payload pipe")
    }

    private fun liveThreadNames(prefix: String): List<String> {
        val deadline = System.nanoTime() + BOUND_NANOS
        while (System.nanoTime() < deadline) {
            val alive = Thread.getAllStackTraces().keys
                .filter { it.name.startsWith(prefix) && it.isAlive }
                .map { it.name }
            if (alive.isEmpty()) return emptyList()
            Thread.yield()
        }
        return Thread.getAllStackTraces().keys
            .filter { it.name.startsWith(prefix) && it.isAlive }
            .map { it.name }
    }

    /**
     * A child that starts, stays alive, produces nothing, and never exits on its own.
     *
     * Its streams are real blocking pipes with no writer, so a pump reading them parks
     * exactly as it would against a wedged `dumpsys`. Being destroyed closes them, which is
     * what the kernel does when a real process dies, so the pumps are released the same way.
     */
    private class StalledProcess : Process() {
        private val stdout = BlockingTestPipe()
        private val stderr = BlockingTestPipe()
        private val exited = CountDownLatch(1)

        @Volatile
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = stdout.source
        override fun getErrorStream(): InputStream = stderr.source
        override fun exitValue(): Int =
            if (destroyed) KILLED else throw IllegalThreadStateException()

        override fun isAlive(): Boolean = !destroyed

        override fun waitFor(): Int {
            exited.await()
            return KILLED
        }

        override fun destroy() {
            destroyed = true
            // A dead process closes its pipes; the pumps must be released the same way.
            stdout.close()
            stderr.close()
            exited.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        private companion object {
            const val KILLED = 137
        }
    }

    /** A child that produces its payload and exits, standing in for a healthy capture. */
    private class CompletingProcess(payload: ByteArray, private val exit: Int) : Process() {
        private val stdout = java.io.ByteArrayInputStream(payload)
        private val stderr = java.io.ByteArrayInputStream(ByteArray(0))

        @Volatile
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun exitValue(): Int = exit
        override fun isAlive(): Boolean = false
        override fun waitFor(): Int = exit

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private companion object {
        const val BOUND_MS = 30_000L
        const val BOUND_NANOS = 30_000_000_000L
    }
}

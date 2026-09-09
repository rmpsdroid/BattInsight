package com.rmpsdroid.battinsight.shizuku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The test double gets its own tests, because the transport tests are only as trustworthy
 * as the pipe they run over.
 *
 * That is not a formality here. The previous double hung one run in six and pointed the blame
 * at production code; the one before that quietly removed the property the cancellation test
 * was supposed to prove, and passed. Both failures were in the stand-in for a kernel pipe, and
 * neither would have survived the seven assertions below.
 *
 * **Blocking is observed, never timed.** Each test that needs a thread to be blocked waits for
 * it to actually reach [Thread.State.WAITING] inside the pipe's monitor, then makes the state
 * change that should release it. Nothing concludes "it must have blocked by now" from a sleep,
 * because a machine under load would make that conclusion false.
 */
class BlockingTestPipeTest {

    // ------------------------------------------------------------------ exact bytes

    @Test
    fun `bytes written are read back exactly, across the capacity boundary`() {
        val pipe = BlockingTestPipe(capacity = 1024)
        val payload = pattern(1024 * 7 + 13) // several wraps, ending mid-buffer

        val writer = daemon("writer") {
            pipe.sink.write(payload)
            pipe.sink.close()
        }

        val read = pipe.source.readBytes()
        writer.join()

        assertEquals(payload.size, read.size)
        assertArrayEquals("order preserved, nothing lost or duplicated", payload, read)
    }

    @Test
    fun `a large stream survives many wraps of a small buffer`() {
        val pipe = BlockingTestPipe(capacity = 4096)
        val payload = pattern(1024 * 1024)

        val writer = daemon("writer") {
            pipe.sink.write(payload)
            pipe.sink.close()
        }

        val read = pipe.source.readBytes()
        writer.join()

        assertArrayEquals(payload, read)
    }

    // ------------------------------------------------------------------ end of stream

    @Test
    fun `closing the writer gives end of stream only after the buffered bytes drain`() {
        val pipe = BlockingTestPipe(capacity = 1024)
        pipe.sink.write(byteArrayOf(1, 2, 3))
        pipe.sink.close()

        val first = ByteArray(3)
        assertEquals("buffered bytes are still delivered", 3, pipe.source.read(first, 0, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), first)
        assertEquals("and only then end of stream", -1, pipe.source.read(ByteArray(1), 0, 1))
        assertEquals("which is stable", -1, pipe.source.read(ByteArray(1), 0, 1))
    }

    @Test
    fun `a reader already blocked wakes at end of stream when the writer closes`() {
        val pipe = BlockingTestPipe(capacity = 1024)
        val result = AtomicReference<Int>()
        val started = CountDownLatch(1)

        val reader = daemon("reader") {
            started.countDown()
            result.set(pipe.source.read(ByteArray(16), 0, 16))
        }
        started.await()
        awaitBlocked(reader)

        pipe.sink.close()
        reader.join(JOIN_MS)

        assertTrue("the reader must not still be blocked", !reader.isAlive)
        assertEquals("and must see end of stream", -1, result.get())
    }

    // ------------------------------------------------------------------ closing the reader

    /**
     * The property `java.io.PipedInputStream` does not have, and the one cancellation needs.
     */
    @Test
    fun `closing the reader wakes a reader already blocked in read`() {
        val pipe = BlockingTestPipe(capacity = 1024)
        val failure = AtomicReference<Throwable>()
        val started = CountDownLatch(1)

        val reader = daemon("reader") {
            started.countDown()
            try {
                pipe.source.read(ByteArray(16), 0, 16)
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        started.await()
        awaitBlocked(reader)

        pipe.source.close()
        reader.join(JOIN_MS)

        assertTrue("the reader must not still be blocked", !reader.isAlive)
        assertTrue("and must fail rather than return", failure.get() is IOException)
    }

    @Test
    fun `closing the reader wakes a writer blocked on a full buffer`() {
        val pipe = BlockingTestPipe(capacity = 256)
        val failure = AtomicReference<Throwable>()
        val started = CountDownLatch(1)

        val writer = daemon("writer") {
            started.countDown()
            try {
                pipe.sink.write(ByteArray(4096)) // four times the capacity: must block
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        started.await()
        awaitBlocked(writer)

        pipe.source.close()
        writer.join(JOIN_MS)

        assertTrue("the writer must not still be blocked", !writer.isAlive)
        assertTrue("and must fail once its reader has gone", failure.get() is IOException)
    }

    // ------------------------------------------------------------------ back-pressure

    /**
     * The property the deadlock proof rests on: a writer with no reader really does stop.
     *
     * Without it, `a child that fills standard error first does not deadlock the producer`
     * could never fail, because its child would run to completion regardless of who was
     * draining what.
     */
    @Test
    fun `a full buffer blocks the writer until a reader takes bytes out`() {
        val capacity = 256
        val pipe = BlockingTestPipe(capacity)
        val done = CountDownLatch(1)
        val started = CountDownLatch(1)

        val writer = daemon("writer") {
            started.countDown()
            pipe.sink.write(ByteArray(capacity * 3))
            done.countDown()
        }
        started.await()
        awaitBlocked(writer)

        assertEquals("exactly the capacity is buffered", capacity, pipe.source.available())
        assertEquals("and the writer has not finished", 1L, done.count)

        // Draining is what releases it -- nothing else was going to.
        val drained = ByteArray(capacity * 3)
        var read = 0
        while (read < drained.size) {
            val n = pipe.source.read(drained, read, drained.size - read)
            if (n <= 0) break
            read += n
        }

        assertTrue("the writer must complete once drained", done.await(JOIN_MS, TimeUnit.MILLISECONDS))
        assertEquals(capacity * 3, read)
    }

    // ------------------------------------------------------------------ idempotence

    @Test
    fun `closing repeatedly, in any order, is safe`() {
        val pipe = BlockingTestPipe(capacity = 64)
        pipe.sink.close()
        pipe.sink.close()
        pipe.source.close()
        pipe.source.close()
        pipe.close()
        pipe.close()

        // And a closed reader keeps refusing rather than pretending to be at end of stream.
        assertThrows(IOException::class.java) { pipe.source.read(ByteArray(1), 0, 1) }
    }

    @Test
    fun `a zero length read is not treated as end of stream`() {
        val pipe = BlockingTestPipe(capacity = 64)
        assertEquals(0, pipe.source.read(ByteArray(0), 0, 0))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Waits until the thread is genuinely parked inside the pipe's monitor.
     *
     * An observation, not an assumption: it returns when the thread reports
     * [Thread.State.WAITING], and fails if it never does. A sleep long enough to "probably"
     * be blocked would be exactly the reasoning that made the previous gate unreliable.
     */
    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + BLOCKED_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.WAITING) return
            Thread.yield()
        }
        throw AssertionError("thread ${thread.name} never blocked; state is ${thread.state}")
    }

    private fun daemon(name: String, body: () -> Unit): Thread =
        Thread(body, "pipe-test-$name").apply {
            isDaemon = true
            start()
        }

    private fun pattern(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var value = 1
        for (i in 0 until size) {
            value = value * 1_103_515_245 + 12_345
            bytes[i] = (value ushr 16).toByte()
        }
        return bytes
    }

    private companion object {
        const val JOIN_MS = 30_000L

        /** Generous: it bounds a failure, and is never waited out on the passing path. */
        const val BLOCKED_TIMEOUT_NANOS = 30_000_000_000L
    }
}

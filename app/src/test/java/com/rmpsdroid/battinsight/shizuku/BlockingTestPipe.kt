package com.rmpsdroid.battinsight.shizuku

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A blocking byte pipe with a fixed capacity, for the transport tests. Test-only.
 *
 * ## Why this exists rather than a JDK pipe
 *
 * The transport tests need a stand-in for the kernel pipe that
 * `ParcelFileDescriptor.createPipe()` gives production. Two JDK types look like candidates and
 * each is wrong in its own way, which is what this class exists to record as much as to fix.
 *
 * `java.nio.channels.Pipe` is socket-backed on some platforms, and there it intermittently
 * fails to deliver end-of-file to an already-blocked reader after the writer has closed:
 * measured at two hangs in twelve isolated runs, with the producer thread `TERMINATED`, both
 * sinks closed and no pump alive. A gate that hangs one run in six accuses the wrong component.
 *
 * `java.io.PipedInputStream` is deterministic about end-of-file but its read loop never checks
 * `closedByReader`, so a reader already blocked inside `read()` is **not** woken by another
 * thread closing it. That is exactly the mechanism `ProbeStreamReader`'s cancellation guard
 * relies on -- cancel, close the descriptors, let the blocked reads fail -- so under that
 * double the cancellation test passes for the wrong reason. Swapping to it made the suite
 * green by removing the property under test, which is worse than a flake.
 *
 * ## The properties this models, and why each one is needed
 *
 * | Property | Which test depends on it |
 * |---|---|
 * | finite capacity, writer blocks when full | the stderr-first deadlock proof: a child must be able to block |
 * | reader consumption releases the writer | every payload larger than the buffer |
 * | writer close, then EOF once drained | every completion, and the truncation flag |
 * | writer close wakes a blocked reader | a remote that dies mid-capture |
 * | reader close wakes a blocked reader | cancellation reaching the privileged child |
 * | reader close wakes a blocked writer | cancellation, from the producer's side |
 * | exact bytes, in order | every byte-for-byte assertion |
 *
 * Nothing here sleeps, polls or times out: every wait is released by the state change that
 * makes it wrong to keep waiting. A test double that needed a timeout to make progress could
 * not tell a deadlock from a slow machine, which is the confusion this replaces.
 *
 * Deliberately **not** in production sources. Production uses real descriptors, and the
 * Binder path is proven on a device by `ProbeStreamBinderTest`.
 */
class BlockingTestPipe(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "a pipe with no capacity could never make progress" }
    }

    /** Circular, so a long stream costs one buffer rather than growing without bound. */
    private val buffer = ByteArray(capacity)
    private var head = 0
    private var available = 0

    private var writerClosed = false
    private var readerClosed = false

    /**
     * How many readers are parked waiting for bytes right now.
     *
     * Exposed so a test can wait for a reader to be *genuinely* blocked before making the
     * state change that should release it. Without it a test can only guess, and a guess that
     * is wrong under load is how a mutation slips through: the consumer had not started
     * reading yet, so cancelling it proved nothing.
     */
    private var readersWaiting = 0

    private val lock = Object()

    /** See [readersWaiting]. Safe to poll from another thread. */
    val blockedReaders: Int get() = synchronized(lock) { readersWaiting }

    /** The reading half. Closing it wakes anything blocked on this pipe. */
    val source: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = readInto(b, off, len)

        override fun available(): Int = synchronized(lock) { available }

        override fun close() = closeReader()
    }

    /** The writing half. Closing it is what eventually gives the reader end-of-file. */
    val sink: OutputStream = object : OutputStream() {
        override fun write(b: Int) = writeFrom(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) = writeFrom(b, off, len)

        override fun close() = closeWriter()
    }

    /** Closes both halves. Safe to call repeatedly, and safe if never opened. */
    fun close() {
        closeWriter()
        closeReader()
    }

    // ----------------------------------------------------------------------------- internals

    private fun readInto(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(lock) {
            while (true) {
                if (readerClosed) throw IOException("the reading half is closed")
                if (available > 0) break
                // Nothing buffered. Either the writer has finished, in which case this is a
                // genuine end of stream, or somebody is still going to write.
                if (writerClosed) return -1
                readersWaiting++
                try {
                    lock.wait()
                } finally {
                    readersWaiting--
                }
            }
            val n = minOf(len, available)
            var copied = 0
            while (copied < n) {
                val runLength = minOf(n - copied, capacity - head)
                System.arraycopy(buffer, head, b, off + copied, runLength)
                head = (head + runLength) % capacity
                copied += runLength
            }
            available -= n
            // Room has appeared, so a writer waiting for space can proceed.
            lock.notifyAll()
            return n
        }
    }

    private fun writeFrom(b: ByteArray, off: Int, len: Int) {
        var written = 0
        synchronized(lock) {
            while (written < len) {
                while (available == capacity) {
                    // Full. Only a reader taking bytes out, or either half closing, changes
                    // that -- so this waits for exactly those, and never for a clock.
                    if (readerClosed) throw IOException("the reading half is closed")
                    if (writerClosed) throw IOException("the writing half is closed")
                    lock.wait()
                }
                if (readerClosed) throw IOException("the reading half is closed")
                if (writerClosed) throw IOException("the writing half is closed")

                val n = minOf(len - written, capacity - available)
                var copied = 0
                while (copied < n) {
                    val tail = (head + available + copied) % capacity
                    val runLength = minOf(n - copied, capacity - tail)
                    System.arraycopy(b, off + written + copied, buffer, tail, runLength)
                    copied += runLength
                }
                available += n
                written += n
                // Bytes have appeared, so a reader waiting for data can proceed.
                lock.notifyAll()
            }
        }
    }

    private fun closeWriter() {
        synchronized(lock) {
            if (writerClosed) return
            writerClosed = true
            // A reader blocked on an empty buffer is now at the end of the stream, and a
            // writer blocked on a full one has nowhere left to go. Both must be released.
            lock.notifyAll()
        }
    }

    private fun closeReader() {
        synchronized(lock) {
            if (readerClosed) return
            readerClosed = true
            // Whatever is still buffered will never be read, and anyone blocked on either
            // half must fail rather than wait for a reader that has gone.
            available = 0
            lock.notifyAll()
        }
    }

    companion object {
        /** Comparable to a kernel pipe buffer, so back-pressure arrives at a realistic point. */
        const val DEFAULT_CAPACITY = 64 * 1024
    }
}

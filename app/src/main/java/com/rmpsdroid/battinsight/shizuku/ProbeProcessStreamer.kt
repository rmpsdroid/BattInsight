package com.rmpsdroid.battinsight.shizuku

import com.rmpsdroid.battinsight.collection.CaptureLimits
import java.io.InputStream
import java.io.OutputStream

/**
 * The producing half of the streaming contract: moves one process onto three pipes.
 *
 * Deliberately free of Android types. The privileged half of this application is the part
 * that is hardest to test -- it runs in a process this application does not own, reachable
 * only through a Binder that a device has to authorise -- so everything about it that *can*
 * be ordinary Java is. What remains Android-specific in [ProbeService] is creating the pipes
 * and putting them in a Bundle; all of the behaviour that the Phase 10A investigation found
 * to matter (ordering, truncation, concurrent draining, killing the child, reporting how it
 * ended) lives here and is exercised on the JVM at several megabytes.
 *
 * ## Peak memory
 *
 * This side never holds the payload. Each stream is copied through one fixed buffer, so the
 * privileged process costs the same whether the capture is 8 KB or 8 MB. That is **not** a
 * claim about the pipeline as a whole: the application still materialises the finished stdout
 * once for the decoder, which takes a `ByteArray`. See [CaptureLimits] for the honest figure.
 */
class ProbeProcessStreamer(private val limit: Int = CaptureLimits.MAX_CAPTURE_BYTES) {

    /**
     * Streams a running process to completion, then reports how it ended.
     *
     * Blocks until the child has been reaped, both payload pipes have been closed, and the
     * completion frame has been written. The caller runs this off the Binder thread so the
     * transaction that established the pipes returns immediately.
     *
     * Closes all four streams it is given. The payload sinks are closed as soon as their
     * pump finishes, which is what gives the reader its end-of-file; the status sink is
     * closed last, after the frame.
     */
    fun stream(
        process: Process,
        stdoutSink: OutputStream,
        stderrSink: OutputStream,
        statusSink: OutputStream,
        startedAtMillis: Long,
    ) {
        // A pump that cannot write has lost its reader: the caller cancelled, or went away.
        // The child must not be left behind writing into a pipe nobody drains, and neither
        // must waitFor below be left blocked on a child that can never finish -- so the
        // failing pump kills it directly rather than setting a flag someone else inspects.
        val kill = { runCatching { process.destroyForcibly() }; Unit }

        val out = Pump("output", process.inputStream, stdoutSink, limit, kill)
        val err = Pump("error", process.errorStream, stderrSink, limit, kill)
        out.start()
        err.start()

        var hasExit = false
        var exit = 0
        var failure = ""
        try {
            exit = process.waitFor()
            hasExit = true
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            kill()
            failure = "the privileged execution was interrupted"
        }

        // Only after joining are the byte counts and truncation flags settled, and only then
        // has the reader seen end-of-file on both payload pipes. Reporting completion before
        // that would let a reader believe a stream had ended when bytes were still in flight.
        out.join()
        err.join()

        if (failure.isEmpty()) {
            failure = out.failure ?: err.failure ?: ""
        }

        runCatching {
            ProbeStreamProtocol.writeCompletion(
                statusSink,
                ProbeCompletion.Complete(
                    hasExitCode = hasExit,
                    exitCode = exit,
                    stdoutTruncated = out.truncated,
                    stderrTruncated = err.truncated,
                    durationMillis = System.currentTimeMillis() - startedAtMillis,
                    stdoutByteCount = out.byteCount,
                    stderrByteCount = err.byteCount,
                    failure = failure,
                ),
            )
        }
        runCatching { statusSink.close() }
    }

    /**
     * One stream, drained on its own thread.
     *
     * Both pumps run concurrently because a child may fill either pipe first, and a child
     * writing to a full pipe is suspended until somebody reads. Draining one to its end
     * before starting the other -- the shape this code had before Phase 10A.3, on both
     * backends -- deadlocks against any command that writes enough to standard error before
     * finishing standard output.
     */
    private class Pump(
        private val label: String,
        private val source: InputStream,
        private val sink: OutputStream,
        private val limit: Int,
        private val onLostReader: () -> Unit,
    ) : Thread("battinsight-probe-" + label) {

        @Volatile
        var byteCount: Long = 0

        @Volatile
        var truncated: Boolean = false

        /** Set only when the stream ended abnormally. Diagnostic; never payload. */
        @Volatile
        var failure: String? = null

        override fun run() {
            try {
                val report = CaptureLimits.copyBounded(source, sink, limit)
                byteCount = report.byteCount
                truncated = report.truncated
                sink.flush()
                if (report.truncated) {
                    // The ceiling stopped us with output still pending. Leaving the child
                    // running would leave it blocked on a pipe that is no longer drained.
                    onLostReader()
                }
            } catch (t: Throwable) {
                failure = "the standard " + label + " stream ended early"
                onLostReader()
            } finally {
                runCatching { source.close() }
                // Closing the sink is what gives the reader end-of-file, so it happens on
                // every path including failure -- a reader must never be left waiting for
                // bytes from a pump that has already given up.
                runCatching { sink.close() }
            }
        }
    }
}

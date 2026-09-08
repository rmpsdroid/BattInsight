package com.rmpsdroid.battinsight.shizuku

import com.rmpsdroid.battinsight.collection.CaptureLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** One streamed execution, as the application received it. */
class ProbeStreamResult(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val truncated: Boolean,
    val completion: ProbeCompletion,
)

/**
 * The consuming half of the streaming contract.
 *
 * Free of Android types for the same reason [ProbeProcessStreamer] is: it lets the ordering
 * and cancellation behaviour be tested deterministically on the JVM against real pipes, at
 * sizes no Binder would have carried.
 */
object ProbeStreamReader {

    /**
     * Drains one streamed execution and reports what arrived.
     *
     * Closes all three streams. stdout and stderr are read in parallel so that this side
     * makes no assumption about the order the producer writes them in; the deadlock that
     * concurrency genuinely prevents is on the producer's side of the pipes, and
     * [ProbeStreamProtocol] says so rather than crediting this method with it. The completion
     * frame is read afterwards, because the producer only writes it once both payload streams
     * have ended.
     *
     * ## Cancellation
     *
     * A blocking read on a pipe does not observe coroutine cancellation, so cancelling would
     * otherwise leave this suspended until the remote happened to finish -- and leave the
     * privileged child running behind it. A guard child therefore sits in the same scope
     * doing nothing but waiting to be cancelled; when it is, it closes the descriptors. The
     * blocked reads fail immediately, the producer's writes fail in turn, and the producer
     * kills the child. Cancellation propagates all the way to the privileged process without
     * anything waiting for a timeout.
     */
    suspend fun consume(
        stdout: InputStream,
        stderr: InputStream,
        status: InputStream,
        limit: Int = CaptureLimits.MAX_CAPTURE_BYTES,
    ): ProbeStreamResult = coroutineScope {
        val guard = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                // Closing an already-closed stream does nothing, so this is safe on the
                // ordinary path too, where the guard is cancelled after every read is done.
                runCatching { stdout.close() }
                runCatching { stderr.close() }
                runCatching { status.close() }
            }
        }
        try {
            // Captured rather than thrown. A read that fails is one of the outcomes this
            // contract is for -- the remote died, or the caller cancelled and the guard
            // closed the descriptor underneath it -- and both deserve a typed answer rather
            // than an exception escaping through a coroutine that was already being torn
            // down. An async that never fails also cannot cancel its sibling behind us.
            val outDeferred = async(Dispatchers.IO) {
                runCatching { CaptureLimits.readBoundedAndClose(stdout, limit) }
            }
            val errDeferred = async(Dispatchers.IO) {
                runCatching { CaptureLimits.readBoundedAndClose(stderr, limit) }
            }
            val out = outDeferred.await().getOrNull()
            val err = errDeferred.await().getOrNull()

            if (out == null || err == null) {
                // One of the payload streams broke. Whatever arrived is of unknown
                // completeness, and the completion frame will never come -- the producer
                // writes it only after both streams end normally -- so waiting for it would
                // block on a pipe nobody is going to write to.
                runCatching { status.close() }
                return@coroutineScope ProbeStreamResult(
                    stdout = ByteArray(0),
                    stderr = ByteArray(0),
                    truncated = false,
                    completion = ProbeCompletion.Missing(
                        "a capture stream could not be read to its end",
                    ),
                )
            }

            val completion = withContext(Dispatchers.IO) {
                status.use { ProbeStreamProtocol.readCompletion(it) }
            }

            val remoteTruncated = (completion as? ProbeCompletion.Complete)
                ?.let { it.stdoutTruncated || it.stderrTruncated } ?: false

            ProbeStreamResult(
                stdout = out.bytes,
                stderr = err.bytes,
                // Either side may be the one that stopped early: the producer at its ceiling,
                // or this side at its own. Both mean the payload is a prefix.
                truncated = out.truncated || err.truncated || remoteTruncated,
                completion = completion,
            )
        } finally {
            guard.cancel()
        }
    }
}

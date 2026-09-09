package com.rmpsdroid.battinsight.shizuku

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * How one privileged execution reports that it finished, and how it finished.
 *
 * A stream has no natural place to put an exit code: the payload ends at end-of-file, and
 * end-of-file is also what a killed process produces. [Missing] is therefore not an error
 * case bolted on afterwards -- it is the whole reason completion is framed explicitly.
 */
sealed interface ProbeCompletion {

    /** The remote reported a finished execution. */
    data class Complete(
        val hasExitCode: Boolean,
        val exitCode: Int,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
        val durationMillis: Long,
        val stdoutByteCount: Long,
        val stderrByteCount: Long,
        /** Why the remote could not finish normally. Empty when it did. Never payload. */
        val failure: String,
    ) : ProbeCompletion

    /**
     * No complete completion frame arrived.
     *
     * The remote process died, was torn down, or wrote a frame this client cannot read. In
     * every one of those cases whatever bytes did arrive are of unknown completeness, so
     * this must never be reported as a successful capture that happened to be short.
     */
    data class Missing(val reason: String) : ProbeCompletion
}

/**
 * The wire contract between [ProbeService] and [ShizukuUserServiceRunner].
 *
 * ## Why the payload no longer travels in the Binder reply
 *
 * Until Phase 10A.3 `executeProbe` returned stdout and stderr as byte arrays inside the
 * reply [android.os.Bundle], marshalled **by value**. On a Samsung SM-M156B whose
 * `dumpsys batterystats -c` had grown to 1,066,676 bytes, the reply parcel measured
 * 1,048,800 bytes and the kernel refused the transaction outright. The application then
 * decoded zero bytes and told the user "Android returned nothing at all" -- a false
 * statement about the platform, whose source was in fact perfectly healthy.
 *
 * Raising the ceiling would only move that failure to a larger device. So the reply now
 * carries **no payload at all**: it carries three [android.os.ParcelFileDescriptor]s and a
 * protocol version, and every byte of output travels through a pipe. Pipes are flow
 * controlled by the kernel, so payload size stops being a function of any transaction
 * budget.
 *
 * ## The three descriptors
 *
 * | descriptor | carries |
 * |---|---|
 * | [KEY_STDOUT_FD] | the capture itself, in order, byte for byte |
 * | [KEY_STDERR_FD] | anything the command wrote to standard error |
 * | [KEY_STATUS_FD] | exactly one completion frame, written after both of the above end |
 *
 * stdout and stderr are separate descriptors rather than one interleaved stream because
 * interleaving would have to be framed, and framing a payload means the reader trusts length
 * prefixes written by the producer in order to reconstruct it. Separate pipes keep the
 * capture byte-exact by construction.
 *
 * **The producer must drain both concurrently**, and this is where the real deadlock lives.
 * A process writing to a full pipe is suspended by the kernel until somebody reads, so a
 * producer that drains the child's stdout to its end before touching stderr waits for output
 * a child cannot produce until its stderr is drained, while the child waits for the reader
 * that will not arrive until stdout ends. Both backends had exactly that single-threaded
 * shape before Phase 10A.3 and both were corrected; a JVM test with a child on real pipes
 * pins it, and reverting either producer to a serial drain hangs that test.
 *
 * [ProbeStreamReader] reads its two pipes in parallel as well. That is not load-bearing
 * against the deadlock above -- the producer's pumps are independent, so a serial reader
 * would still finish -- and saying otherwise would overstate it. It is there so the reader
 * does not quietly depend on how the producer happens to be threaded.
 *
 * ## Completion is a frame, not an end-of-file
 *
 * The status pipe carries one fixed-layout record, written only after both payload streams
 * have ended and the child has been reaped. Its absence is meaningful: a remote that dies
 * mid-capture closes its descriptors without ever writing one, so [ProbeCompletion.Missing]
 * separates "the command finished and produced this much" from "the bytes stopped for a
 * reason nobody recorded". The frame is a few dozen bytes, far below a pipe buffer, so
 * writing it can never block.
 */
object ProbeStreamProtocol {

    /**
     * Bumped whenever the shape of the reply or the frame changes.
     *
     * Checked by the client on every call. Shizuku's own service versioning already restarts
     * a remote process whose version differs, but AIDL does not verify signatures: an older
     * remote handed the same transaction id would happily reply with the old by-value
     * Bundle, and a client that merely noticed a missing descriptor would report something
     * unhelpful. An explicit version makes a contract mismatch say what it is.
     */
    const val PROTOCOL_VERSION: Int = 3

    const val KEY_PROTOCOL = "protocolVersion"
    const val KEY_STDOUT_FD = "stdoutFd"
    const val KEY_STDERR_FD = "stderrFd"
    const val KEY_STATUS_FD = "statusFd"

    /** Set when the identifier was refused. No descriptors accompany a rejection. */
    const val KEY_REJECTION = "rejection"

    /** Short by-value output, used only by the bounded setup path. */
    const val KEY_HAS_EXIT = "hasExitCode"
    const val KEY_EXIT = "exitCode"
    const val KEY_STDOUT = "stdout"
    const val KEY_STDERR = "stderr"
    const val KEY_TRUNCATED = "truncated"
    const val KEY_DURATION = "durationMillis"

    /** Identifies a completion frame, so a truncated or foreign stream is not misread. */
    private const val MAGIC = 0x42504B31

    /** Diagnostic text only, never a payload, so it is bounded hard. */
    private const val FAILURE_LIMIT = 200

    /**
     * Writes the single completion frame.
     *
     * Callers treat a failed write as ordinary: a closed status pipe means the reader has
     * already stopped listening, which is cancellation rather than a fault.
     */
    fun writeCompletion(sink: OutputStream, completion: ProbeCompletion.Complete) {
        val failure = completion.failure.take(FAILURE_LIMIT).toByteArray(Charsets.UTF_8)
        val out = DataOutputStream(sink)
        out.writeInt(MAGIC)
        out.writeInt(PROTOCOL_VERSION)
        out.writeBoolean(completion.hasExitCode)
        out.writeInt(completion.exitCode)
        out.writeBoolean(completion.stdoutTruncated)
        out.writeBoolean(completion.stderrTruncated)
        out.writeLong(completion.durationMillis)
        out.writeLong(completion.stdoutByteCount)
        out.writeLong(completion.stderrByteCount)
        out.writeInt(failure.size)
        out.write(failure)
        out.flush()
    }

    /**
     * Reads the completion frame, or says why there is not one.
     *
     * Every abnormal ending -- an empty pipe, a half-written frame, a frame belonging to a
     * different contract -- becomes [ProbeCompletion.Missing] carrying a reason. None of them
     * can be mistaken for a completed execution.
     */
    fun readCompletion(source: InputStream): ProbeCompletion = try {
        val input = DataInputStream(source)
        val magic = input.readInt()
        if (magic != MAGIC) {
            ProbeCompletion.Missing("the remote did not report a recognisable completion")
        } else {
            val version = input.readInt()
            if (version != PROTOCOL_VERSION) {
                ProbeCompletion.Missing(
                    "the privileged service speaks stream protocol " + version +
                        ", this build speaks " + PROTOCOL_VERSION,
                )
            } else {
                val hasExit = input.readBoolean()
                val exit = input.readInt()
                val outTruncated = input.readBoolean()
                val errTruncated = input.readBoolean()
                val duration = input.readLong()
                val outBytes = input.readLong()
                val errBytes = input.readLong()
                val failureLength = input.readInt()
                if (failureLength < 0 || failureLength > FAILURE_LIMIT) {
                    ProbeCompletion.Missing("the completion frame is malformed")
                } else {
                    val failure = ByteArray(failureLength)
                    input.readFully(failure)
                    ProbeCompletion.Complete(
                        hasExitCode = hasExit,
                        exitCode = exit,
                        stdoutTruncated = outTruncated,
                        stderrTruncated = errTruncated,
                        durationMillis = duration,
                        stdoutByteCount = outBytes,
                        stderrByteCount = errBytes,
                        failure = String(failure, Charsets.UTF_8),
                    )
                }
            }
        }
    } catch (e: EOFException) {
        // The commonest abnormal ending: the remote went away part way through.
        ProbeCompletion.Missing("the privileged service ended before reporting a result")
    } catch (t: Throwable) {
        if (t is InterruptedException) Thread.currentThread().interrupt()
        ProbeCompletion.Missing("the completion could not be read: " + t.javaClass.simpleName)
    }
}

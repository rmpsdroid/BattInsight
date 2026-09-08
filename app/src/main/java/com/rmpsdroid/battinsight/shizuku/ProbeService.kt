package com.rmpsdroid.battinsight.shizuku

import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.rmpsdroid.battinsight.collection.CaptureLimits
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.setup.SetupAction
import java.util.Collections
import kotlin.system.exitProcess

/**
 * The privileged half of the Shizuku backend.
 *
 * Shizuku launches this class in a separate process running with its own identity (uid 2000
 * and `u:r:shell:s0` for an ADB-started server, though that is measured at runtime rather
 * than assumed). It loads our APK, so it can share [ProbeCommand] directly.
 *
 * ## Why this exists rather than `Shizuku.newProcess`
 *
 * `newProcess` is `private static` in the official API and its own documentation says it
 * "is planned to be removed from Shizuku API 14", directing callers to `bindUserService`
 * for anything beyond a transition from `su`. Reaching it by reflection would have made the
 * production backend depend on a private method scheduled for deletion.
 *
 * ## The security boundary crosses the Binder intact
 *
 * [executeProbe] takes a probe **identifier**, never a command. The identifier is resolved
 * against the same sealed whitelist the application uses, and anything unrecognised is
 * refused without being executed. There is no command string parameter, no shell, and no
 * interpolation: the argument vector comes from [ProbeCommand] and nothing else. Streaming
 * changed how the *output* comes back; it did not widen what may be run.
 *
 * [executeSetupAction] is the same shape but stricter, because it *changes* state. Its
 * identifier resolves to a [SetupAction], whose argument vector names BattInsight's own
 * package as a compile-time constant. There is no package parameter on the interface, so
 * this service cannot be asked to alter another application's permissions. It kept the
 * bounded by-value reply rather than inheriting the streaming path: its output is a line or
 * two, and a state-changing entry point should have the narrower mechanism, not the more
 * capable one.
 *
 * ## Output leaves through pipes, never in the reply
 *
 * See [ProbeStreamProtocol] for the contract and for the measured failure that produced it.
 * This class creates the pipes and hands the read ends back; [ProbeProcessStreamer] does the
 * work, on an ordinary thread, so the Binder transaction returns as soon as the streams
 * exist rather than being held for the length of a capture.
 *
 * This service must not use Android `Context` APIs. It runs standalone, not as a normal
 * application component.
 */
class ProbeService : IProbeService.Stub() {

    /**
     * Children currently running on behalf of a caller.
     *
     * Tracked because a child process is not a thread: it outlives the process that spawned
     * it. Without this, tearing the service down mid-capture would leave a privileged
     * `dumpsys` running with nothing attached to it, which is exactly the kind of residue a
     * non-daemon service exists to avoid.
     */
    private val live: MutableSet<Process> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Shizuku tears the service down through this.
     *
     * Any capture still in flight ends without a completion frame, which the application
     * reads as a typed failure rather than as a short but successful capture -- the
     * distinction [ProbeCompletion.Missing] exists to make.
     */
    override fun destroy() {
        synchronized(live) { live.toList() }.forEach { runCatching { it.destroyForcibly() } }
        exitProcess(0)
    }

    override fun executeProbe(probeId: String?): Bundle {
        val started = System.currentTimeMillis()

        // Resolve the identifier against the whitelist. An unknown id is refused here,
        // before any process is created and before any pipe exists.
        val command = ProbeCommand.all.firstOrNull { it.id == probeId }
            ?: return rejectedStream("unknown probe id")

        return openStream(command.argv, started)
    }

    /**
     * Performs one whitelisted setup action against BattInsight's own package.
     *
     * The resolution step is the boundary: an identifier that names no [SetupAction] is
     * refused here, before a process exists. Because the argument vector is built by
     * [SetupAction] from compile-time constants, a caller cannot influence which package is
     * affected, which permission is named, or which `pm` subcommand runs.
     */
    override fun executeSetupAction(actionId: String?): Bundle {
        val started = System.currentTimeMillis()

        val action = SetupAction.forId(actionId)
            ?: return rejectedValue("unknown setup action id", System.currentTimeMillis() - started)

        return runBounded(action.argv, started)
    }

    /**
     * Starts a whitelisted argument vector and returns the pipes it will write to.
     *
     * Returns as soon as the process exists and the streams are wired, so the Binder thread
     * is never held for the duration of a capture.
     */
    private fun openStream(argv: List<String>, started: Long): Bundle {
        var out: Array<ParcelFileDescriptor>? = null
        var err: Array<ParcelFileDescriptor>? = null
        var status: Array<ParcelFileDescriptor>? = null
        var process: Process? = null
        try {
            // index 0 is the read end, which the caller gets; index 1 is ours to write.
            out = ParcelFileDescriptor.createPipe()
            err = ParcelFileDescriptor.createPipe()
            status = ParcelFileDescriptor.createPipe()

            // Fixed argument vector from a whitelist. No shell, no interpolation.
            val child = ProcessBuilder(argv).start()
            process = child
            live.add(child)

            val stdoutSink = ParcelFileDescriptor.AutoCloseOutputStream(out[1])
            val stderrSink = ParcelFileDescriptor.AutoCloseOutputStream(err[1])
            val statusSink = ParcelFileDescriptor.AutoCloseOutputStream(status[1])

            // Our own copies of the read ends. The reply duplicates each descriptor as it is
            // marshalled, so the caller's copies are independent of these; these are closed
            // once the capture is over, which is necessarily after the reply was written.
            val ourReadEnds = listOf(out[0], err[0], status[0])

            Thread({
                try {
                    ProbeProcessStreamer().stream(child, stdoutSink, stderrSink, statusSink, started)
                } finally {
                    live.remove(child)
                    ourReadEnds.forEach { runCatching { it.close() } }
                }
            }, "battinsight-probe-stream").start()

            return Bundle().apply {
                putInt(ProbeStreamProtocol.KEY_PROTOCOL, ProbeStreamProtocol.PROTOCOL_VERSION)
                putParcelable(ProbeStreamProtocol.KEY_STDOUT_FD, out[0])
                putParcelable(ProbeStreamProtocol.KEY_STDERR_FD, err[0])
                putParcelable(ProbeStreamProtocol.KEY_STATUS_FD, status[0])
            }
        } catch (t: Throwable) {
            process?.let {
                live.remove(it)
                runCatching { it.destroyForcibly() }
            }
            listOf(out, err, status).forEach { pipe ->
                pipe?.forEach { runCatching { it.close() } }
            }
            return rejectedStream("remote execution failed: " + t.javaClass.simpleName)
        }
    }

    /**
     * Runs a short, state-changing action and returns its output by value.
     *
     * Kept separate from [openStream] on purpose. This path is bounded at
     * [CaptureLimits.MAX_MESSAGE_BYTES] -- small enough that the reply can never approach a
     * transaction budget -- so the one entry point that changes device state does not gain a
     * general streaming mechanism it has no use for.
     *
     * Both streams are read on separate threads even at this size, because which of them a
     * command fills first is not something the caller gets to know.
     */
    private fun runBounded(argv: List<String>, started: Long): Bundle {
        var process: Process? = null
        return try {
            // Fixed argument vector from a whitelist. No shell, no interpolation.
            val child = ProcessBuilder(argv).start()
            process = child
            live.add(child)

            var outCapture = ByteArray(0)
            var outTruncated = false
            val reader = Thread {
                val captured = CaptureLimits.readBoundedAndClose(
                    child.inputStream, CaptureLimits.MAX_MESSAGE_BYTES,
                )
                outCapture = captured.bytes
                outTruncated = captured.truncated
            }
            reader.start()
            val errCapture = CaptureLimits.readBoundedAndClose(
                child.errorStream, CaptureLimits.MAX_MESSAGE_BYTES,
            )
            reader.join()
            val exit = child.waitFor()
            live.remove(child)

            Bundle().apply {
                putBoolean(ProbeStreamProtocol.KEY_HAS_EXIT, true)
                putInt(ProbeStreamProtocol.KEY_EXIT, exit)
                putByteArray(ProbeStreamProtocol.KEY_STDOUT, outCapture)
                putByteArray(ProbeStreamProtocol.KEY_STDERR, errCapture.bytes)
                putBoolean(ProbeStreamProtocol.KEY_TRUNCATED, outTruncated || errCapture.truncated)
                putLong(ProbeStreamProtocol.KEY_DURATION, System.currentTimeMillis() - started)
            }
        } catch (t: Throwable) {
            process?.let {
                live.remove(it)
                runCatching { it.destroyForcibly() }
            }
            rejectedValue(
                "remote execution failed: " + t.javaClass.simpleName,
                System.currentTimeMillis() - started,
            )
        }
    }

    /** A refused streaming call. Carries a reason and, deliberately, no descriptors. */
    private fun rejectedStream(reason: String): Bundle = Bundle().apply {
        putInt(ProbeStreamProtocol.KEY_PROTOCOL, ProbeStreamProtocol.PROTOCOL_VERSION)
        putString(ProbeStreamProtocol.KEY_REJECTION, reason)
    }

    private fun rejectedValue(reason: String, durationMillis: Long): Bundle = Bundle().apply {
        putBoolean(ProbeStreamProtocol.KEY_HAS_EXIT, false)
        putByteArray(ProbeStreamProtocol.KEY_STDOUT, ByteArray(0))
        putByteArray(ProbeStreamProtocol.KEY_STDERR, reason.toByteArray())
        putBoolean(ProbeStreamProtocol.KEY_TRUNCATED, false)
        putLong(ProbeStreamProtocol.KEY_DURATION, durationMillis)
        putString(ProbeStreamProtocol.KEY_REJECTION, reason)
    }

    companion object {
        // Retained so existing callers and tests keep naming one set of keys. The protocol
        // itself lives in ProbeStreamProtocol, which both halves of the contract share.
        const val KEY_HAS_EXIT = ProbeStreamProtocol.KEY_HAS_EXIT
        const val KEY_EXIT = ProbeStreamProtocol.KEY_EXIT
        const val KEY_STDOUT = ProbeStreamProtocol.KEY_STDOUT
        const val KEY_STDERR = ProbeStreamProtocol.KEY_STDERR
        const val KEY_TRUNCATED = ProbeStreamProtocol.KEY_TRUNCATED
        const val KEY_DURATION = ProbeStreamProtocol.KEY_DURATION
        const val KEY_REJECTION = ProbeStreamProtocol.KEY_REJECTION
    }
}

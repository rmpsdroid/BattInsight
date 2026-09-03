package com.rmpsdroid.battinsight.shizuku

import android.os.Bundle
import com.rmpsdroid.battinsight.collection.ProbeCommand
import java.io.InputStream
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
 * refused without being executed. There is no command string parameter, no `sh -c`, and no
 * interpolation: the argument vector comes from [ProbeCommand] and nothing else.
 *
 * This service must not use Android `Context` APIs. It runs standalone, not as a normal
 * application component.
 */
class ProbeService : IProbeService.Stub() {

    /** Shizuku tears the service down through this. */
    override fun destroy() {
        exitProcess(0)
    }

    override fun executeProbe(probeId: String?): Bundle {
        val started = System.currentTimeMillis()

        // Resolve the identifier against the whitelist. An unknown id is refused here,
        // before any process is created.
        val command = ProbeCommand.all.firstOrNull { it.id == probeId }
            ?: return rejected(
                "unknown probe id",
                System.currentTimeMillis() - started,
            )

        var process: Process? = null
        return try {
            // Fixed argument vector from the whitelist. No shell, no interpolation.
            process = ProcessBuilder(command.argv).start()
            val stdoutCapture = process.inputStream.readBounded()
            val stderrCapture = process.errorStream.readBounded()
            val exit = process.waitFor()
            Bundle().apply {
                putBoolean(KEY_HAS_EXIT, true)
                putInt(KEY_EXIT, exit)
                putByteArray(KEY_STDOUT, stdoutCapture.bytes)
                putByteArray(KEY_STDERR, stderrCapture.bytes)
                putBoolean(KEY_TRUNCATED, stdoutCapture.truncated || stderrCapture.truncated)
                putLong(KEY_DURATION, System.currentTimeMillis() - started)
            }
        } catch (t: Throwable) {
            process?.destroyForcibly()
            rejected(
                "remote execution failed: ${t.javaClass.simpleName}",
                System.currentTimeMillis() - started,
            )
        }
    }

    private fun rejected(reason: String, durationMillis: Long): Bundle = Bundle().apply {
        putBoolean(KEY_HAS_EXIT, false)
        putByteArray(KEY_STDOUT, ByteArray(0))
        putByteArray(KEY_STDERR, reason.toByteArray())
        putBoolean(KEY_TRUNCATED, false)
        putLong(KEY_DURATION, durationMillis)
        putString(KEY_REJECTION, reason)
    }

    /** Bytes read, plus whether the ceiling stopped us before the stream ended. */
    private class Capture(val bytes: ByteArray, val truncated: Boolean)

    /**
     * Reads a stream up to a hard ceiling, reporting whether it was cut short.
     *
     * Truncation must be reported rather than silently swallowed: a payload cut off before
     * the evidence a probe is looking for would otherwise be indistinguishable from one
     * that genuinely lacked it.
     */
    private fun InputStream.readBounded(limit: Int = MAX_CAPTURE_BYTES): Capture = use { input ->
        val buffer = ByteArray(BUFFER)
        val sink = java.io.ByteArrayOutputStream(INITIAL_SINK)
        var total = 0
        var truncated = false
        while (true) {
            if (total >= limit) {
                // Something remains unread, so the capture is short.
                truncated = input.read() != -1
                break
            }
            val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            sink.write(buffer, 0, read)
            total += read
        }
        Capture(sink.toByteArray(), truncated)
    }

    companion object {
        const val KEY_HAS_EXIT = "hasExitCode"
        const val KEY_EXIT = "exitCode"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        const val KEY_TRUNCATED = "truncated"
        const val KEY_DURATION = "durationMillis"
        const val KEY_REJECTION = "rejection"

        private const val MAX_CAPTURE_BYTES = 1024 * 1024
        private const val BUFFER = 16 * 1024
        private const val INITIAL_SINK = 64 * 1024
    }
}

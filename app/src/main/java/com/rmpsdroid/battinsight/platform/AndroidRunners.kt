package com.rmpsdroid.battinsight.platform

import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream

/**
 * Runs a [ProbeCommand] in our own process, under the application UID.
 *
 * Whether anything useful comes back depends on the three permissions being granted; the
 * runner does not check that, it just reports what happened. Interpretation belongs to the
 * capability layer.
 */
class GrantedAppProcessRunner : ProcessRunner {

    override suspend fun isReady(): Boolean = true

    override suspend fun run(command: ProbeCommand, timeoutMillis: Long): ExecutionOutput =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            var process: Process? = null
            try {
                withTimeout(timeoutMillis) {
                    // Absolute paths and a fixed argument vector: no shell, no interpolation,
                    // nothing user-supplied. See ProbeCommand for why this matters.
                    val argv = listOf(BIN_PREFIX + command.argv.first()) + command.argv.drop(1)
                    val p = ProcessBuilder(argv).start().also { process = it }
                    val out = p.inputStream.readBoundedAndClose()
                    val err = p.errorStream.readBoundedAndClose()
                    val code = p.waitFor()
                    ExecutionOutput(
                        command = command,
                        exitCode = code,
                        stdout = out.bytes,
                        stderr = err.bytes,
                        durationMillis = System.currentTimeMillis() - started,
                        truncated = out.truncated || err.truncated,
                    )
                }
            } catch (t: TimeoutCancellationException) {
                process?.destroyForcibly()
                ExecutionOutput(
                    command, null, ByteArray(0), TIMEOUT_MARKER,
                    System.currentTimeMillis() - started, timedOut = true,
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    process?.destroyForcibly()
                    throw t
                }
                ExecutionOutput(
                    command, null, ByteArray(0),
                    ("exec failed: " + t.javaClass.simpleName).toByteArray(),
                    System.currentTimeMillis() - started,
                )
            }
        }

    private companion object {
        const val BIN_PREFIX = "/system/bin/"
        val TIMEOUT_MARKER = "timed out".toByteArray()
    }
}

/** Bytes read, plus whether the ceiling stopped us before the stream ended. */
internal class BoundedCapture(val bytes: ByteArray, val truncated: Boolean)

/**
 * Reads a stream with a hard ceiling, reporting whether it was cut short.
 *
 * Battery statistics checkin output is around 800 KB and there is no reason to hold more
 * than the capability layer inspects; the cap also bounds a misbehaving process. But a
 * truncated payload must never be mistaken for one that genuinely lacked the evidence a
 * probe was looking for, so truncation is reported rather than silently swallowed.
 */
internal fun InputStream.readBoundedAndClose(limit: Int = MAX_CAPTURE_BYTES): BoundedCapture =
    use { input ->
        val buffer = ByteArray(BUFFER)
        val sink = java.io.ByteArrayOutputStream(minOf(limit, INITIAL_SINK))
        var total = 0
        var truncated = false
        while (true) {
            if (total >= limit) {
                truncated = input.read() != -1
                break
            }
            val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            sink.write(buffer, 0, read)
            total += read
        }
        BoundedCapture(sink.toByteArray(), truncated)
    }

private const val MAX_CAPTURE_BYTES = 1024 * 1024
private const val BUFFER = 16 * 1024
private const val INITIAL_SINK = 64 * 1024

package com.rmpsdroid.battinsight.platform

import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import com.rmpsdroid.battinsight.shizuku.ShizukuGateway
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
                    ExecutionOutput(command, code, out, err, System.currentTimeMillis() - started)
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

/**
 * Runs a [ProbeCommand] through an ADB-started Shizuku session.
 *
 * Phase 1B measured this executing as uid 2000 in `u:r:shell:s0`, producing output
 * structurally identical to an ADB shell and needing none of our privileged permissions.
 * The identity is still measured at runtime rather than assumed.
 *
 * `Shizuku.newProcess` is reached reflectively: it is the documented way to obtain a remote
 * process but is annotated as restricted API, so reflection avoids a compile-time
 * dependency on an unstable signature. A failure here is reported, never silently ignored.
 */
class ShizukuProcessRunner(
    private val gateway: ShizukuGateway,
) : ProcessRunner {

    override suspend fun isReady(): Boolean = gateway.state().isUsable

    override suspend fun run(command: ProbeCommand, timeoutMillis: Long): ExecutionOutput =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            if (!gateway.state().isUsable) {
                return@withContext ExecutionOutput(
                    command, null, ByteArray(0), NOT_AUTHORISED,
                    System.currentTimeMillis() - started,
                )
            }
            var process: Process? = null
            try {
                withTimeout(timeoutMillis) {
                    val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java,
                    ).apply { isAccessible = true }
                    val p = method.invoke(null, command.argv.toTypedArray(), null, null) as Process
                    process = p
                    val out = p.inputStream.readBoundedAndClose()
                    val err = p.errorStream.readBoundedAndClose()
                    val code = p.waitFor()
                    ExecutionOutput(command, code, out, err, System.currentTimeMillis() - started)
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
                    ("shizuku exec failed: " + t.javaClass.simpleName).toByteArray(),
                    System.currentTimeMillis() - started,
                )
            }
        }

    private companion object {
        val NOT_AUTHORISED = "shizuku not authorised".toByteArray()
        val TIMEOUT_MARKER = "timed out".toByteArray()
    }
}

/**
 * Reads a stream with a hard ceiling.
 *
 * Battery statistics checkin output is around 800 KB and there is no reason to hold more
 * than the capability layer inspects. The cap also bounds a misbehaving process.
 */
internal fun InputStream.readBoundedAndClose(limit: Int = MAX_CAPTURE_BYTES): ByteArray = use { input ->
    val buffer = ByteArray(BUFFER)
    val sink = java.io.ByteArrayOutputStream(minOf(limit, INITIAL_SINK))
    var total = 0
    while (total < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
        if (read <= 0) break
        sink.write(buffer, 0, read)
        total += read
    }
    sink.toByteArray()
}

private const val MAX_CAPTURE_BYTES = 1024 * 1024
private const val BUFFER = 16 * 1024
private const val INITIAL_SINK = 64 * 1024

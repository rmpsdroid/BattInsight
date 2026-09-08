package com.rmpsdroid.battinsight.platform

import com.rmpsdroid.battinsight.collection.CaptureLimits
import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Runs a [ProbeCommand] in our own process, under the application UID.
 *
 * Whether anything useful comes back depends on the three permissions being granted; the
 * runner does not check that, it just reports what happened. Interpretation belongs to the
 * capability layer.
 *
 * ## No Binder, but the same completeness policy
 *
 * This backend never had the transport defect Phase 10A.3 fixed -- the child process is
 * spawned in this process, so its output never crosses a Binder. It did have the same
 * *ceiling*, declared independently at the same 1 MiB, which is how the two backends came to
 * disagree about one device: at 1,066,676 measured bytes this path returned a truncated
 * prefix and reported it honestly, while the Shizuku path failed outright and reported the
 * platform as empty. Both now read [CaptureLimits.MAX_CAPTURE_BYTES], so a payload is either
 * complete on both backends or truncated on both.
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

                    // Concurrently, not one after the other. A pipe holds a fixed amount
                    // before it blocks, so draining stdout to its end first can hang on a
                    // command that filled stderr and is waiting for room -- each side
                    // blocked on the other. The privileged backend had the identical
                    // pattern and was corrected at the same time.
                    val (out, err) = coroutineScope {
                        val outRead = async(Dispatchers.IO) {
                            CaptureLimits.readBoundedAndClose(p.inputStream)
                        }
                        val errRead = async(Dispatchers.IO) {
                            CaptureLimits.readBoundedAndClose(p.errorStream)
                        }
                        outRead.await() to errRead.await()
                    }
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

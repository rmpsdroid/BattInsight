package com.rmpsdroid.battinsight.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import com.rmpsdroid.battinsight.setup.SetupAction
import com.rmpsdroid.battinsight.setup.SetupExecutor
import com.rmpsdroid.battinsight.setup.SetupOutcome
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Executes whitelisted probes through a Shizuku **UserService**.
 *
 * This replaces the reflective `Shizuku.newProcess` route used during Phase 3 development.
 * That method is `private static` in the official API and documented as "planned to be
 * removed from Shizuku API 14", so it was not a defensible production dependency.
 * `bindUserService` is the supported mechanism the official API points to.
 *
 * ## Lifecycle
 *
 * The service is bound on demand and not run as a daemon: `daemon(false)` means Shizuku
 * tears it down when the connection drops, so no privileged process outlives the capability
 * check that needed it. Binder death, disconnection, revoked authorisation and a stopped
 * Shizuku server all surface as an unusable backend on the next refresh rather than as a
 * stale connection that appears to work.
 *
 * ## Why a binding is an object rather than two fields
 *
 * A [ServiceConnection] outlives the bind that created it: after `unbindUserService`, its
 * `onServiceDisconnected` still fires when the remote process actually goes away, which is
 * *after* a subsequent bind may already have succeeded. Measured on Android 16: a stale
 * callback wrote `null` over a freshly bound service, and the next probe reported the
 * backend unavailable while it was in fact connected.
 *
 * So each bind owns a [Binding], every callback mutates only its own, and shared state
 * changes only while that binding is still [current]. A [Mutex] serialises binding and
 * teardown, so concurrent probes share one bind instead of racing into several.
 */
class ShizukuUserServiceRunner(
    context: Context,
    private val gateway: ShizukuGateway,
) : ProcessRunner, SetupExecutor {

    private val appContext = context.applicationContext

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, ProbeService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("probe")
        .debuggable(false)
        .version(SERVICE_VERSION)

    /** One bind attempt and everything belonging to it. */
    private class Binding(val connection: ServiceConnection) {
        @Volatile
        var service: IProbeService? = null
    }

    private val bindMutex = Mutex()

    @Volatile
    private var current: Binding? = null

    /** Why the last bind produced nothing. Diagnostic only; never contains payload. */
    @Volatile
    private var lastBindError: String? = null

    override suspend fun isReady(): Boolean = gateway.state().isUsable

    override suspend fun run(command: ProbeCommand, timeoutMillis: Long): ExecutionOutput =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()

            if (!gateway.state().isUsable) {
                return@withContext failure(command, "shizuku not authorised", started)
            }

            val remote = try {
                withTimeout(BIND_TIMEOUT_MS) { obtainService() }
            } catch (t: TimeoutCancellationException) {
                return@withContext failure(command, "user service bind timed out", started)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                return@withContext failure(
                    command, "user service bind failed: " + describe(t), started,
                )
            } ?: return@withContext failure(
                command,
                "user service unavailable" + (lastBindError?.let { " (" + it + ")" } ?: ""),
                started,
            )

            var completed = false
            try {
                withTimeout(timeoutMillis) {
                    // A probe identifier crosses the Binder, never a command.
                    val bundle = remote.executeProbe(command.id)
                        ?: return@withTimeout failure(command, "no result from user service", started)

                    bundle.getString(ProbeStreamProtocol.KEY_REJECTION)?.let { reason ->
                        return@withTimeout failure(command, reason, started)
                    }

                    val output = collectStream(bundle, command, started)
                        ?: return@withTimeout failure(
                            command, "the privileged service did not open the capture streams", started,
                        )
                    completed = true
                    output
                }
            } catch (t: TimeoutCancellationException) {
                endRemoteProbe(remote)
                failure(command, "probe timed out", started, timedOut = true)
            } catch (t: android.os.DeadObjectException) {
                // The remote process died. Drop the handle so the next attempt rebinds, and
                // do not try to talk to it -- there is nothing left to cancel.
                discard()
                failure(command, "user service died", started)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    endRemoteProbe(remote)
                    throw t
                }
                failure(command, "remote execution failed: " + describe(t), started)
            } finally {
                // Anything that left the streaming block without finishing it leaves a child
                // running in the privileged process. The two paths above cover cancellation
                // and the timeout; this covers the rest, including a failure thrown between
                // the reply arriving and the streams being drained.
                if (!completed) endRemoteProbe(remote)
            }
        }

    /**
     * Tells the privileged service to end any probe it is still running.
     *
     * ## Why this is needed at all
     *
     * The producer kills its child when a pump loses its reader, but a pump only finds that
     * out when it next tries to write. A child producing nothing never gets that far, so
     * closing the descriptors -- which is all cancellation used to do -- left a privileged
     * `dumpsys` alive until the service was torn down. Phase 10A.3 measured it; R.131 records
     * it.
     *
     * ## Why it runs where it does
     *
     * [NonCancellable], because this is cleanup on the cancellation path: a cleanup that is
     * itself cancellable would be skipped exactly when it is needed. Bounded by a timeout,
     * because an unbounded Binder call in a `finally` would turn a cancelled capture into a
     * hang. Failures are swallowed: the caller is already leaving with a typed failure, and a
     * remote that cannot be reached has no child left to end anyway.
     *
     * Calling it after a capture has already finished is harmless and expected -- the child
     * is gone and the registry is empty. Cancelling something that has just succeeded must
     * not be an error.
     */
    private suspend fun endRemoteProbe(remote: IProbeService) {
        withContext(NonCancellable) {
            runCatching { withTimeout(CANCEL_TIMEOUT_MS) { remote.cancelProbe() } }
        }
    }

    /**
     * Drains one streamed capture into an [ExecutionOutput].
     *
     * ## Why a partial capture is not returned as a short success
     *
     * When the completion frame never arrives -- the privileged process died, or was torn
     * down mid-capture -- whatever bytes did arrive are of unknown completeness. They are
     * dropped and the attempt is reported as a failure rather than being handed to the
     * decoder. A prefix of checkin output parses perfectly well as far as it goes, and its
     * missing late sections would be read as sections the device does not have; the kernel
     * wakelock block sits at 84-88% of the payload, so that is the likely thing to lose.
     *
     * A remote that reported a problem gets its exit code discarded for the same reason. A
     * process that could not be run properly has no meaningful exit status, and reporting
     * one would let the classifier treat a broken execution as a clean one.
     *
     * @return null when the reply carried no descriptors at all.
     */
    private suspend fun collectStream(
        bundle: android.os.Bundle,
        command: ProbeCommand,
        started: Long,
    ): ExecutionOutput? {
        val protocol = bundle.getInt(ProbeStreamProtocol.KEY_PROTOCOL, 0)
        if (protocol != ProbeStreamProtocol.PROTOCOL_VERSION) {
            // Shizuku restarts a service whose version differs, so reaching here means that
            // did not happen. AIDL does not verify signatures, so saying what the mismatch
            // is beats reporting the missing descriptor it would otherwise look like.
            return failure(
                command,
                "the privileged service speaks stream protocol " + protocol +
                    ", this build speaks " + ProbeStreamProtocol.PROTOCOL_VERSION,
                started,
            )
        }

        val fdType = ParcelFileDescriptor::class.java
        val stdoutFd = bundle.getParcelable(ProbeStreamProtocol.KEY_STDOUT_FD, fdType)
        val stderrFd = bundle.getParcelable(ProbeStreamProtocol.KEY_STDERR_FD, fdType)
        val statusFd = bundle.getParcelable(ProbeStreamProtocol.KEY_STATUS_FD, fdType)
        if (stdoutFd == null || stderrFd == null || statusFd == null) {
            listOfNotNull(stdoutFd, stderrFd, statusFd).forEach { runCatching { it.close() } }
            return null
        }

        val result = ProbeStreamReader.consume(
            ParcelFileDescriptor.AutoCloseInputStream(stdoutFd),
            ParcelFileDescriptor.AutoCloseInputStream(stderrFd),
            ParcelFileDescriptor.AutoCloseInputStream(statusFd),
        )

        return when (val completion = result.completion) {
            is ProbeCompletion.Missing -> failure(command, completion.reason, started)
            is ProbeCompletion.Complete -> {
                val broke = completion.failure.isNotEmpty()
                ExecutionOutput(
                    command = command,
                    exitCode = if (completion.hasExitCode && !broke) completion.exitCode else null,
                    stdout = if (broke) ByteArray(0) else result.stdout,
                    stderr = if (broke) completion.failure.toByteArray() else result.stderr,
                    durationMillis = completion.durationMillis,
                    truncated = result.truncated,
                )
            }
        }
    }

    /**
     * Performs one typed setup action with shell identity.
     *
     * Shares the binding with [run], so a grant sequence does not rebind between steps. The
     * action identifier is all that crosses the Binder; the remote side rebuilds the
     * argument vector from its own copy of the whitelist.
     */
    override suspend fun execute(action: SetupAction): SetupOutcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        if (!gateway.state().isUsable) {
            return@withContext SetupOutcome.Unavailable("Shizuku is not authorised")
        }

        val remote = try {
            withTimeout(BIND_TIMEOUT_MS) { obtainService() }
        } catch (t: TimeoutCancellationException) {
            return@withContext SetupOutcome.Unavailable("user service bind timed out")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return@withContext SetupOutcome.Unavailable("user service bind failed: " + describe(t))
        } ?: return@withContext SetupOutcome.Unavailable(
            "user service unavailable" + (lastBindError?.let { " (" + it + ")" } ?: ""),
        )

        try {
            withTimeout(SETUP_TIMEOUT_MS) {
                val bundle = remote.executeSetupAction(action.id)
                    ?: return@withTimeout SetupOutcome.Refused("no result from user service")

                bundle.getString(ProbeStreamProtocol.KEY_REJECTION)?.let { reason ->
                    return@withTimeout SetupOutcome.Refused(reason)
                }

                val hasExit = bundle.getBoolean(ProbeStreamProtocol.KEY_HAS_EXIT, false)
                val stderr = bundle.getByteArray(ProbeStreamProtocol.KEY_STDERR) ?: ByteArray(0)
                val stdout = bundle.getByteArray(ProbeStreamProtocol.KEY_STDOUT) ?: ByteArray(0)
                // pm reports failures on either stream; both are short, so both are kept.
                val message = (decode(stderr) + " " + decode(stdout)).trim()
                SetupOutcome.Executed(
                    exitCode = if (hasExit) bundle.getInt(ProbeStreamProtocol.KEY_EXIT) else null,
                    message = message,
                    durationMillis = bundle.getLong(
                        ProbeStreamProtocol.KEY_DURATION,
                        System.currentTimeMillis() - started,
                    ),
                )
            }
        } catch (t: TimeoutCancellationException) {
            SetupOutcome.Unavailable("setup action timed out")
        } catch (t: android.os.DeadObjectException) {
            discard()
            SetupOutcome.Unavailable("user service died")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            SetupOutcome.Unavailable("remote execution failed: " + describe(t))
        }
    }

    /** `pm` output is short and diagnostic; bounded anyway so nothing large is ever held. */
    private fun decode(bytes: ByteArray): String =
        String(bytes, 0, minOf(bytes.size, MESSAGE_LIMIT), Charsets.UTF_8).trim()

    /**
     * Returns a live service, binding if necessary. Serialised so concurrent probes share
     * one bind rather than racing into several.
     *
     * ## Why a bind may need retrying
     *
     * Shizuku indexes client connections by the service tag, so when a previous record is
     * destroyed its teardown notification is delivered to *whatever* connection is
     * registered under that tag -- including one that was just added. Measured on Android
     * 16: binding immediately after a `remove` unbind produced `onServiceDisconnected`
     * before `onServiceConnected`, and the probe reported the backend unavailable when it
     * was merely still cleaning up.
     *
     * That disconnect belongs to the old record, not the new one, so a bounded retry is the
     * correct reading of it. It is bounded, and it is not a retry of a *failed* bind: any
     * other failure is returned immediately.
     */
    private suspend fun obtainService(): IProbeService? = bindMutex.withLock {
        current?.let { existing ->
            val live = existing.service
            if (live != null && live.asBinder().pingBinder()) return@withLock live
            // Stale or dead: tear it down before asking for another.
            unbind(existing)
        }
        repeat(BIND_ATTEMPTS) { attempt ->
            val bound = bindSuspending()
            if (bound != null) return@withLock bound
            if (lastBindError != TEARDOWN_RACE) return@withLock null
            if (attempt < BIND_ATTEMPTS - 1) kotlinx.coroutines.delay(BIND_RETRY_MS)
        }
        null
    }

    private suspend fun bindSuspending(): IProbeService? =
        suspendCancellableCoroutine { cont: CancellableContinuation<IProbeService?> ->
            lateinit var binding: Binding

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val bound = if (binder != null && binder.pingBinder()) {
                        IProbeService.Stub.asInterface(binder)
                    } else {
                        lastBindError =
                            if (binder == null) "connected with no binder" else "binder already dead"
                        null
                    }
                    // Only this binding's own state, and only while it is still the live one.
                    binding.service = bound
                    if (cont.isActive) cont.resume(bound)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // Binder death, or the Shizuku server stopping. This may arrive long
                    // after a newer bind has succeeded, so it must never touch anything but
                    // its own binding.
                    binding.service = null
                    if (current === binding) current = null
                    if (cont.isActive) {
                        lastBindError = TEARDOWN_RACE
                        cont.resume(null)
                    }
                }
            }
            binding = Binding(conn)
            current = binding
            lastBindError = null

            cont.invokeOnCancellation {
                unbind(binding)
            }

            try {
                Shizuku.bindUserService(serviceArgs, conn)
            } catch (t: Throwable) {
                lastBindError = "bindUserService threw " + describe(t)
                if (current === binding) current = null
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * Unbinds one binding and asks Shizuku to destroy the remote process.
     *
     * `remove = true` is deliberate: a privileged process must not linger past the check
     * that needed it. Clearing [current] first means a disconnect callback arriving during
     * teardown cannot resurrect or clobber anything.
     */
    private fun unbind(binding: Binding) {
        if (current === binding) current = null
        binding.service = null
        runCatching { Shizuku.unbindUserService(serviceArgs, binding.connection, true) }
    }

    /** Drops the live binding, if any. */
    private fun discard() {
        current?.let { unbind(it) }
    }

    /**
     * Releases the service. Safe to call when nothing is bound, and safe to call twice.
     *
     * A later probe rebinds rather than failing: the capability centre can be refreshed
     * after the screen that owned the runner went away.
     */
    fun release() = discard()

    private fun failure(
        command: ProbeCommand,
        reason: String,
        started: Long,
        timedOut: Boolean = false,
    ) = ExecutionOutput(
        command = command,
        exitCode = null,
        stdout = ByteArray(0),
        stderr = reason.toByteArray(),
        durationMillis = System.currentTimeMillis() - started,
        timedOut = timedOut,
    )

    /** Names a failure without leaking a payload into a message. */
    private fun describe(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.let { ": " + it.take(MESSAGE_LIMIT) } ?: "")

    private companion object {
        /**
         * Bumped when the remote contract changes, so Shizuku restarts an old process.
         *
         * Version 2 is Phase 10A.3: executeProbe keeps its transaction id but no longer
         * returns the payload by value. An unchanged version would let Shizuku reuse a
         * running version-1 process, which would answer the same transaction id with the old
         * Bundle -- AIDL verifies transaction ids, not signatures. The protocol version in
         * the reply is checked as well, so a mismatch that reached the client anyway is
         * reported as what it is rather than as a missing descriptor.
         */
        const val SERVICE_VERSION = 3
        const val BIND_TIMEOUT_MS = 15_000L

        /** `pm grant` is fast; a long wait here would only mask a stuck service. */
        const val SETUP_TIMEOUT_MS = 20_000L

        /**
         * Cancellation cleanup is a short call, and must not become a second hang.
         *
         * Generous enough for a Binder round trip that only walks a registry, short enough
         * that a wedged service cannot hold a cancelled capture open.
         */
        const val CANCEL_TIMEOUT_MS = 5_000L

        /** A disconnect that arrived before the new binding was ever used. */
        const val TEARDOWN_RACE = "disconnected before first use"

        /** Bounded: three attempts across under a second, inside the bind timeout. */
        const val BIND_ATTEMPTS = 3
        const val BIND_RETRY_MS = 300L
        const val MESSAGE_LIMIT = 120
    }
}

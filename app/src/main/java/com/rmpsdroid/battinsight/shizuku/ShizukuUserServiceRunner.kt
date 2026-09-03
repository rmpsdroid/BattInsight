package com.rmpsdroid.battinsight.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
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
) : ProcessRunner {

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

            try {
                withTimeout(timeoutMillis) {
                    // A probe identifier crosses the Binder, never a command.
                    val bundle = remote.executeProbe(command.id)
                        ?: return@withTimeout failure(command, "no result from user service", started)

                    bundle.getString(ProbeService.KEY_REJECTION)?.let { reason ->
                        return@withTimeout failure(command, reason, started)
                    }

                    val hasExit = bundle.getBoolean(ProbeService.KEY_HAS_EXIT, false)
                    ExecutionOutput(
                        command = command,
                        exitCode = if (hasExit) bundle.getInt(ProbeService.KEY_EXIT) else null,
                        stdout = bundle.getByteArray(ProbeService.KEY_STDOUT) ?: ByteArray(0),
                        stderr = bundle.getByteArray(ProbeService.KEY_STDERR) ?: ByteArray(0),
                        durationMillis = bundle.getLong(
                            ProbeService.KEY_DURATION,
                            System.currentTimeMillis() - started,
                        ),
                        truncated = bundle.getBoolean(ProbeService.KEY_TRUNCATED, false),
                    )
                }
            } catch (t: TimeoutCancellationException) {
                failure(command, "probe timed out", started, timedOut = true)
            } catch (t: android.os.DeadObjectException) {
                // The remote process died. Drop the handle so the next attempt rebinds.
                discard()
                failure(command, "user service died", started)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                failure(command, "remote execution failed: " + describe(t), started)
            }
        }

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
        /** Bumped when the remote contract changes, so Shizuku restarts an old process. */
        const val SERVICE_VERSION = 1
        const val BIND_TIMEOUT_MS = 15_000L

        /** A disconnect that arrived before the new binding was ever used. */
        const val TEARDOWN_RACE = "disconnected before first use"

        /** Bounded: three attempts across under a second, inside the bind timeout. */
        const val BIND_ATTEMPTS = 3
        const val BIND_RETRY_MS = 300L
        const val MESSAGE_LIMIT = 120
    }
}

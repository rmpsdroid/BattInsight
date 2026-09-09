package com.rmpsdroid.battinsight.harness

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.rmpsdroid.battinsight.shizuku.ProbeChildRegistry
import com.rmpsdroid.battinsight.shizuku.ProbeProcessStreamer
import com.rmpsdroid.battinsight.shizuku.ProbeStreamProtocol
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

/**
 * The remote half of the real-Binder transport test. Debug variant only; never in a release.
 *
 * Runs in its own process (see the debug manifest) so that everything the JVM tests
 * cannot reach is genuinely exercised: parcel marshalling, the kernel's transaction size
 * limit, and the duplication of file descriptors across a process boundary.
 *
 * It uses the **production** [ProbeProcessStreamer] and the production [ProbeStreamProtocol]
 * rather than a reimplementation, so what the test proves is the shipped contract. What it
 * substitutes is only the source of the bytes: a generator standing in for `dumpsys`, so the
 * payload size is chosen by the test instead of by whatever the device happens to hold.
 */
class TransportHarnessService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    /** The production registry, so the cancellation path under test is the shipped one. */
    private val probes = ProbeChildRegistry()

    @Volatile
    private var stalled: StalledProcess? = null

    private val binder = object : ITransportHarness.Stub() {

        override fun openStream(stdoutBytes: Int, stderrBytes: Int, exitCode: Int): Bundle {
            val out = ParcelFileDescriptor.createPipe()
            val err = ParcelFileDescriptor.createPipe()
            val status = ParcelFileDescriptor.createPipe()

            val process = GeneratedProcess(
                stdout = ByteArrayInputStream(TransportPayload.of(stdoutBytes, STDOUT_SEED)),
                stderr = ByteArrayInputStream(TransportPayload.of(stderrBytes, STDERR_SEED)),
                exit = exitCode,
            )

            val stdoutSink = ParcelFileDescriptor.AutoCloseOutputStream(out[1])
            val stderrSink = ParcelFileDescriptor.AutoCloseOutputStream(err[1])
            val statusSink = ParcelFileDescriptor.AutoCloseOutputStream(status[1])
            val ourReadEnds = listOf(out[0], err[0], status[0])

            Thread({
                try {
                    ProbeProcessStreamer().stream(
                        process, stdoutSink, stderrSink, statusSink, System.currentTimeMillis(),
                    )
                } finally {
                    ourReadEnds.forEach { runCatching { it.close() } }
                }
            }, "harness-stream").start()

            return Bundle().apply {
                putInt(ProbeStreamProtocol.KEY_PROTOCOL, ProbeStreamProtocol.PROTOCOL_VERSION)
                putParcelable(ProbeStreamProtocol.KEY_STDOUT_FD, out[0])
                putParcelable(ProbeStreamProtocol.KEY_STDERR_FD, err[0])
                putParcelable(ProbeStreamProtocol.KEY_STATUS_FD, status[0])
            }
        }

        /**
         * The R.131 shape: a real remote process, real descriptors, and a child that stalls.
         *
         * Mirrors `ProbeService.openStream` exactly, including registering the child with the
         * production [ProbeChildRegistry], so what the test drives is the shipped path.
         */
        override fun openStalledStream(): Bundle {
            val out = ParcelFileDescriptor.createPipe()
            val err = ParcelFileDescriptor.createPipe()
            val status = ParcelFileDescriptor.createPipe()

            val process = StalledProcess()
            stalled = process
            probes.add(process)

            val stdoutSink = ParcelFileDescriptor.AutoCloseOutputStream(out[1])
            val stderrSink = ParcelFileDescriptor.AutoCloseOutputStream(err[1])
            val statusSink = ParcelFileDescriptor.AutoCloseOutputStream(status[1])
            val ourReadEnds = listOf(out[0], err[0], status[0])

            Thread({
                try {
                    ProbeProcessStreamer().stream(
                        process, stdoutSink, stderrSink, statusSink, System.currentTimeMillis(),
                    )
                } finally {
                    probes.remove(process)
                    ourReadEnds.forEach { runCatching { it.close() } }
                }
            }, "harness-stalled-stream").start()

            return Bundle().apply {
                putInt(ProbeStreamProtocol.KEY_PROTOCOL, ProbeStreamProtocol.PROTOCOL_VERSION)
                putParcelable(ProbeStreamProtocol.KEY_STDOUT_FD, out[0])
                putParcelable(ProbeStreamProtocol.KEY_STDERR_FD, err[0])
                putParcelable(ProbeStreamProtocol.KEY_STATUS_FD, status[0])
            }
        }

        override fun cancelProbe() {
            probes.cancelAll()
        }

        override fun isStalledChildAlive(): Boolean = stalled?.isAlive ?: false

        /** Exactly what `ProbeService.run` did before Phase 10A.3, kept for the negative proof. */
        override fun returnByValue(stdoutBytes: Int): Bundle = Bundle().apply {
            putBoolean(ProbeStreamProtocol.KEY_HAS_EXIT, true)
            putInt(ProbeStreamProtocol.KEY_EXIT, 0)
            putByteArray(ProbeStreamProtocol.KEY_STDOUT, TransportPayload.of(stdoutBytes, STDOUT_SEED))
            putByteArray(ProbeStreamProtocol.KEY_STDERR, ByteArray(0))
            putBoolean(ProbeStreamProtocol.KEY_TRUNCATED, false)
            putLong(ProbeStreamProtocol.KEY_DURATION, 0L)
        }
    }

    /**
     * A child that starts, stays alive, produces nothing and never exits by itself.
     *
     * Its streams are pipes with no writer, so a pump reading them parks exactly as it would
     * against a wedged privileged command.
     */
    private class StalledProcess : Process() {
        private val stdout = ParcelFileDescriptor.createPipe()
        private val stderr = ParcelFileDescriptor.createPipe()
        private val exited = CountDownLatch(1)

        @Volatile
        private var destroyed = false

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream =
            ParcelFileDescriptor.AutoCloseInputStream(stdout[0])

        override fun getErrorStream(): InputStream =
            ParcelFileDescriptor.AutoCloseInputStream(stderr[0])

        override fun exitValue(): Int =
            if (destroyed) 137 else throw IllegalThreadStateException()

        override fun isAlive(): Boolean = !destroyed

        override fun waitFor(): Int {
            exited.await()
            return 137
        }

        override fun destroy() {
            destroyed = true
            // A dead process closes its pipes; releasing the pumps the same way.
            listOf(stdout[0], stdout[1], stderr[0], stderr[1]).forEach { runCatching { it.close() } }
            exited.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    /** A [Process] whose output the harness supplies, standing in for a privileged command. */
    private class GeneratedProcess(
        private val stdout: InputStream,
        private val stderr: InputStream,
        private val exit: Int,
        private val waitUntil: CountDownLatch = CountDownLatch(0),
    ) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun exitValue(): Int = exit
        override fun waitFor(): Int {
            waitUntil.await()
            return exit
        }

        override fun destroy() {
            waitUntil.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    companion object {
        const val STDOUT_SEED = 1
        const val STDERR_SEED = 11
    }
}

/**
 * The payload both sides generate independently.
 *
 * Regenerated rather than compared against something transferred, so an assertion of
 * equality really is an assertion that the bytes survived the journey. Deterministic and
 * non-repeating enough that a misaligned or partially-delivered copy would not compare equal.
 */
object TransportPayload {
    fun of(size: Int, seed: Int): ByteArray {
        val bytes = ByteArray(size)
        var value = seed
        for (i in 0 until size) {
            value = value * 1_103_515_245 + 12_345
            bytes[i] = (value ushr 16).toByte()
        }
        return bytes
    }
}

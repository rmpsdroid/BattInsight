package com.rmpsdroid.battinsight.shizuku

/**
 * The privileged children currently running, and the only thing that can end them early.
 *
 * ## Why this exists
 *
 * A child process is not a thread: it outlives whatever spawned it. The streaming producer
 * kills its child when a pump loses its reader, but a pump discovers that **on its next
 * write** -- so a child that is producing nothing is never noticed. Phase 10A.3 measured
 * exactly that: cancelling a stalled capture left a privileged `dumpsys` running until the
 * whole service was torn down.
 *
 * Holding the children in one place lets a cancellation end them directly instead of waiting
 * for a write that is not going to happen.
 *
 * ## Why probes and setup actions are kept apart
 *
 * The Shizuku user service is **not** capture-scoped. `ShizukuUserServiceRunner` shares one
 * binding between probes and setup actions deliberately, so a grant sequence does not rebind
 * between steps -- which means a probe and a `pm grant` can be in flight in the same process
 * at the same time.
 *
 * That is why cancelling a probe must not reach for anything blunter than this. Tearing the
 * service down would have been fewer lines and would have killed a half-finished `pm grant`
 * along with the stalled capture: a state-changing operation interrupted to clean up a
 * read-only one. Two registries keep the cancellation as narrow as the thing being cancelled.
 *
 * Deliberately free of Android types, so the behaviour that matters can be tested on the JVM
 * against a real [ProbeProcessStreamer] rather than only through a Binder on a device.
 */
class ProbeChildRegistry {

    private val children = mutableSetOf<Process>()

    /** Registers a child that has just started. */
    fun add(child: Process) {
        synchronized(children) { children.add(child) }
    }

    /** Forgets a child that has finished. Safe for one that was never added. */
    fun remove(child: Process) {
        synchronized(children) { children.remove(child) }
    }

    /** How many children are being tracked. Diagnostic and test use only. */
    val size: Int get() = synchronized(children) { children.size }

    /**
     * Ends every tracked child now, and reports how many were still running.
     *
     * Safe when nothing is tracked, which is the ordinary outcome of a race with normal
     * completion: a capture that finished a moment earlier has already removed its child, and
     * one that is finishing as this runs is destroyed after it exited, which does nothing.
     * Cancelling something that has just succeeded must not be an error.
     *
     * The snapshot is taken under the lock and the destroying is done outside it, so a child
     * whose exit removes it from this registry cannot deadlock against the caller.
     */
    fun cancelAll(): Int {
        val doomed = synchronized(children) { children.toList() }
        doomed.forEach { runCatching { it.destroyForcibly() } }
        return doomed.size
    }
}

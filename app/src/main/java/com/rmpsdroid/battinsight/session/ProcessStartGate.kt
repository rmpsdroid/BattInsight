package com.rmpsdroid.battinsight.session

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether this process has already announced itself as a fresh start.
 *
 * ## Why this exists
 *
 * [SessionTrigger.APP_START] is read by `BatterySeriesBuilder` as evidence that the process
 * died and a new one took its place. That inference is only sound if `APP_START` is emitted
 * **once per process**, and before Phase 10A.1 it was not: the sampling call ran inside
 * `repeatOnLifecycle(STARTED)`, so every background-to-foreground transition of a perfectly
 * healthy process announced itself as a fresh start. A Samsung SM-M156B held pid 31963
 * continuously while the UI told the user "BattInsight stopped running and started again".
 *
 * The Activity cannot own this state and neither can the `ViewModel`. An Activity is recreated
 * on rotation; a `ViewModel` survives that but is cleared when the Activity is finished, so a
 * back-press and relaunch inside one living process would produce a second "first" start. The
 * scope that matches the claim being made is the **process**, so that is the scope this holds.
 *
 * ## Why a process-scoped object is the right mechanism
 *
 * The distinguishing state has to disappear exactly when the process does, because that is the
 * event it describes. Static state in a loaded class does precisely that and nothing else: it
 * is created when the class is first loaded into a process and is gone when the process ends.
 *
 * Deliberately *not* used, because each would answer a different question or answer this one
 * wrongly:
 *
 *  - **Wall clock** — a clock reading cannot distinguish a restart from a long pause, and it
 *    moves for reasons that have nothing to do with this process.
 *  - **Activity or ViewModel instance identity** — both are narrower than a process, which is
 *    the whole defect being fixed.
 *  - **A persisted pid** — a pid written to storage outlives the process it describes, and pids
 *    are reused. Durable storage is the wrong medium for a fact that must expire.
 *
 * Nothing here is written to disk. There is no reset across process death to implement, because
 * process death *is* the reset.
 *
 * ## Testability
 *
 * The gate is an ordinary instance so tests can construct a fresh one to represent a fresh
 * process. [forProcess] is the single instance production uses.
 */
class ProcessStartGate {

    private val claimed = AtomicBoolean(false)

    /**
     * Claims the one "this process just started" announcement.
     *
     * Returns `true` exactly once per instance -- for production, once per process. Every
     * later call returns `false`, meaning the UI became observable again inside a process that
     * was already running.
     *
     * Atomic because the first sample can be requested from more than one coroutine on a fast
     * launch, and two callers both believing they were first is the defect this class exists
     * to prevent.
     */
    fun claimStart(): Boolean = claimed.compareAndSet(false, true)

    /** Whether the start announcement has already been made in this process. */
    val hasStarted: Boolean get() = claimed.get()

    companion object {
        /**
         * The process-scoped gate.
         *
         * Lives as long as the class stays loaded, which is as long as the process lives.
         */
        val forProcess = ProcessStartGate()
    }
}

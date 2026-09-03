package com.rmpsdroid.battinsight.shizuku;

/**
 * The privileged probe service, running inside a Shizuku UserService process.
 *
 * The security boundary of Phase 3 is preserved across the Binder: this interface takes a
 * probe *identifier*, never a command. The identifier is looked up against the same sealed
 * whitelist the application uses, and an unrecognised one is rejected. No caller -- not the
 * UI, not any other component -- can cause an arbitrary string to be executed.
 */
interface IProbeService {

    /**
     * Shizuku calls this to tear the service down. The transaction id is fixed by Shizuku
     * (IBinder.LAST_CALL_TRANSACTION - 1) and must not be changed.
     */
    void destroy() = 16777114;

    /**
     * Runs one whitelisted probe.
     *
     * @param probeId an identifier from the application's ProbeCommand whitelist. Anything
     *                else is refused without being executed.
     * @return a Bundle carrying exitCode, hasExitCode, stdout, stderr, truncated,
     *         durationMillis and, when refused, a rejection reason.
     */
    Bundle executeProbe(String probeId) = 1;
}

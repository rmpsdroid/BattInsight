package com.rmpsdroid.battinsight.shizuku;

/**
 * The privileged service, running inside a Shizuku UserService process.
 *
 * The security boundary is preserved across the Binder: both methods take an *identifier*,
 * never a command. Each identifier is looked up against a sealed whitelist that the
 * application and this service share, and an unrecognised one is rejected before any
 * process is created. No caller -- not the UI, not any other component -- can cause an
 * arbitrary string to be executed.
 */
interface IProbeService {

    /**
     * Shizuku calls this to tear the service down. The transaction id is fixed by Shizuku
     * (IBinder.LAST_CALL_TRANSACTION - 1) and must not be changed.
     */
    void destroy() = 16777114;

    /**
     * Runs one whitelisted read-only probe.
     *
     * @param probeId an identifier from the application's ProbeCommand whitelist. Anything
     *                else is refused without being executed.
     * @return a Bundle carrying exitCode, hasExitCode, stdout, stderr, truncated,
     *         durationMillis and, when refused, a rejection reason.
     */
    Bundle executeProbe(String probeId) = 1;

    /**
     * Performs one whitelisted setup action against BattInsight's own package.
     *
     * This is the only state-changing entry point in the application, so its constraints
     * are stricter than executeProbe's. The identifier resolves to a SetupAction, which
     * carries a fixed argument vector: a fixed `pm` path, `grant` or `revoke`, BattInsight's
     * own compile-time package name, and one of exactly three measured permissions.
     *
     * There is no package parameter. This interface cannot be used to change the
     * permissions of any other application, and cannot express any other pm subcommand.
     *
     * @param actionId an identifier from the application's SetupAction whitelist.
     * @return a Bundle of the same shape executeProbe returns.
     */
    Bundle executeSetupAction(String actionId) = 2;
}

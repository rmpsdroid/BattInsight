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
     * Starts one whitelisted read-only probe and hands back the streams it will write to.
     *
     * The reply carries **no payload**. It carries a protocol version and three
     * ParcelFileDescriptors -- standard output, standard error, and a completion frame --
     * and the command's bytes travel only through those pipes.
     *
     * This is the Phase 10A.3 correction. The previous contract returned stdout inside the
     * reply Bundle as a byte array, marshalled by value; on a device whose checkin output
     * had grown to 1,066,676 bytes the reply parcel measured 1,048,800 bytes and the
     * transaction was refused, which the application then misreported to the user as the
     * platform having returned nothing. A pipe is flow controlled by the kernel, so payload
     * size is no longer a function of any transaction budget.
     *
     * The transaction id is unchanged, but the reply shape is not, so SERVICE_VERSION is
     * bumped: an older remote must be restarted rather than reused. The protocol version in
     * the reply is the second line of defence, because AIDL does not verify signatures.
     *
     * @param probeId an identifier from the application's ProbeCommand whitelist. Anything
     *                else is refused without being executed, and the reply then carries a
     *                rejection reason and no descriptors.
     * @return a Bundle carrying protocolVersion, stdoutFd, stderrFd and statusFd, or a
     *         rejection reason.
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
     * Deliberately still returns its output **by value**, and deliberately did not inherit
     * the streaming contract. A setup action's entire output is a line or two from pm, far
     * below any transaction budget, and a general streaming entry point is a wider surface
     * than this method needs. Its transaction id, its argument and its semantics are all
     * unchanged by Phase 10A.3.
     *
     * @param actionId an identifier from the application's SetupAction whitelist.
     * @return a Bundle carrying exitCode, hasExitCode, stdout, stderr, truncated,
     *         durationMillis and, when refused, a rejection reason.
     */
    Bundle executeSetupAction(String actionId) = 2;

    /**
     * Ends any probe still running, because the caller has stopped listening.
     *
     * Phase 10A.3 measured the gap this closes. A probe's output travels through pipes, and
     * the producer learns that its reader has gone when its next write fails -- so a child
     * that is producing nothing is never noticed, and a cancelled capture left a privileged
     * dumpsys running until the whole service was torn down.
     *
     * A descriptor cannot carry this signal. Every pipe end handed back inside the reply
     * Bundle stays open in this process until the capture ends, because Bundle does not
     * propagate PARCELABLE_WRITE_RETURN_VALUE to the ParcelFileDescriptors it contains and
     * nothing else closes them after marshalling. A pipe with a writer that never closes
     * cannot report end of stream, so the signal has to be a call.
     *
     * Affects **probes only**. Setup actions run in this same process and may be in flight
     * at the same time, and interrupting a half-finished pm grant to clean up a read-only
     * capture would be worse than the problem being solved.
     *
     * Takes no argument, so it cannot be aimed at anything. Safe to call when nothing is
     * running, which is the ordinary outcome of racing normal completion.
     */
    void cancelProbe() = 3;
}

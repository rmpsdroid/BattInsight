package com.rmpsdroid.battinsight.collection

/**
 * The complete set of commands BattInsight is permitted to execute.
 *
 * This is a security boundary, not a convenience. The application will eventually run
 * commands with elevated identity through Shizuku, so there is deliberately **no**
 * `execute(command: String)` anywhere in the public surface: callers choose a
 * [ProbeCommand], and only this file maps one to an argument vector. UI code cannot
 * construct a command, and nothing user-supplied reaches a process.
 *
 * Every entry is read-only. Adding one is a reviewable change to this file.
 *
 * ## Commands that must never appear here
 *
 * `--checkin` writes and clears the last old completed statistics, per the platform's own
 * help text on both devices measured in Phase 1A. Use [BatteryStatsCheckinCurrent] (`-c`),
 * which only writes current statistics.
 *
 * Also permanently excluded: `--reset`, `--reset-all`, `--write`, `--new-daily`,
 * `--read-daily`, `--history-create-events`, and the `enable`/`disable` subcommands. All
 * alter the user's data or device state. `FoundationContractsTest` and
 * `ProbeCommandTest` assert their absence.
 */
sealed class ProbeCommand(
    /** Stable identifier for logging and diagnostics. */
    val id: String,
    /** The argument vector. Never a shell string; never concatenated with input. */
    val argv: List<String>,
) {
    /** Who is this process actually running as? Answered by measurement, never assumed. */
    data object Identity : ProbeCommand("id", listOf("id"))

    /** Which SELinux domain? Phase 1B showed domain, not uid, decides some access. */
    data object SelinuxIdentity : ProbeCommand("id_selinux", listOf("id", "-Z"))

    /**
     * Aggregate battery statistics as protobuf.
     *
     * The routine acquisition probe. Phase 1B measured it at roughly one ninth the size of
     * checkin (91 KB against 818 KB) and about twice as fast.
     */
    data object BatteryStatsProto :
        ProbeCommand("batterystats_proto", listOf("dumpsys", "batterystats", "--proto"))

    /**
     * Current battery statistics in checkin format.
     *
     * `-c`, never `--checkin`. Used only where a capability cannot be established from
     * protobuf without building the production decoder -- kernel wakelocks being the case
     * in Phase 3, since the `kwl` records are greppable as text.
     */
    data object BatteryStatsCheckinCurrent :
        ProbeCommand("batterystats_checkin_current", listOf("dumpsys", "batterystats", "-c"))

    companion object {
        /**
         * Every command the application may run. Used by tests to police the whitelist.
         *
         * A computed property, not an eagerly-initialised `val`. The companion initialiser
         * runs before the nested objects are constructed, so an eager list would silently
         * contain nulls -- and this is the list the safety tests check, so a null-filled
         * whitelist would make those assertions pass while proving nothing.
         */
        val all: List<ProbeCommand> get() = listOf(
            Identity,
            SelinuxIdentity,
            BatteryStatsProto,
            BatteryStatsCheckinCurrent,
        )

        /**
         * Arguments that change device or user state and must never be executed.
         *
         * Asserted against [all] by test.
         */
        val forbiddenArguments: List<String> get() = listOf(
            "--checkin",
            "--reset",
            "--reset-all",
            "--write",
            "--new-daily",
            "--read-daily",
            "--history-create-events",
            "enable",
            "disable",
        )
    }
}

/**
 * Raw result of running one [ProbeCommand]. No interpretation.
 *
 * [exitCode] is nullable because a process that never completed has no exit status, and
 * conflating "timed out" with "exited 0" would repeat the mistake this architecture exists
 * to avoid.
 */
data class ExecutionOutput(
    val command: ProbeCommand,
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val durationMillis: Long,
    val timedOut: Boolean = false,
    /**
     * Whether the capture ceiling stopped us before the stream ended.
     *
     * Carried alongside the payload because absence of evidence in a short capture is not
     * evidence of absence: the capability layer must be able to answer *unknown* rather
     * than claim a section is missing when we simply stopped reading.
     */
    val truncated: Boolean = false,
) {
    val stdoutBytes: Int get() = stdout.size
    val stderrBytes: Int get() = stderr.size

    /**
     * A bounded, decoded prefix of stdout for classification.
     *
     * Bounded deliberately: a checkin payload is ~800 KB and must not be turned into a
     * String wholesale, nor logged. Only enough to recognise a denial or a format marker.
     */
    fun stdoutHead(limit: Int = CLASSIFY_LIMIT): String =
        String(stdout, 0, minOf(stdout.size, limit), Charsets.UTF_8)

    fun stderrText(limit: Int = CLASSIFY_LIMIT): String =
        String(stderr, 0, minOf(stderr.size, limit), Charsets.UTF_8)

    /** Metadata only. Never includes payload bytes, so it is safe to log. */
    override fun toString(): String =
        "ExecutionOutput(${command.id}, exit=$exitCode, out=${stdout.size}B, " +
            "err=${stderr.size}B, ${durationMillis}ms, timedOut=$timedOut, truncated=$truncated)"

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExecutionOutput && command == other.command &&
            exitCode == other.exitCode && stdout.contentEquals(other.stdout) &&
            stderr.contentEquals(other.stderr) && timedOut == other.timedOut &&
            truncated == other.truncated)

    override fun hashCode(): Int =
        ((((command.hashCode() * 31 + (exitCode ?: 0)) * 31 + stdout.contentHashCode()) * 31 +
            stderr.contentHashCode()) * 31 + timedOut.hashCode()) * 31 + truncated.hashCode()

    companion object {
        const val CLASSIFY_LIMIT: Int = 4096
    }
}

/**
 * Runs a [ProbeCommand]. The seam that lets every capability test run without a device.
 *
 * Implementations must enforce a timeout and honour coroutine cancellation.
 */
interface ProcessRunner {
    /** Whether this runner can currently start a process at all. */
    suspend fun isReady(): Boolean

    suspend fun run(command: ProbeCommand, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): ExecutionOutput

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 20_000
    }
}

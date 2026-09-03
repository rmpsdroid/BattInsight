package com.rmpsdroid.battinsight.collection

/**
 * An acquisition format for battery statistics.
 *
 * Both structured formats were measured working on Android 10 and Android 16, from an
 * ADB shell, from an app UID holding the required permissions, and through a Shizuku
 * shell session. Their outputs were structurally equivalent in every comparison.
 *
 * No format is designated primary here. Phase 1A recommended CHECKIN on documentation and
 * CTS-coverage grounds; Phase 1B then measured PROTO at roughly one ninth the size and
 * about twice the speed. The architecture must therefore permit routine collection in one
 * format with the other available for history, diagnostics and fallback, and that choice
 * is deliberately not encoded in this type.
 */
enum class SourceFormat(
    /** The `dumpsys batterystats` argument that produces this format. */
    val dumpsysArgument: String,
    /** Whether this format is a parsing target or exists only for humans to read. */
    val isParseTarget: Boolean,
) {
    /**
     * Protocol-buffer aggregate output.
     *
     * Measured 72-92 KB against 814-819 KB for [CHECKIN], and consistently faster.
     * Note that `--proto` and `--proto --history` do not share a framing: the former is
     * length-delimited, the latter is the bare message. A decoder must not assume one.
     */
    PROTO("--proto", isParseTarget = true),

    /**
     * Comma-separated checkin output.
     *
     * The argument is `-c`, never `--checkin`. Both devices measured in Phase 1A document
     * that `--checkin` "will write (and clear) the last old completed stats when they had
     * been reset", while `-c` only writes current stats. `-c` also returns aggregate and
     * history together. Using `--checkin` is prohibited project-wide.
     */
    CHECKIN("-c", isParseTarget = true),

    /**
     * Human-readable text.
     *
     * Rejected as a parser input: 2.5-3.1 MB, no version identifier, no stability
     * guarantee, and no capability was found in it that the structured formats lack.
     * Retained only as an attachment for human reading in a diagnostic bundle.
     */
    TEXT("", isParseTarget = false),
    ;

    companion object {
        /** Formats a parser may consume. */
        val parseTargets: List<SourceFormat> get() = entries.filter { it.isParseTarget }
    }
}

package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat

/**
 * One decoded batterystats capture, normalised away from the transport that carried it.
 *
 * Deliberately **not** a mirror of the checkin line format. A model shaped like its transport
 * has to change when the transport does, and the whole reason this layer exists is so the
 * choice between checkin and proto stays a decoding decision rather than a product one.
 *
 * Equally deliberately, this holds only what Phase 7A actually verified against AOSP source
 * and measured captures. The checkin format carries around two dozen record types; four are
 * decoded here. The rest are counted as [unsupportedTags] rather than modelled speculatively,
 * because an empty typed structure invites code that reads it and believes the zeros.
 *
 * Lives in memory only. Nothing here is written to the database in Phase 7A -- the Room
 * schema stays at version 1 and continues to hold session metadata and nothing else.
 */
data class BatteryStatsCapture(
    /** Provenance and shape of the payload this was decoded from. */
    val metadata: CaptureMetadata,
    /** The format's own version block, which gates every counter below. */
    val version: CheckinVersionBlock,
    /** Kernel wakelocks, from batterystats itself -- never from sysfs or debugfs. */
    val kernelWakelocks: List<KernelWakelockStat>,
    /** Per-UID partial wakelocks. */
    val partialWakelocks: List<PartialWakelockStat>,
    /** Numeric UID to package names. A UID may map to many packages, or to none. */
    val uidPackages: List<UidPackageMapping>,
    /**
     * Record tags seen but not decoded, with how many of each.
     *
     * Present so "we did not decode this" is visible and countable rather than silently
     * indistinguishable from "the device did not report this".
     */
    val unsupportedTags: Map<String, Int>,
    /**
     * How many battery-history lines the payload carried.
     *
     * Counted, not decoded. History is a genuinely different format inside the same payload
     * -- `9,h,<elapsed>,<events...>` -- and `-c` always includes it, so a capture reporting
     * zero here is a capture that did not come from the production command. Decoding history
     * is a later phase; counting it now keeps "we chose not to read this" separate from
     * "the device did not send it".
     */
    val historyLineCount: Int,
    /** Everything the decoder wanted to say about data it could not fully trust. */
    val warnings: List<DecodeWarning>,
) {
    val kernelWakelockCount: Int get() = kernelWakelocks.size
    val partialWakelockCount: Int get() = partialWakelocks.size

    /** Distinct UIDs that carry at least one decoded statistic. */
    val uidCount: Int get() = partialWakelocks.map { it.uid }.distinct().size

    /**
     * Kernel wakelocks that actually accumulated something.
     *
     * Separate from [kernelWakelockCount] because the difference is diagnostic: Phase 1A
     * measured 68 named kernel wakelocks on the Android 16 emulator with every value zero,
     * which is the correct answer for a device that never truly suspends. "Enumerated but
     * idle" and "not reported at all" are different facts about a device.
     */
    val activeKernelWakelocks: List<KernelWakelockStat>
        get() = kernelWakelocks.filter { it.hasActivity }
}

/**
 * Where a payload came from and what shape it had, kept apart from what it said.
 *
 * Separate from the counters on purpose. Provenance stays valid even when decoding fails, and
 * a failed decode still needs to report which backend and format produced the bytes.
 *
 * No payload content is held here, and none is logged. [payloadHash] exists so two captures
 * can be compared, or a bug report can reference one, without reproducing privileged output.
 */
data class CaptureMetadata(
    val sourceFormat: SourceFormat,
    /**
     * The format's own record-format version, when the payload declared one.
     *
     * Null before the version record is read, or when the payload has none. Distinct from
     * the platform version: they are different domains and must never be compared.
     */
    val sourceFormatVersion: Int?,
    /** Monotonic device time at capture. Survives wall-clock changes. */
    val captureElapsedRealtimeMillis: Long,
    /** Wall clock at capture, for display only. Can jump. */
    val captureWallClockMillis: Long,
    /** Which privilege mechanism produced the bytes. The decoder never branches on this. */
    val backendKind: BackendIdentity.Kind,
    /** The OS at capture, e.g. "16". Not the format version. */
    val platformVersion: String?,
    val payloadByteCount: Int,
    /**
     * A short digest of the payload, for correlating captures in diagnostics.
     *
     * Not a security control and not stored: it exists so a bug report can say "this is the
     * same capture" without quoting privileged content.
     */
    val payloadHash: String?,
    /**
     * Whether the payload is known to be incomplete.
     *
     * Load-bearing. Phase 3.1 found a real defect where reading only the first 512 KB missed
     * kernel wakelocks entirely, because the `kwl` block sits at 84-88% of the payload. A
     * truncated capture must never let "we stopped reading" be reported as "the device has
     * none".
     */
    val truncated: Boolean,
)

/**
 * The checkin format's version block.
 *
 * Gate on exact values, never on ranges. Between Android 10 and 16 the parcel version moved
 * 1310906 to 215 -- it went *down* by four orders of magnitude -- so any parser comparing
 * magnitudes is already wrong.
 *
 * @param recordFormatVersion the leading field of every line. AOSP's
 *   `BATTERY_STATS_CHECKIN_VERSION`, measured as 9 on both Android 10 and Android 16.
 * @param checkinVersion AOSP's `CHECKIN_VERSION`. Measured 34 on Android 10, 36 on Android 16.
 * @param parcelVersion the parcel format version. Measured 1310906 then 215.
 * @param startPlatformVersion build fingerprint at the start of the accounting window.
 * @param endPlatformVersion build fingerprint at the end. Differing from
 *   [startPlatformVersion] means the window spans an OS update, which makes counters from
 *   either side of it incomparable.
 */
data class CheckinVersionBlock(
    val recordFormatVersion: Int,
    val checkinVersion: Int,
    val parcelVersion: Long,
    val startPlatformVersion: String,
    val endPlatformVersion: String,
) {
    /** True when the accounting window spans an OS update. */
    val spansPlatformChange: Boolean get() = startPlatformVersion != endPlatformVersion
}

/**
 * Which accounting window a record belongs to.
 *
 * From AOSP's `STAT_NAMES`. Records from different windows are not comparable with one
 * another, so the window is kept on every statistic rather than assumed.
 */
enum class AggregationWindow(val checkinCode: String) {
    /** Informational records that are not statistics at all -- `vers`, `uid`. */
    INFO("i"),

    /** Since the device was last charged. What `-c` predominantly emits. */
    SINCE_CHARGED("l"),

    /** The current run. */
    CURRENT_RUN("c"),

    /** Since the device was last unplugged. */
    SINCE_UNPLUGGED("u"),

    /** A window this decoder does not recognise. The record is kept; the window is not guessed. */
    UNKNOWN("?"),
    ;

    companion object {
        fun of(code: String): AggregationWindow =
            entries.firstOrNull { it.checkinCode == code } ?: UNKNOWN
    }
}

/**
 * One kernel wakelock.
 *
 * Sourced from the batterystats `kwl` record. Phase 1A measured every direct kernel interface
 * -- `/proc/wakelocks`, `/sys/kernel/debug/wakeup_sources`, `/sys/power/wake_lock`,
 * `/sys/class/wakeup` -- as unreadable without root on both test environments, while `kwl`
 * was populated at ordinary shell privilege. The sysfs/debugfs collector design stays retired.
 *
 * @param name the kernel's own name for the lock. Quoted in the source format, so it may
 *   contain commas, and it may legitimately be empty -- Android 16 emits one `kwl` record
 *   with an empty name.
 * @param totalTimeMillis cumulative held time in **milliseconds** within [window]. AOSP
 *   rounds microseconds to milliseconds when writing checkin. Cumulative, so it only ever
 *   increases within one accounting window; a decrease means the window restarted.
 * @param count cumulative number of times the lock was taken within [window]. Unitless,
 *   cumulative, never negative in valid data.
 */
data class KernelWakelockStat(
    val name: String,
    val totalTimeMillis: Long,
    val count: Long,
    val window: AggregationWindow,
) {
    /**
     * Whether this lock did anything in the window.
     *
     * A record with zeros is a real measurement -- the kernel enumerated the lock and it was
     * never taken -- and must not be confused with the lock being unreported.
     */
    val hasActivity: Boolean get() = totalTimeMillis > 0L || count > 0L
}

/**
 * One partial wakelock held by an application.
 *
 * Only the *partial* block of the `wl` record is modelled. The record also carries full,
 * background-partial and window blocks; those are read past rather than stored, because
 * Phase 7A has no consumer for them and a stored field nobody validates is a field that
 * quietly goes wrong.
 *
 * @param uid the numeric UID. Authoritative identity -- see [UidPackageMapping].
 * @param name the wakelock tag as the application declared it. Not a package name.
 * @param totalTimeMillis cumulative held time in milliseconds within [window].
 * @param count cumulative acquisition count within [window].
 */
data class PartialWakelockStat(
    val uid: Int,
    val name: String,
    val totalTimeMillis: Long,
    val count: Long,
    val window: AggregationWindow,
) {
    val hasActivity: Boolean get() = totalTimeMillis > 0L || count > 0L
}

/**
 * A numeric UID and one package that runs under it.
 *
 * Emitted once per package, so a shared UID produces several of these -- Android 16 maps 20
 * or more packages onto UID 1000. This is a mapping, never an identity: the UID is the
 * identity.
 *
 * Phase 3 measured that Shizuku can resolve more of these than the app's own UID can, because
 * package-visibility filtering applies to an ordinary application and not to the shell. A
 * capture with fewer mappings is therefore not a capture with fewer UIDs, and statistics must
 * never be discarded for want of a name.
 */
data class UidPackageMapping(
    val uid: Int,
    val packageName: String,
)

/**
 * Something the decoder could not fully trust, kept rather than thrown away.
 *
 * Warnings do not fail a decode. They accumulate so that a capture can be honestly described
 * as partially understood, which is the state real captures are usually in: a vendor adds a
 * record type, a field arrives malformed, a name is unparseable. The alternative -- discarding
 * the capture, or silently coercing bad values to zero -- loses more than it protects.
 */
data class DecodeWarning(
    val kind: Kind,
    /** The record tag involved, when there is one. */
    val tag: String?,
    /** 1-based line number in the payload, for locating it in a diagnostic capture. */
    val line: Int?,
    /** Engineer-facing detail. Never rendered as ordinary UI, and never contains payload data. */
    val detail: String,
) {
    enum class Kind {
        /** A tag this decoder does not implement. Expected, and not a defect. */
        UNSUPPORTED_TAG,

        /** A known tag whose fields did not match the documented layout. */
        MALFORMED_RECORD,

        /** A numeric field that would not parse, or fell outside a valid range. */
        MALFORMED_NUMBER,

        /** A record shorter than its layout requires. */
        TRUNCATED_RECORD,

        /** A record repeated where the format does not obviously permit it. */
        DUPLICATE_RECORD,

        /** The payload declared a version this decoder has not been verified against. */
        UNVERIFIED_VERSION,
    }
}

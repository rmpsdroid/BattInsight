package com.rmpsdroid.battinsight.capability

import com.rmpsdroid.battinsight.collection.CollectionOutcome
import com.rmpsdroid.battinsight.collection.CollectionResult
import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.collection.BackendIdentity

/**
 * Structural inspection of battery statistics output.
 *
 * Answers one question -- *can this backend actually acquire battery statistics?* -- and
 * deliberately not *is every collector available?* A non-empty protobuf establishes that
 * acquisition works; it says nothing about whether any particular field is populated.
 *
 * **No production decoder is built here.** Phase 3 validates framing and, for kernel
 * wakelocks, scans checkin text. Decoding the full schema is later work.
 */
object BatteryStatsProbe {

    /**
     * Whether bytes plausibly are the protobuf `dumpsys batterystats --proto` emits.
     *
     * Phase 1A validated the real thing with a schema-free wire-format walk on two
     * environments. Plain `--proto` is length-delimited: field 1, wire type 2, whose
     * declared length accounts for the remainder exactly.
     *
     * Note `--proto --history` uses *different* framing -- the bare message, opening
     * `08 22`/`08 24` -- so a decoder must not assume one shape. Only plain `--proto` is
     * probed here.
     */
    fun looksLikeProto(bytes: ByteArray): ProtoShape {
        if (bytes.isEmpty()) return ProtoShape.Empty
        if (bytes.size < MIN_PLAUSIBLE_PROTO_BYTES) return ProtoShape.TooSmall(bytes.size)

        // A text error would be almost entirely printable; protobuf is not.
        val sample = minOf(bytes.size, 512)
        var printable = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 32..126 || b == 9 || b == 10 || b == 13) printable++
        }
        if (printable * 100 / sample > MAX_PRINTABLE_PERCENT) {
            return ProtoShape.NotProtobuf("output is ${printable * 100 / sample}% printable")
        }

        // field 1, wire type 2  ->  tag byte 0x0A
        if (bytes[0].toInt() != 0x0A) {
            return ProtoShape.NotProtobuf("unexpected leading tag 0x%02X".format(bytes[0]))
        }
        val (declared, offset) = readVarint(bytes, 1) ?: return ProtoShape.NotProtobuf("bad length varint")
        return if (offset + declared == bytes.size.toLong()) {
            ProtoShape.Valid(bytes.size)
        } else {
            ProtoShape.NotProtobuf("declared length $declared does not account for ${bytes.size} bytes")
        }
    }

    /** Result of structural protobuf inspection. */
    sealed interface ProtoShape {
        data class Valid(val bytes: Int) : ProtoShape
        data object Empty : ProtoShape
        data class TooSmall(val bytes: Int) : ProtoShape
        data class NotProtobuf(val reason: String) : ProtoShape
    }

    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < b.size && shift <= 63) {
            val v = b[i].toInt() and 0xFF
            result = result or ((v and 0x7F).toLong() shl shift)
            i++
            if (v and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    /**
     * Scans checkin output for kernel wakelock records.
     *
     * Kernel wakelocks reach us through battery statistics, not the filesystem: Phase 1A
     * found `/proc/wakelocks` gone, debugfs unmounted on Android 12+ user builds, and
     * `/sys/class/wakeup` SELinux-denied to the shell domain. The retired sysfs collectors
     * are not resurrected here.
     *
     * A `kwl` record looks like:
     * ```
     * 9,0,l,kwl,"bt_read_wake_lock",681038,678,-1,-1
     * ```
     * Records with names but zero durations are the correct answer on a device that has
     * not suspended -- the Android 16 emulator returned 68 such records. That is
     * `AvailableNoEvents`, not a failure.
     *
     * Reads a bounded prefix; the caller discards the payload afterwards.
     */
    fun scanKernelWakelocks(checkinText: String): SourceReading {
        var total = 0
        var withValues = 0
        checkinText.lineSequence().forEach { line ->
            val fields = splitCheckinLine(line)
            if (fields.size >= 7 && fields[3] == KWL_TAG) {
                total++
                val time = fields.getOrNull(5)?.trim()?.toLongOrNull() ?: 0L
                val count = fields.getOrNull(6)?.trim()?.toLongOrNull() ?: 0L
                if (time > 0L || count > 0L) withValues++
            }
        }
        return when {
            total == 0 -> SourceReading.SectionAbsent
            else -> SourceReading.Records(total = total, withValues = withValues)
        }
    }

    /**
     * Splits a checkin line, honouring quoted fields.
     *
     * Necessary because wakelock names are quoted and may contain commas -- a naive
     * `split(",")` mis-parses them, which is precisely the ambiguity that makes protobuf
     * the better routine format.
     */
    internal fun splitCheckinLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }

    /** Extracts the checkin schema version record, if present. */
    fun readCheckinVersion(checkinText: String): CheckinVersion? {
        checkinText.lineSequence().forEach { line ->
            val f = splitCheckinLine(line)
            if (f.size >= 8 && f[3] == VERS_TAG) {
                return CheckinVersion(
                    checkinVersion = f[4].trim().toIntOrNull() ?: return@forEach,
                    parcelVersion = f[5].trim().toLongOrNull() ?: return@forEach,
                    startPlatformVersion = f[6].trim(),
                    endPlatformVersion = f[7].trim(),
                )
            }
        }
        return null
    }

    /**
     * The checkin schema version block.
     *
     * Gate on exact values, never ranges: between Android 10 and 16 the parcel version
     * moved 1310906 -> 215, so any magnitude comparison breaks.
     */
    data class CheckinVersion(
        val checkinVersion: Int,
        val parcelVersion: Long,
        val startPlatformVersion: String,
        val endPlatformVersion: String,
    )

    /** Builds a [CollectionResult] from raw output so classification stays in one place. */
    fun toCollectionResult(
        output: ExecutionOutput,
        backend: BackendIdentity.Kind,
        format: SourceFormat,
        nowMillis: Long,
    ): CollectionResult = CollectionResult(
        backend = backend,
        sourceFormat = format,
        exitCode = output.exitCode,
        stdoutBytes = output.stdoutBytes,
        stderrBytes = output.stderrBytes,
        stdoutHead = output.stdoutHead(),
        stderrText = output.stderrText(),
        durationMillis = output.durationMillis,
        timestampMillis = nowMillis,
    )

    /**
     * Combines classification with structural validation for the protobuf probe.
     *
     * `CollectionResult.classify` only sees a decoded text prefix, which cannot judge
     * binary framing. So a `Data` verdict is confirmed against [looksLikeProto] before
     * acquisition is reported as working.
     */
    fun evaluateProtoAcquisition(result: CollectionResult, rawStdout: ByteArray): CapabilityState {
        val outcome = result.outcome()
        if (outcome !is CollectionOutcome.Data) {
            return CapabilityInterpreter.interpret(outcome)
        }
        return when (val shape = looksLikeProto(rawStdout)) {
            is ProtoShape.Valid -> CapabilityState.Available
            ProtoShape.Empty -> CapabilityState.AvailableNoEvents("command produced no protobuf")
            is ProtoShape.TooSmall ->
                CapabilityState.ExecutionFailed("protobuf too small to be plausible (${shape.bytes} bytes)")
            is ProtoShape.NotProtobuf ->
                CapabilityState.ExecutionFailed("output is not the expected protobuf: ${shape.reason}")
        }
    }

    private const val KWL_TAG = "kwl"
    private const val VERS_TAG = "vers"
    private const val MIN_PLAUSIBLE_PROTO_BYTES = 64
    private const val MAX_PRINTABLE_PERCENT = 95
}

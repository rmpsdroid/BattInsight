package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.collection.SourceFormat

/**
 * Decodes `dumpsys batterystats -c` output.
 *
 * ## Line shape
 *
 * Every line is `recordFormatVersion, uid, window, tag, fields...`, verified against AOSP
 * (`frameworks/base/core/java/android/os/BatteryStats.java`) and against the Phase 1A
 * captures from Android 10 and Android 16:
 *
 * ```
 * 9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1
 * 9,0,i,uid,1000,com.android.settings
 * 9,0,l,kwl,"bt_read_wake_lock",681038,678,-1,-1
 * 9,1000,l,wl,WindowManager,7713,f,3,-1,-1,-1,0,p,0,0,0,0,0,bp,0,0,0,0,0,w,0,0,0,0
 * ```
 *
 * ## What is decoded, and what is not
 *
 * Four record types: `vers`, `uid`, `kwl`, `wl`. Every other tag is counted and reported as
 * unsupported rather than modelled.
 *
 * That is a deliberately small surface. The Android 16 capture carries 25 distinct tags, and
 * decoding a record whose field semantics have not been read out of AOSP source means
 * inventing units from magnitudes -- exactly how a battery tool ends up confidently
 * displaying milliseconds as seconds. Tags graduate to decoded when their layout is verified,
 * not when they look obvious.
 *
 * ## Streaming
 *
 * Lines are consumed one at a time and never all held at once. Phase 3.1 found a real defect
 * where a 512 KB prefix scan missed kernel wakelocks entirely, because the `kwl` block sits
 * at 84-88% of the payload. A decoder that stops early would recreate it, so this one always
 * reaches the end of what it is given -- and if what it was given is itself a prefix, the
 * caller says so through [CaptureMetadata.truncated] and the result is [DecodeOutcome.TRUNCATED].
 */
class CheckinDecoder : BatteryStatsDecoder {

    override val supportedFormats: Set<SourceFormat> = setOf(SourceFormat.CHECKIN)

    override fun decode(payload: ByteArray, metadata: CaptureMetadata): DecodeResult {
        if (metadata.sourceFormat !in supportedFormats) {
            return DecodeResult.Failure(
                DecodeOutcome.UNSUPPORTED_FORMAT,
                "this decoder reads ${SourceFormat.CHECKIN.name}, not ${metadata.sourceFormat.name}",
                metadata,
            )
        }
        if (payload.isEmpty()) {
            return DecodeResult.Failure(DecodeOutcome.EMPTY, "the payload has no bytes", metadata)
        }

        val text = payload.toString(Charsets.UTF_8)

        // Denial before anything else. dumpsys was measured returning exit status 0 with a
        // denial on stdout, so a decoder that looked at structure first would find no records
        // and report a device with no statistics.
        denialDetail(text)?.let {
            return DecodeResult.Failure(DecodeOutcome.PERMISSION_DENIAL_PAYLOAD, it, metadata)
        }

        return decodeLines(text.lineSequence(), metadata)
    }

    private fun decodeLines(
        lines: Sequence<String>,
        metadata: CaptureMetadata,
    ): DecodeResult {
        val warnings = mutableListOf<DecodeWarning>()
        var historyLines = 0
        val unsupported = mutableMapOf<String, Int>()
        val kernelWakelocks = mutableListOf<KernelWakelockStat>()
        val partialWakelocks = mutableListOf<PartialWakelockStat>()
        val uidPackages = mutableListOf<UidPackageMapping>()
        val seenKernelNames = mutableSetOf<String>()
        var version: CheckinVersionBlock? = null
        var recordLines = 0
        var lineNumber = 0

        for (raw in lines) {
            lineNumber++
            val line = raw.trim()
            if (line.isEmpty()) continue

            val fields = splitCheckinLine(line)

            // History first, and before any length check.
            //
            // History lines are a different shape entirely: `9,h,<elapsed>,<events...>`,
            // where the field that holds a record tag in an aggregate line holds event data.
            // Dispatching on that field without recognising history invents an "unsupported
            // record type" for every `+r`, `-w` and `Bl=100` in the payload.
            //
            // The length check has to come *after* this, not before, because history lines
            // are not all four fields long: `9,h,0:RESET:TIME:1788344548223` is three. An
            // earlier version rejected those as truncated records and both undercounted the
            // history and emitted thousands of spurious warnings -- 2,746 on one capture and
            // 7,080 on another.
            //
            // Production captures with `-c` always contain history: `-c` returns the
            // aggregate block *and* the history in one call, which is why Phase 1A chose it.
            if (fields.size >= 2 && fields[UID_FIELD].trim() in HISTORY_MARKERS) {
                historyLines++
                continue
            }

            // Every aggregate record is at least version, uid, window, tag.
            if (fields.size < MIN_FIELDS) {
                warnings.add(
                    DecodeWarning(
                        DecodeWarning.Kind.TRUNCATED_RECORD, null, lineNumber,
                        "line has ${fields.size} fields, fewer than the $MIN_FIELDS a record requires",
                    ),
                )
                continue
            }

            val tag = fields[TAG]
            recordLines++

            when (tag) {
                VERS -> {
                    val parsed = parseVersion(fields, lineNumber, warnings)
                    if (parsed != null) {
                        if (version != null && version != parsed) {
                            warnings.add(
                                DecodeWarning(
                                    DecodeWarning.Kind.DUPLICATE_RECORD, VERS, lineNumber,
                                    "a second, different version record appeared; the first is kept",
                                ),
                            )
                        } else if (version == null) {
                            version = parsed
                        }
                    }
                }

                UID -> parseUidMapping(fields, lineNumber, warnings)?.let(uidPackages::add)

                KWL -> parseKernelWakelock(fields, lineNumber, warnings)?.let { stat ->
                    // Names are not guaranteed unique -- Android 16 emits an empty name --
                    // so duplicates are noted and kept rather than silently collapsed.
                    if (!seenKernelNames.add(stat.name)) {
                        warnings.add(
                            DecodeWarning(
                                DecodeWarning.Kind.DUPLICATE_RECORD, KWL, lineNumber,
                                "a kernel wakelock name repeats; both records are kept",
                            ),
                        )
                    }
                    kernelWakelocks.add(stat)
                }

                WL -> parsePartialWakelock(fields, lineNumber, warnings)?.let(partialWakelocks::add)

                else -> unsupported[tag] = (unsupported[tag] ?: 0) + 1
            }
        }

        // No records at all means this was not checkin output, whatever it was.
        if (recordLines == 0) {
            return DecodeResult.Failure(
                DecodeOutcome.MALFORMED,
                "no checkin records were found in ${metadata.payloadByteCount} bytes",
                metadata,
            )
        }

        if (unsupported.isNotEmpty()) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.UNSUPPORTED_TAG, null, null,
                    "${unsupported.size} record types are not decoded by this phase: " +
                        unsupported.keys.sorted().joinToString(", "),
                ),
            )
        }

        // The version block gates every counter, so its absence is INCOMPLETE rather than a
        // successful decode of unversioned numbers.
        val resolved = version ?: return DecodeResult.Failure(
            DecodeOutcome.INCOMPLETE,
            "no version record; counters cannot be trusted without one",
            metadata,
        )

        if (resolved.checkinVersion !in VERIFIED_CHECKIN_VERSIONS) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.UNVERIFIED_VERSION, VERS, null,
                    "checkin version ${resolved.checkinVersion} has not been verified against " +
                        "a measured capture; decoded records may be misread",
                ),
            )
        }
        if (resolved.spansPlatformChange) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.UNVERIFIED_VERSION, VERS, null,
                    "the accounting window spans an OS update; counters from either side of " +
                        "it are not comparable",
                ),
            )
        }

        val capture = BatteryStatsCapture(
            metadata = metadata.copy(sourceFormatVersion = resolved.recordFormatVersion),
            version = resolved,
            kernelWakelocks = kernelWakelocks,
            partialWakelocks = partialWakelocks,
            uidPackages = uidPackages,
            unsupportedTags = unsupported.toMap(),
            historyLineCount = historyLines,
            warnings = warnings.toList(),
        )

        // A truncated payload decodes fine as far as it goes, and that is exactly the trap:
        // its late sections are missing, and kwl is a late section. Reporting success here
        // would let "we stopped reading" be read as "the device has none".
        if (metadata.truncated) {
            return DecodeResult.Failure(
                DecodeOutcome.TRUNCATED,
                "the capture is a prefix; sections after the cut are missing, not absent " +
                    "(${kernelWakelocks.size} kernel wakelocks seen before it)",
                capture.metadata,
            )
        }

        return DecodeResult.Success(capture)
    }

    // ------------------------------------------------------------------ record parsers

    /** `9,0,i,vers,36,215,<startPlatform>,<endPlatform>` */
    private fun parseVersion(
        fields: List<String>,
        line: Int,
        warnings: MutableList<DecodeWarning>,
    ): CheckinVersionBlock? {
        if (fields.size < 8) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.TRUNCATED_RECORD, VERS, line,
                    "version record has ${fields.size} fields, needs 8",
                ),
            )
            return null
        }
        val recordFormat = intAt(fields, 0, VERS, line, warnings) ?: return null
        val checkin = intAt(fields, 4, VERS, line, warnings) ?: return null
        val parcel = longAt(fields, 5, VERS, line, warnings) ?: return null
        return CheckinVersionBlock(
            recordFormatVersion = recordFormat,
            checkinVersion = checkin,
            parcelVersion = parcel,
            startPlatformVersion = fields[6].trim(),
            endPlatformVersion = fields[7].trim(),
        )
    }

    /** `9,0,i,uid,1000,com.android.settings` */
    private fun parseUidMapping(
        fields: List<String>,
        line: Int,
        warnings: MutableList<DecodeWarning>,
    ): UidPackageMapping? {
        if (fields.size < 6) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.TRUNCATED_RECORD, UID, line,
                    "uid record has ${fields.size} fields, needs 6",
                ),
            )
            return null
        }
        val uid = intAt(fields, 4, UID, line, warnings) ?: return null
        val name = fields[5].trim()
        if (name.isEmpty()) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.MALFORMED_RECORD, UID, line,
                    "uid $uid maps to an empty package name",
                ),
            )
            return null
        }
        return UidPackageMapping(uid = uid, packageName = name)
    }

    /**
     * `9,0,l,kwl,"name",totalTimeMs,count,-1,-1`
     *
     * Only the name, time and count are interpreted. The two trailing fields are consistently
     * `-1` in every measured capture and their meaning was not confirmed from AOSP source, so
     * they are read past rather than stored under a guessed name.
     */
    private fun parseKernelWakelock(
        fields: List<String>,
        line: Int,
        warnings: MutableList<DecodeWarning>,
    ): KernelWakelockStat? {
        if (fields.size < 7) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.TRUNCATED_RECORD, KWL, line,
                    "kernel wakelock record has ${fields.size} fields, needs 7",
                ),
            )
            return null
        }
        // The name is deliberately not rejected when empty: Android 16 emits exactly one
        // kwl record with an empty name, and dropping it would under-count the device.
        val name = fields[4]
        val time = longAt(fields, 5, KWL, line, warnings) ?: return null
        val count = longAt(fields, 6, KWL, line, warnings) ?: return null
        if (time < 0L || count < 0L) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.MALFORMED_NUMBER, KWL, line,
                    "negative duration or count is not valid for a cumulative counter",
                ),
            )
            return null
        }
        return KernelWakelockStat(
            name = name,
            totalTimeMillis = time,
            count = count,
            window = AggregationWindow.of(fields[WINDOW].trim()),
        )
    }

    /**
     * The partial block of a `wl` record.
     *
     * AOSP documents the layout verbatim:
     *
     * ```
     * wl line is: BATTERY_STATS_CHECKIN_VERSION, uid, which, "wl", name,
     *   full totalTime, 'f', count, current duration, max duration, total duration,
     *   partial totalTime, 'p', count, current duration, max duration, total duration,
     *   bg partial totalTime, 'bp', count, ...,
     *   window totalTime, 'w', count, ...
     * ```
     *
     * The subtlety worth stating: the marker letter is the **second** element of each
     * six-field block, not the first. `...,7713,f,3,...` is "full total time 7713, then the
     * 'f' marker, then count 3". A parser that expects the marker first reads the previous
     * block's total as this block's marker and silently produces nonsense.
     *
     * Only the partial block is kept -- see [PartialWakelockStat].
     */
    private fun parsePartialWakelock(
        fields: List<String>,
        line: Int,
        warnings: MutableList<DecodeWarning>,
    ): PartialWakelockStat? {
        // name + full block (6) + partial block (6) = index 4 through 15 inclusive.
        if (fields.size < 16) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.TRUNCATED_RECORD, WL, line,
                    "wakelock record has ${fields.size} fields, needs at least 16 to reach " +
                        "the partial block",
                ),
            )
            return null
        }
        val uid = intAt(fields, 1, WL, line, warnings) ?: return null
        val name = fields[4]

        // Locate the partial block by its marker rather than by a fixed offset, and verify
        // it. A record whose markers are not where the layout says they are is malformed,
        // and must not be reported as an unknown tag or coerced into zeros.
        if (fields[6].trim() != FULL_MARKER || fields[12].trim() != PARTIAL_MARKER) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.MALFORMED_RECORD, WL, line,
                    "expected '$FULL_MARKER' and '$PARTIAL_MARKER' markers at fields 6 and 12",
                ),
            )
            return null
        }

        val time = longAt(fields, 11, WL, line, warnings) ?: return null
        val count = longAt(fields, 13, WL, line, warnings) ?: return null
        if (time < 0L || count < 0L) {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.MALFORMED_NUMBER, WL, line,
                    "negative duration or count is not valid for a cumulative counter",
                ),
            )
            return null
        }
        return PartialWakelockStat(
            uid = uid,
            name = name,
            totalTimeMillis = time,
            count = count,
            window = AggregationWindow.of(fields[WINDOW].trim()),
        )
    }

    // ---------------------------------------------------------------------- primitives

    private fun intAt(
        fields: List<String>,
        index: Int,
        tag: String,
        line: Int,
        warnings: MutableList<DecodeWarning>,
    ): Int? = fields.getOrNull(index)?.trim()?.toIntOrNull()
        ?: null.also {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.MALFORMED_NUMBER, tag, line,
                    "field $index is not a 32-bit integer",
                ),
            )
        }

    /**
     * Parses a 64-bit field.
     *
     * Overflow is a typed warning, not a wrapped value. `toLongOrNull` returns null for
     * anything outside Long, so a counter larger than 2^63 becomes a refused record rather
     * than a negative duration -- which is what silent overflow would produce, and which
     * would then look exactly like a counter reset.
     */
    private fun longAt(
        fields: List<String>,
        index: Int,
        tag: String,
        line: Int,
        warnings: MutableList<DecodeWarning>,
    ): Long? = fields.getOrNull(index)?.trim()?.toLongOrNull()
        ?: null.also {
            warnings.add(
                DecodeWarning(
                    DecodeWarning.Kind.MALFORMED_NUMBER, tag, line,
                    "field $index is not a 64-bit integer, or overflows one",
                ),
            )
        }

    companion object {
        /**
         * Splits a checkin line, honouring quoted fields.
         *
         * Not optional. The Android 10 capture contains
         * `9,0,l,wr,"Abort:Some devices failed to suspend, or early wake event detected",0,0`
         * -- a quoted field with an embedded comma. A naive `split(",")` shifts every
         * subsequent field by one and produces numbers from the wrong columns.
         */
        internal fun splitCheckinLine(line: String): List<String> {
            val out = ArrayList<String>(16)
            val sb = StringBuilder()
            var inQuotes = false
            for (ch in line) {
                when {
                    ch == '"' -> inQuotes = !inQuotes
                    ch == ',' && !inQuotes -> {
                        out.add(sb.toString()); sb.setLength(0)
                    }
                    else -> sb.append(ch)
                }
            }
            out.add(sb.toString())
            return out
        }

        /**
         * Recognises a permission denial payload.
         *
         * Kept consistent with the collection layer's own denial markers rather than
         * inventing a second interpretation: a string that the capability layer calls a
         * denial must never be something the decoder calls an empty capture.
         */
        internal fun denialDetail(text: String): String? {
            val head = text.take(DENIAL_SCAN_LIMIT)
            val matched = DENIAL_MARKERS.firstOrNull { head.contains(it, ignoreCase = true) }
                ?: return null
            return "the payload is a permission denial, not statistics (matched \"$matched\")"
        }

        private val DENIAL_MARKERS = listOf(
            "Permission Denial",
            "Security exception",
            "missing android.permission",
            "requires android.permission",
            "SecurityException",
        )

        /**
         * Denials are short and arrive first; a real capture is hundreds of kilobytes.
         * Scanning a bounded head keeps a denial check from walking the whole payload, and
         * keeps a wakelock named "SecurityException" in a 800 KB capture from being mistaken
         * for one.
         */
        private const val DENIAL_SCAN_LIMIT = 4096

        /** Field indices shared by every record. */
        private const val UID_FIELD = 1
        private const val WINDOW = 2
        private const val TAG = 3
        private const val MIN_FIELDS = 4

        /**
         * Field 1 of a history-block line, in place of a UID.
         *
         * Two of them, and missing the second was a real defect. `h` is a history event;
         * `hsp` is the history string pool, shaped `9,hsp,<index>,<uid>,"<string>"`. Because
         * `hsp` puts a **UID** where an aggregate record puts its tag, treating it as an
         * aggregate record turned every distinct UID in the pool into a fictional record
         * type: 124 of them on one Android 16 capture, against 42 real ones.
         *
         * These are the only two non-numeric values field 1 takes across all four measured
         * captures, which is what makes an explicit set safe rather than a guess.
         */
        private val HISTORY_MARKERS = setOf("h", "hsp")

        private const val VERS = "vers"
        private const val UID = "uid"
        private const val KWL = "kwl"
        private const val WL = "wl"

        private const val FULL_MARKER = "f"
        private const val PARTIAL_MARKER = "p"

        /**
         * Checkin versions this decoder has been verified against with a real capture.
         *
         * 34 is Android 10 and 36 is Android 16, both from the Phase 1A corpus. An unlisted
         * version still decodes -- refusing would make every future Android release a
         * failure -- but it carries a warning, because the layout is then assumed rather
         * than measured.
         */
        private val VERIFIED_CHECKIN_VERSIONS = setOf(34, 36)
    }
}

package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.collection.SourceFormat

/**
 * Why a decode ended the way it did.
 *
 * A typed outcome rather than an exception, for the same reason Phase 6 typed its persistence
 * failures: the difference between "this device reports no kernel wakelocks" and "we could
 * not read the payload" is the difference between a fact about the user's device and a fact
 * about our own code, and a thrown exception flattens both into "something went wrong".
 *
 * Exception-or-not is not the product contract. Callers match on this.
 */
enum class DecodeOutcome {
    /** The payload was decoded. Warnings may still be present. */
    SUCCESS,

    /** The payload is in a format this decoder does not implement. */
    UNSUPPORTED_FORMAT,

    /** The format is right but its declared version has not been verified. */
    UNSUPPORTED_VERSION,

    /** Structurally wrong: not the format it claims to be. */
    MALFORMED,

    /** Cut short. Late sections may be missing and must not be reported as absent. */
    TRUNCATED,

    /** Nothing to decode. */
    EMPTY,

    /**
     * The payload is a permission denial, not statistics.
     *
     * Its own outcome because Phase 1B measured `dumpsys` returning **exit status 0** with a
     * denial on stdout. A denial that parsed as an empty capture would be reported to the
     * user as "your device has no wakelocks", which is false and unfalsifiable.
     */
    PERMISSION_DENIAL_PAYLOAD,

    /** Decoded, but a required part was missing. */
    INCOMPLETE,

    /** Anything else. */
    UNKNOWN_FAILURE,
}

/**
 * The result of decoding one payload.
 *
 * [Success] still carries warnings, because a real capture is usually partially understood
 * rather than perfectly understood or worthless.
 */
sealed interface DecodeResult {

    val outcome: DecodeOutcome

    data class Success(
        val capture: BatteryStatsCapture,
    ) : DecodeResult {
        override val outcome: DecodeOutcome get() = DecodeOutcome.SUCCESS
        val warnings: List<DecodeWarning> get() = capture.warnings
    }

    /**
     * @param detail engineer-facing. Must never contain payload content.
     * @param metadata what is known about the capture even though it could not be decoded.
     */
    data class Failure(
        override val outcome: DecodeOutcome,
        val detail: String,
        val metadata: CaptureMetadata?,
    ) : DecodeResult

    val succeeded: Boolean get() = this is Success
    val captureOrNull: BatteryStatsCapture? get() = (this as? Success)?.capture
}

/**
 * Turns acquired bytes into a normalised capture.
 *
 * The boundary exists so that nothing above it ever sees a raw payload. No collector, no
 * view model and no Composable parses bytes; they receive a [BatteryStatsCapture] or a typed
 * failure. The predecessor parsed in its UI layer, which is why a format change there broke
 * screens rather than one class.
 *
 * Implementations must be **pure**: no Android, no I/O, no clock. Everything variable arrives
 * through [CaptureMetadata], which is what lets the whole decoder be tested on the JVM
 * against real captured payloads.
 *
 * Implementations must also be **backend-blind**. The same bytes must decode identically
 * whether Shizuku or the granted-app backend produced them; the backend is recorded in
 * metadata for diagnostics and is never branched on. Choosing a backend is Phase 4's job.
 */
interface BatteryStatsDecoder {

    /** Which formats this decoder can handle. */
    val supportedFormats: Set<SourceFormat>

    /**
     * Decodes a payload.
     *
     * @param payload the complete capture. If it is a prefix, [CaptureMetadata.truncated]
     *   must be true -- see the note there about the 512 KB defect.
     */
    fun decode(payload: ByteArray, metadata: CaptureMetadata): DecodeResult
}

package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.capability.BatteryStatsProbe
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.CollectionOutcome
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.ProcessRunner
import com.rmpsdroid.battinsight.collection.SourceFormat

/**
 * Where a capture's timestamps come from.
 *
 * Injected so the collector stays testable on the JVM. Elapsed realtime is monotonic within a
 * boot and survives clock changes; wall clock can jump and is for display only. The same
 * distinction the session engine makes, for the same reason.
 */
interface CaptureClock {
    fun elapsedRealtimeMillis(): Long
    fun wallClockMillis(): Long
}

/**
 * The production path from a privileged backend to a normalised capture.
 *
 * ```
 * selected backend -> fixed ProbeCommand -> ExecutionOutput
 *                  -> denial / truncation validation
 *                  -> BatteryStatsDecoder -> BatteryStatsCapture
 * ```
 *
 * Every step above is here, and none of it is anywhere else. No view model runs a command, no
 * Composable parses bytes, and no DAO sees a payload. The predecessor parsed in its UI layer,
 * which is why a format change there broke screens instead of one class.
 *
 * The collector takes a [ProcessRunner] rather than choosing one. Which backend is active is
 * Phase 4's decision, and passing the runner in keeps that decision where it already lives --
 * this class cannot express a preference between Shizuku and the granted-app path even by
 * accident.
 *
 * The command is a fixed [ProbeCommand], never a constructed string. Phase 2A established
 * that the application does not build shell command lines, and decoding does not change it.
 */
class BatteryStatsCollector(
    private val clock: CaptureClock,
    private val decoder: BatteryStatsDecoder = CheckinDecoder(),
) {
    /**
     * Captures and decodes once.
     *
     * @param runner the already-selected backend's runner.
     * @param backendKind recorded in metadata for diagnostics. The decoder never reads it.
     * @param platformVersion the OS at capture, for provenance. Never used to infer layout --
     *   the payload's own version record does that.
     */
    suspend fun collect(
        runner: ProcessRunner,
        backendKind: BackendIdentity.Kind,
        platformVersion: String? = null,
    ): DecodeResult {
        val command = ProbeCommand.BatteryStatsCheckinCurrent
        val output = try {
            runner.run(command)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return DecodeResult.Failure(
                DecodeOutcome.UNKNOWN_FAILURE,
                "the capture command could not be run: ${t.javaClass.simpleName}",
                null,
            )
        }

        val metadata = CaptureMetadata(
            sourceFormat = SourceFormat.CHECKIN,
            sourceFormatVersion = null,
            captureElapsedRealtimeMillis = clock.elapsedRealtimeMillis(),
            captureWallClockMillis = clock.wallClockMillis(),
            backendKind = backendKind,
            platformVersion = platformVersion,
            payloadByteCount = output.stdoutBytes,
            payloadHash = null,
            truncated = output.truncated,
        )

        if (output.timedOut) {
            return DecodeResult.Failure(
                DecodeOutcome.TRUNCATED,
                "the capture timed out after ${output.durationMillis} ms; what arrived is a prefix",
                metadata,
            )
        }

        // Classification goes through the existing collection layer rather than being
        // re-derived here. Phase 1B measured denials arriving with exit status 0 and an
        // empty stderr, and the capability layer already knows every measured denial
        // signature. A second, independently written interpretation is how two parts of one
        // application end up disagreeing about whether the user has access.
        val classified = BatteryStatsProbe.toCollectionResult(
            output, backendKind, SourceFormat.CHECKIN, output.durationMillis,
        ).outcome()

        if (classified is CollectionOutcome.PermissionDenied) {
            return DecodeResult.Failure(
                DecodeOutcome.PERMISSION_DENIAL_PAYLOAD,
                "the backend returned a permission denial: ${classified.permission}",
                metadata,
            )
        }

        return decoder.decode(output.stdout, metadata)
    }
}

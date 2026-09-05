package com.rmpsdroid.battinsight.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture
import com.rmpsdroid.battinsight.batterystats.DecodeOutcome
import com.rmpsdroid.battinsight.batterystats.DecodeResult

/**
 * A diagnostic view of one decoded capture.
 *
 * Deliberately not a history screen. Phase 7A proves the decoder works; showing what it
 * understood is how a person checks that claim on their own device, which is worth more right
 * now than a chart of data whose correctness has not been established.
 *
 * It shows counts and a short sample, never the capture. A full list of every wakelock and
 * package on the device is the privileged payload rendered to the screen, and this project
 * does not put that anywhere it could be screenshotted into a bug report by accident.
 */
@Composable
fun CoreCollectorSection(
    state: CollectorUiState,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Battery statistics decoder", style = MaterialTheme.typography.titleSmall)

            when (state) {
                CollectorUiState.Idle -> Text(
                    "No capture taken yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                CollectorUiState.Capturing -> {
                    CircularProgressIndicator()
                    Text("Capturing…", style = MaterialTheme.typography.bodySmall)
                }

                is CollectorUiState.Decoded -> Decoded(state.capture)
                is CollectorUiState.Failed -> Failed(state)
            }

            OutlinedButton(
                onClick = onCapture,
                enabled = state !is CollectorUiState.Capturing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state is CollectorUiState.Idle) "Capture statistics" else "Capture again")
            }
        }
    }
}

@Composable
private fun Decoded(capture: BatteryStatsCapture) {
    Row("Source", "${capture.metadata.sourceFormat.name.lowercase()} · ${capture.metadata.backendKind.name.lowercase()}")
    Row(
        "Format version",
        "record ${capture.version.recordFormatVersion} · checkin ${capture.version.checkinVersion} · " +
            "parcel ${capture.version.parcelVersion}",
    )
    Row("Capture size", "${capture.metadata.payloadByteCount / 1024} KB")
    Row("Kernel wakelocks", "${capture.kernelWakelockCount} (${capture.activeKernelWakelocks.size} active)")
    Row("Partial wakelocks", "${capture.partialWakelockCount}")
    Row("UID records", "${capture.uidCount} with statistics · ${capture.uidPackages.size} name mappings")
    Row("History lines", "${capture.historyLineCount} (not decoded in this phase)")
    Row("Undecoded record types", "${capture.unsupportedTags.size}")

    // A short sample, so a person can see real values rather than only counts. Bounded to
    // five: this is a diagnostic, and thousands of rows in a Compose list is a different
    // feature with different performance and privacy questions.
    val top = capture.activeKernelWakelocks.sortedByDescending { it.totalTimeMillis }.take(5)
    if (top.isNotEmpty()) {
        Text(
            "Longest-held kernel wakelocks",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
        top.forEach {
            Text(
                "${it.name.ifEmpty { "(unnamed)" }} — ${it.totalTimeMillis / 1000}s over ${it.count}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    } else if (capture.kernelWakelockCount > 0) {
        Text(
            "Every kernel wakelock reported zero time. That is the expected result on a " +
                "device that never fully suspends, such as an emulator.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (capture.warnings.isNotEmpty()) {
        Text(
            "${capture.warnings.size} decode warning${if (capture.warnings.size == 1) "" else "s"}. " +
                "Some of the capture was not fully understood; what is shown above is what was.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Failed(state: CollectorUiState.Failed) {
    Text(
        describeFailure(state.outcome),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * What a failed capture means, in the user's terms.
 *
 * No exception text, no record tags, no field indices. The `TRUNCATED` wording matters most:
 * a partial capture that reported "no kernel wakelocks" would be a false statement about the
 * device, and it is the exact defect Phase 3.1 found.
 */
private fun describeFailure(outcome: DecodeOutcome): String = when (outcome) {
    DecodeOutcome.PERMISSION_DENIAL_PAYLOAD ->
        "Android refused the request. Set up access again from Manage access."
    DecodeOutcome.TRUNCATED ->
        "The capture was cut short, so this reading is incomplete. Nothing is missing from " +
            "your device — BattInsight stopped reading before the end."
    DecodeOutcome.EMPTY ->
        "Android returned nothing at all."
    DecodeOutcome.UNSUPPORTED_VERSION ->
        "This device reports battery statistics in a version BattInsight has not been " +
            "checked against, so it will not guess at the numbers."
    DecodeOutcome.UNSUPPORTED_FORMAT ->
        "The statistics arrived in a format BattInsight does not read."
    DecodeOutcome.INCOMPLETE ->
        "The statistics arrived without the version information needed to read them safely."
    DecodeOutcome.MALFORMED ->
        "The statistics could not be read. This is a problem in BattInsight, not on your device."
    DecodeOutcome.SUCCESS, DecodeOutcome.UNKNOWN_FAILURE ->
        "The capture did not complete."
}

@Composable
private fun Row(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the collector screen is currently showing. */
sealed interface CollectorUiState {
    data object Idle : CollectorUiState
    data object Capturing : CollectorUiState
    data class Decoded(val capture: BatteryStatsCapture) : CollectorUiState

    /** Detail is kept for diagnostics and is deliberately not rendered. */
    data class Failed(val outcome: DecodeOutcome, val detail: String) : CollectorUiState

    companion object {
        fun from(result: DecodeResult): CollectorUiState = when (result) {
            is DecodeResult.Success -> Decoded(result.capture)
            is DecodeResult.Failure -> Failed(result.outcome, result.detail)
        }
    }
}

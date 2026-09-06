package com.rmpsdroid.battinsight.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.history.CounterAvailability
import com.rmpsdroid.battinsight.history.HistoryPresentation
import com.rmpsdroid.battinsight.chart.BatteryChartModel
import com.rmpsdroid.battinsight.chart.CounterChartModel
import com.rmpsdroid.battinsight.history.SessionDetail

/**
 * One battery period, in full.
 *
 * Ordered the way someone troubleshooting actually reads: what happened, then how well we know
 * it happened, then what the counters say, then the machinery. Diagnostics sit last because
 * they answer questions about BattInsight rather than about the device.
 */
@Composable
fun SessionDetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Battery period", style = MaterialTheme.typography.headlineSmall)

        when (state) {
            DetailUiState.Loading -> CircularProgressIndicator()
            is DetailUiState.Missing -> Card(Modifier.fillMaxWidth()) {
                Text(
                    "This period could not be found. It may have been cleared.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is DetailUiState.Loaded -> Loaded(state)
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun Loaded(state: DetailUiState.Loaded) {
    val detail = state.detail
    val row = detail.row

    Section("Session") {
        Field("Type", HistoryPresentation.sessionTitle(row.type, row.isActive))
        Field("State", if (row.isActive) "Still running" else "Completed")
        Field("Started", state.formatWallClock(row.startWallClockMillis))
        row.endWallClockMillis?.let { Field("Ended", state.formatWallClock(it)) }
        Field("Duration", HistoryPresentation.duration(row.durationMillis))
        Field("Battery at start", HistoryPresentation.batteryPercent(row.startBattery))
        Field(
            if (row.isActive) "Battery now" else "Battery at end",
            HistoryPresentation.batteryPercent(row.endBattery),
        )
    }

    // The chart sections sit between the summary and the existing diagnostics. Nothing below
    // them changed: a session with no sampled series keeps exactly the detail it had before.
    state.batteryChart?.let { BatteryTrendSection(it, state.formatDuration) }
    CounterActivitySection(state.kernelChart, state.applicationChart, state.formatDuration)

    Section("How this period began and ended") {
        Field("Began", HistoryPresentation.startDescription(detail.provenance.startTrigger))
        Field(
            "Evidence",
            HistoryPresentation.observationLabel(detail.provenance.startObserved),
        )
        Field("Ended", HistoryPresentation.endDescription(detail.provenance.endReason, row.isActive))
        Field("Start-up identity", detail.provenance.bootIdentityLabel)
        Field("Counter generation", detail.provenance.counterGeneration.toString())
    }

    val captures = detail.captures
    if (captures == null) {
        Section("Battery statistics") {
            Text(
                "No battery statistics were captured during this period. Statistics are only " +
                    "collected when you ask for them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Section("Battery statistics") {
        Field("First capture", state.formatWallClock(captures.baselineWallClockMillis))
        Field(
            "Most recent capture",
            if (captures.baselineIsLatest) {
                "same as the first"
            } else {
                state.formatWallClock(captures.latestWallClockMillis)
            },
        )
        Comparison(detail)
    }

    if (detail.unavailableReason == null && !captures.baselineIsLatest) {
        DeltaSection(
            title = "Kernel wakelocks — what changed",
            rows = HistoryPresentation.topBy(
                detail.kernelDeltas, TOP_N,
                duration = { it.durationDeltaMillis },
                count = { it.countDelta },
                name = { it.name },
            ).map {
                DeltaRow(
                    it.name.ifEmpty { "(unnamed)" },
                    HistoryPresentation.deltaLabel(it.durationDeltaMillis, it.countDelta),
                )
            },
            emptyMessage = "No kernel wakelock recorded any additional time. On a device that " +
                "never fully suspends, such as an emulator, that is the expected result.",
        )

        DeltaSection(
            title = "App wakelocks — what changed",
            rows = HistoryPresentation.topBy(
                detail.partialDeltas, TOP_N,
                duration = { it.durationDeltaMillis },
                count = { it.countDelta },
                name = { it.name },
            ).map {
                DeltaRow(
                    HistoryPresentation.uidLabel(it.uid, state.resolvePackage(it.uid)) +
                        " · " + it.name,
                    HistoryPresentation.deltaLabel(it.durationDeltaMillis, it.countDelta),
                )
            },
            emptyMessage = "No app wakelock recorded any additional time between these captures.",
        )

        Section("About app names") {
            Text(
                "App names are looked up now, not saved with the reading. A name shows what " +
                    "runs under that user ID today, which may not be what ran under it when " +
                    "the reading was taken. The user ID is the reliable part.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Section("Diagnostics") {
        Field("Collected through", captures.backendKind.name.lowercase().replace('_', ' '))
        Field("Source format", captures.sourceFormat.name.lowercase())
        Field(
            "Statistics format version",
            "checkin ${captures.checkinVersion} · record ${captures.recordFormatVersion}" +
                if (captures.checkinVersionVerified) "" else " (not verified)",
        )
        Field("Capture size", "${captures.payloadByteCount / 1024} KB")
        Field("Decode warnings", captures.warningCount.toString())
        if (captures.platformChanged) {
            Text(
                "This period spans a system update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Diagnostic only. Ordinary copy above says the accounting changed rather than naming
        // a wakelock, because the wakelock did not cause it -- it is only where we noticed.
        detail.continuityDetail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Whether the two captures can be compared, and what that means.
 *
 * The three outcomes are written so they cannot be mistaken for each other. A refused pair
 * shows no numbers at all -- Phase 7B.1 established that one decreased counter makes every
 * counter in the capture untrustworthy, so a partial list would be showing figures already
 * known to be wrong.
 */
@Composable
private fun Comparison(detail: SessionDetail) {
    val reason = detail.unavailableReason
    when {
        reason != null -> {
            Field("Comparison", "Unavailable")
            Text(
                HistoryPresentation.unavailableReason(reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (reason == CounterDeltaReason.COUNTER_DECREASED) {
                Text(
                    "No wakelock figures are shown for this period, including ones that would " +
                        "otherwise look normal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        detail.captures?.baselineIsLatest == true -> {
            Field("Comparison", "Nothing to compare yet")
            Text(
                "Only one capture exists for this period. Capture again to see what changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        (detail.row.counters as? CounterAvailability.DeltaAvailable)?.allZero == true -> {
            Field("Comparison", "Available")
            Text(
                "No increase was recorded between these captures. That is a measurement, not " +
                    "missing data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> Field("Comparison", "Available")
    }
}

/**
 * The battery trend, or an honest explanation of why there is no trend to draw.
 *
 * The three states are genuinely different and are never collapsed: nothing sampled, samples
 * without a usable level, and samples too broken up to form a run. An empty chart frame would
 * make the user guess which one they were looking at.
 */
@Composable
private fun BatteryTrendSection(model: BatteryChartModel, formatDuration: (Long) -> String) {
    Section("Battery trend") {
        when {
            model.isEmpty -> Explain(
                "No sampled battery history yet. Readings are taken while BattInsight is open " +
                    "and on screen.",
            )

            model.hasNoUsablePercentage -> Explain(
                "Battery level was unavailable in these readings, so there is no trend to " +
                    "draw. The readings themselves were taken.",
            )

            model.hasNoConnectedEvidence -> {
                Explain(
                    "Not enough continuous observations to draw a battery trend. The readings " +
                        "below are shown where they happened.",
                )
                BatteryChart(model)
                BatteryChartLegend(model, formatDuration)
            }

            else -> {
                BatteryChart(model)
                BatteryChartLegend(model, formatDuration)
                Explain(
                    "Levels are shown where they were observed. A break means no reading was " +
                        "taken, not that the level stayed the same.",
                )
            }
        }
    }
}

/**
 * Wakelock activity per measured interval.
 *
 * The copy says "accumulated between readings" rather than anything resembling "ran for", and
 * that wording is load-bearing: the evidence is the difference between two cumulative counters
 * taken when the user pressed refresh. It says how much, not when within the window.
 */
@Composable
private fun CounterActivitySection(
    kernel: CounterChartModel?,
    application: CounterChartModel?,
    formatDuration: (Long) -> String,
) {
    if (kernel == null && application == null) return
    if (kernel?.isEmpty != false && application?.isEmpty != false) {
        Section("Wakelock activity over time") {
            Explain(
                "Only one set of statistics was captured during this period, so there is no " +
                    "interval to compare. Capture again to measure what accumulates between " +
                    "readings.",
            )
        }
        return
    }

    Section("Wakelock activity over time") {
        Explain(
            "Each block covers the period between two captures you took, and shows how much " +
                "wakelock time accumulated across that whole period.",
        )
        kernel?.takeIf { !it.isEmpty }?.let { CounterFamilyBlock("Kernel wakelocks", it, formatDuration) }
        application?.takeIf { !it.isEmpty }?.let { CounterFamilyBlock("Application wakelocks", it, formatDuration) }
    }
}

@Composable
private fun CounterFamilyBlock(
    title: String,
    model: CounterChartModel,
    formatDuration: (Long) -> String,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    CounterIntervalChart(model)
    model.comparable.forEach { interval ->
        Field(
            formatDuration(interval.spanMillis) + " interval",
            if (interval.isMeasuredZero) {
                // Mandatory distinction: a measured zero is a result, not an absence.
                "No increase recorded — that is a measurement, not missing data"
            } else {
                HistoryPresentation.duration(interval.totalDurationMillis) +
                    " across " + interval.totalCount + " acquisitions"
            },
        )
    }
    model.refused.forEach { interval ->
        Field(formatDuration(interval.spanMillis) + " interval", interval.description)
    }
}

@Composable
private fun Explain(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class DeltaRow(val label: String, val value: String)

@Composable
private fun DeltaSection(title: String, rows: List<DeltaRow>, emptyMessage: String) {
    Section(title) {
        if (rows.isEmpty()) {
            Text(
                emptyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Section
        }
        rows.forEach {
            Column {
                Text(
                    it.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    // Wakelock tags run to a hundred characters. Wrapping to two lines and
                    // then ellipsising keeps one long name from stretching the layout.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    it.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private const val TOP_N = 5

/** What the detail screen is showing. */
sealed interface DetailUiState {
    data object Loading : DetailUiState
    data object Missing : DetailUiState

    data class Loaded(
        val detail: SessionDetail,
        val formatWallClock: (Long) -> String,
        /** Current lookup only. Returns null when the UID resolves to nothing today. */
        val resolvePackage: (Int) -> String?,
        /**
         * The sampled battery series, already divided into what may be drawn and what may not.
         *
         * Null when this build has no series for the session -- a session recorded before
         * Phase 9B, for instance. The rest of the screen must stay useful without it, which is
         * why it is nullable rather than an empty model: "no chart section at all" and "a chart
         * section saying nothing was sampled" are different, and only the first is right for a
         * session that predates sampling.
         */
        val batteryChart: BatteryChartModel? = null,
        val kernelChart: CounterChartModel? = null,
        val applicationChart: CounterChartModel? = null,
        val formatDuration: (Long) -> String = { "" },
    ) : DetailUiState
}

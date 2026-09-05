package com.rmpsdroid.battinsight.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rmpsdroid.battinsight.history.CounterAvailability
import com.rmpsdroid.battinsight.history.HistoryPresentation
import com.rmpsdroid.battinsight.history.SessionHistoryRow

/**
 * Every battery period BattInsight has recorded, newest first.
 *
 * Rows are deliberately plain. A history list is scanned rather than read, so each row answers
 * three questions -- what kind of period, how long, and is there anything to look at -- and
 * leaves the rest to the detail screen.
 *
 * No identifiers. Session UUIDs are how the database finds a row, not something a person needs
 * to see, and putting them here would fill the screen with 36-character strings that mean
 * nothing.
 */
@Composable
fun SessionHistoryScreen(
    state: HistoryUiState,
    onOpenSession: (String) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Battery periods BattInsight has recorded on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state) {
            HistoryUiState.Loading -> Loading()
            is HistoryUiState.Empty -> EmptyHistory(state)
            is HistoryUiState.Loaded -> LoadedHistory(state, onOpenSession, onLoadMore)
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Back")
        }
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Reading saved history…", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Nothing recorded yet, said in a way that distinguishes the two reasons.
 *
 * "No sessions" because the app has only just been installed and "no sessions" because the
 * database could not be read are different facts, and a single empty screen would hide the
 * second one entirely.
 */
@Composable
private fun EmptyHistory(state: HistoryUiState.Empty) {
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.detail == null) {
                Text("No battery periods recorded yet", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A period starts when BattInsight first sees a battery reading, and ends " +
                        "when you plug in or unplug. Open the app occasionally and history " +
                        "will build up on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("History could not be read", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your saved periods may still be there — BattInsight could not read them " +
                        "just now. Nothing has been deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LoadedHistory(
    state: HistoryUiState.Loaded,
    onOpenSession: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        items(state.rows, key = { it.sessionId }) { row ->
            SessionRow(row, state.formatWallClock, onOpenSession)
        }
        if (state.canLoadMore) {
            item {
                OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                    Text("Show older periods")
                }
            }
        }
        item {
            Text(
                "${state.totalCount} period${if (state.totalCount == 1) "" else "s"} recorded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionRow(
    row: SessionHistoryRow,
    formatWallClock: (Long) -> String,
    onOpenSession: (String) -> Unit,
) {
    val title = HistoryPresentation.sessionTitle(row.type, row.isActive)
    val counters = HistoryPresentation.counterSummary(row.counters)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target rather than a small chevron, which keeps it well
            // above the minimum touch size without a separate affordance.
            .clickable { onOpenSession(row.sessionId) }
            .semantics {
                contentDescription = "$title, ${HistoryPresentation.duration(row.durationMillis)}, $counters"
            },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // Text, not a coloured dot: state must survive being read aloud and being
                // looked at by someone who cannot distinguish the colours.
                Text(
                    if (row.isActive) "Active" else "Completed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatWallClock(row.startWallClockMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${HistoryPresentation.duration(row.durationMillis)} · " +
                    HistoryPresentation.batteryRange(row.startBattery, row.endBattery),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                counters,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.counters is CounterAvailability.DeltaUnavailable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                // Wakelock names and reason clauses can be long. Ellipsis keeps one row from
                // widening the list; the detail screen shows the full text.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What the history screen is showing. */
sealed interface HistoryUiState {
    data object Loading : HistoryUiState

    /** @param detail non-null when the list is empty because reading failed. */
    data class Empty(val detail: String?) : HistoryUiState

    data class Loaded(
        val rows: List<SessionHistoryRow>,
        val totalCount: Int,
        val canLoadMore: Boolean,
        /** Injected so the screen has no locale or clock dependency of its own. */
        val formatWallClock: (Long) -> String,
    ) : HistoryUiState
}

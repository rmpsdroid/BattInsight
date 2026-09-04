package com.rmpsdroid.battinsight.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rmpsdroid.battinsight.persistence.StorageCounts
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.PersistenceOutcome
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionStatus
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType
import com.rmpsdroid.battinsight.session.TransitionResult

/**
 * What the session engine currently believes, for the Capability Centre.
 *
 * A status view, not the battery-history screen -- that arrives once there is data worth
 * charting. Its purpose now is that the engine's conclusions are visible and checkable
 * rather than only inspectable in tests.
 *
 * Identifiers are abbreviated by default and the full forms appear only behind *Details*.
 * A boot identifier and a session UUID are stable device-linked values, and putting them
 * on the main screen would clutter it while making them easy to copy into a bug report
 * without thinking.
 */
@Composable
fun SessionStatusSection(
    status: SessionStatus,
    storage: StorageCounts?,
    modifier: Modifier = Modifier,
) {
    var showDetails by remember { mutableStateOf(false) }
    val session = status.session

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // No title here: the Capability Centre already renders a section header, and
            // repeating it reads as a rendering bug rather than emphasis.
            if (session == null) {
                Text(
                    "No battery reading yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            Row("State", describeBatteryState(status))
            Row("Session", describeType(session.type))
            Row("Started", formatDuration(session.elapsedMillis) + " ago")
            Row("Last change", describeResult(status.lastResult))

            // Storage problems are shown on the face of the card rather than behind
            // Details. If measurements are not being saved, that changes what the numbers
            // above are worth, and a user should not have to go looking to find out.
            status.persistence?.failureOrNull?.let { failure ->
                Notice(describeWriteFailure(failure.outcome))
            }
            status.loadFailure?.let { failure ->
                Notice(describeLoadFailure(failure.outcome))
            }

            TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "Hide details" else "Details")
            }

            if (showDetails) {
                Row("Session ID", session.abbreviatedId)
                Row("Start-up ID", status.bootIdentity.abbreviated)
                Row("Counter generation", status.counterGeneration.toString())
                Row("Started by", describeTrigger(session.start.trigger))
                status.lastObservation?.let { observation ->
                    Row("Power", describePlug(observation.plug))
                    observation.levelPercent?.let { Row("Level", "$it%") }
                    Row(
                        "Captured at",
                        "+${observation.time.elapsedRealtime.millis} ms since start-up, " +
                            "UTC${formatOffset(observation.time.utcOffsetMinutes)}",
                    )
                    if (observation.statusContradictsPlug) {
                        Text(
                            "Android reported a status that disagrees with the power source. " +
                                "The power source is used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                storage?.let {
                    Row(
                        "Saved on this device",
                        "${it.sessions} ${plural(it.sessions, "session")}, " +
                            "${it.snapshots} ${plural(it.snapshots, "reading")}",
                    )
                }
                Text(
                    "Durations are measured from the device's start-up clock, so changing " +
                        "the time or timezone does not affect them. Sessions are saved on " +
                        "this device and are restored when the app reopens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Row(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (label.endsWith("ID")) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

private fun describeType(type: SessionType): String = when (type) {
    SessionType.DISCHARGE -> "On battery"
    SessionType.CHARGE -> "On external power"
    SessionType.UNKNOWN -> "Not established"
}

private fun describeBatteryState(status: SessionStatus): String {
    val observation = status.lastObservation ?: return "Unknown"
    val statusWord = when (observation.status) {
        BatteryStatus.CHARGING -> "Charging"
        BatteryStatus.DISCHARGING -> "Discharging"
        BatteryStatus.FULL -> "Full"
        BatteryStatus.NOT_CHARGING -> "Not charging"
        BatteryStatus.UNKNOWN -> "Unknown"
    }
    val level = observation.levelPercent?.let { " · $it%" } ?: ""
    return statusWord + level
}

private fun describePlug(plug: PlugSource): String = when (plug) {
    PlugSource.AC -> "Mains"
    PlugSource.USB -> "USB"
    PlugSource.WIRELESS -> "Wireless"
    PlugSource.DOCK -> "Dock"
    PlugSource.OTHER -> "Other supply"
    PlugSource.NONE -> "Not connected"
    PlugSource.UNKNOWN -> "Unknown"
}

/**
 * What the last observation did.
 *
 * An inferred boundary is labelled as inferred. The user is entitled to know that a
 * transition was reconstructed at start-up rather than witnessed, because it means the
 * moment it happened is approximate.
 */
private fun describeResult(result: TransitionResult?): String = when (result) {
    null -> "Nothing yet"
    is TransitionResult.Started -> "Session started (" + describeTrigger(result.trigger) + ")"
    is TransitionResult.Continued -> "Continued"
    is TransitionResult.Unchanged -> "No change"
    is TransitionResult.Boundary -> when (result.reason) {
        SessionBoundaryReason.POWER_TRANSITION -> "Power source changed"
        SessionBoundaryReason.BOOT_BOUNDARY -> "Device restarted"
        SessionBoundaryReason.RECOVERY -> "Change detected at start-up (not observed directly)"
        SessionBoundaryReason.UNPROVEN_CONTINUITY ->
            "Could not confirm this continues the previous session"
        SessionBoundaryReason.INCONSISTENT_STATE -> "Previous state could not be trusted"
        SessionBoundaryReason.NONE -> "Session changed"
    }
    is TransitionResult.Rejected -> "Reading ignored: " + result.detail
}

private fun describeTrigger(trigger: SessionTrigger): String = when (trigger) {
    SessionTrigger.APP_START -> "app opened"
    SessionTrigger.POWER_CONNECTED -> "power connected"
    SessionTrigger.POWER_DISCONNECTED -> "power disconnected"
    SessionTrigger.BATTERY_CHANGED -> "battery update"
    SessionTrigger.PERIODIC -> "scheduled check"
    SessionTrigger.MANUAL -> "manual"
    SessionTrigger.RECOVERY -> "reconstructed at start-up"
    SessionTrigger.BOOT_CHANGED -> "device restart"
    SessionTrigger.COUNTER_RESET -> "counter reset"
    SessionTrigger.UNKNOWN -> "unknown"
}

/** Whole units only. A session measured to the millisecond would be false precision. */
internal fun formatDuration(millis: Long): String {
    if (millis < 0) return "unknown"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

internal fun formatOffset(minutes: Int): String {
    val sign = if (minutes < 0) "-" else "+"
    val abs = kotlin.math.abs(minutes)
    // Locale.ROOT: a UTC offset is a machine-format value, not localised text, and
    // a locale with non-Latin digits would make it unreadable to the tools that
    // consume an export.
    return String.format(java.util.Locale.ROOT, "%s%02d:%02d", sign, abs / 60, abs % 60)
}

/**
 * A problem worth interrupting for.
 *
 * Deliberately plain rather than alarming: these states are recoverable and the application
 * still works, so the error colour is used for the text and not as a filled background.
 */
@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun plural(count: Int, noun: String) = if (count == 1) noun else "${noun}s"

/**
 * What a failed write means, said in the user's terms.
 *
 * No SQL, no exception text and no error codes -- a person told `FOREIGN KEY constraint
 * failed (787)` has been told nothing. Each string says the same two things: what is not
 * happening, and whether the numbers currently on screen can still be trusted. The
 * underlying detail is kept on [PersistenceResult.Failure] for bug reports.
 */
private fun describeWriteFailure(outcome: PersistenceOutcome): String = when (outcome) {
    PersistenceOutcome.SUCCESS -> ""
    PersistenceOutcome.DATABASE_UNAVAILABLE ->
        "This session is not being saved: BattInsight cannot reach its storage. " +
            "What you see here is correct but will be lost when the app closes."
    PersistenceOutcome.MIGRATION_FAILURE ->
        "This session is not being saved: saved history is from a newer version of " +
            "BattInsight and cannot be updated. Your existing history has been left alone."
    PersistenceOutcome.CONSTRAINT_FAILURE, PersistenceOutcome.MAPPING_FAILURE,
    PersistenceOutcome.CORRUPT_STATE, PersistenceOutcome.UNKNOWN ->
        "This session is not being saved because of a problem in BattInsight. " +
            "What you see here is correct but will be lost when the app closes."
}

/**
 * What a failed *load* means, which is a different statement.
 *
 * The distinction is the point of [StoredState.Failed] existing at all: an unreadable store
 * is not an empty one. Saying "no history" here would be a false claim about the user's
 * device, and would be the predecessor's exact behaviour.
 */
private fun describeLoadFailure(outcome: PersistenceOutcome): String = when (outcome) {
    PersistenceOutcome.MIGRATION_FAILURE ->
        "Earlier history could not be opened: it was saved by a newer version of " +
            "BattInsight. Nothing has been deleted."
    else ->
        "Earlier history could not be read, so this session starts fresh. " +
            "Nothing has been deleted."
}

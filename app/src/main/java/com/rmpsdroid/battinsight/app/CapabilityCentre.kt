package com.rmpsdroid.battinsight.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.capability.CapabilityFinding
import com.rmpsdroid.battinsight.capability.CapabilityReport
import com.rmpsdroid.battinsight.capability.CapabilityState
import com.rmpsdroid.battinsight.collection.BackendStatus
import com.rmpsdroid.battinsight.permissions.PermissionStatus
import com.rmpsdroid.battinsight.persistence.StorageCounts
import com.rmpsdroid.battinsight.session.SessionStatus

/**
 * A developer-facing view of what BattInsight can currently do.
 *
 * Observation, plus a way out to setup. The screen itself still changes nothing: it shows
 * what the capability layer concluded and offers navigation to *Manage access*, where any
 * change is made deliberately and with confirmation. Keeping the diagnostics view free of
 * one-tap privileged actions is what stops it becoming a hidden control panel.
 *
 * Nothing is hidden for looking bad. Capabilities with no probe yet are listed as Unknown
 * rather than omitted, because a missing row reads as a capability that does not exist.
 *
 * Every row states a capability, its state, and a specific reason. "Check your permissions"
 * with no detail is the message both predecessor applications shipped, and is the reason
 * their users could not distinguish a missing permission from an unsupported kernel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilityCentreScreen(
    report: CapabilityReport,
    mode: AccessMode,
    sessionStatus: SessionStatus,
    storageCounts: StorageCounts?,
    collectorState: CollectorUiState,
    counterState: CounterUiState,
    onCapture: () -> Unit,
    onRefresh: () -> Unit,
    onManageAccess: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BattInsight", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Capability Centre",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (report.refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 16.dp).height(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = onRefresh) { Text("Refresh") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("Battery session") }
            item { SessionStatusSection(sessionStatus, storageCounts) }
            item { CoreCollectorSection(collectorState, counterState, onCapture) }

            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Access")
            }
            item {
                PlainRow(
                    title = "Access method",
                    detail = mode.label + " · " + report.selection.reason,
                )
            }
            item {
                PlainRow(
                    title = "Preferred backend",
                    detail = report.selection.preferred?.displayName
                        ?: "None chosen",
                )
            }
            item {
                PlainRow(
                    title = "Active backend",
                    detail = report.selection.active?.displayName
                        ?: "None. " + report.selection.reason,
                )
            }
            report.selection.fallbackOffer?.let { offer ->
                item {
                    PlainRow(
                        title = "Available alternative",
                        detail = "${offer.displayName} would work, but is not being used " +
                            "because you chose otherwise. Change it under Manage access.",
                    )
                }
            }
            item {
                // History first: it is the thing a person opens the app to look at, whereas
                // access management is something they do once.
                Button(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("Battery history")
                }
            }
            item {
                Button(onClick = onManageAccess, modifier = Modifier.fillMaxWidth()) {
                    Text("Manage access")
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Access backends")
            }
            items(report.backends) { BackendRow(it) }

            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Shizuku")
            }
            item { PlainRow(title = "State", detail = report.shizuku.nextStep) }

            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Permissions")
            }
            items(report.permissions.statuses) { PermissionRow(it) }
            item {
                PlainRow(
                    title = "Usage access app-op",
                    detail = report.permissions.usageStatsAppOp.name +
                        if (report.permissions.usageAccessExpected) " (access expected)" else "",
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Capabilities")
            }
            items(report.findings) { CapabilityRow(it) }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Capabilities with no probe yet are shown as Unknown rather " +
                        "than hidden. Setup changes are made under Manage access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun BackendRow(status: BackendStatus) {
    val label = if (status.isUsable) "USABLE" else if (status.kind.implemented) "UNAVAILABLE" else "NOT IMPLEMENTED"
    StateCard(
        title = status.kind.displayName,
        badge = label,
        badgeColour = if (status.isUsable) Ok else Neutral,
        detail = status.summary,
    )
}

@Composable
private fun PermissionRow(status: PermissionStatus) {
    StateCard(
        title = status.permission.manifestName.substringAfterLast('.'),
        badge = status.grant.name,
        badgeColour = if (status.isGranted) Ok else Warn,
        detail = status.permission.why,
    )
}

@Composable
private fun CapabilityRow(finding: CapabilityFinding) {
    val (badge, colour) = finding.state.badge()
    StateCard(
        title = finding.capability.name.replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() },
        badge = badge,
        badgeColour = colour,
        detail = finding.reason + (finding.viaBackend?.let { " · via ${it.displayName}" } ?: ""),
    )
}

@Composable
private fun PlainRow(title: String, detail: String) =
    StateCard(title = title, badge = null, badgeColour = Neutral, detail = detail)

/**
 * One row.
 *
 * Text wraps rather than truncating, and no width is fixed. Two of the defects recorded in
 * the predecessor's tracker were clipped text on an ordinary phone and a broken title at
 * large font scale; both came from layouts that assumed a width and a text size.
 */
@Composable
private fun StateCard(title: String, badge: String?, badgeColour: Color, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColour,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Maps a capability state to a short label.
 *
 * `AVAILABLE (NO EVENTS)` and `DEGRADED` are surfaced as distinct from `AVAILABLE`
 * precisely because collapsing them is what produced uninformative empty screens.
 */
private fun CapabilityState.badge(): Pair<String, Color> = when (this) {
    CapabilityState.Available -> "AVAILABLE" to Ok
    is CapabilityState.AvailableNoEvents -> "AVAILABLE (NO EVENTS)" to Ok
    is CapabilityState.AvailableDegraded -> "DEGRADED" to Warn
    is CapabilityState.PermissionMissing -> "PERMISSION MISSING" to Warn
    is CapabilityState.NotSupported -> "NOT SUPPORTED" to Neutral
    is CapabilityState.SourceUnavailable -> "SOURCE UNAVAILABLE" to Neutral
    is CapabilityState.ExecutionFailed -> "FAILED" to Bad
    CapabilityState.Unknown -> "UNKNOWN" to Neutral
}

private val Ok = Color(0xFF1B7F4B)
private val Warn = Color(0xFF9A6700)
private val Bad = Color(0xFFB3261E)
private val Neutral = Color(0xFF6B6B6B)

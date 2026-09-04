package com.rmpsdroid.battinsight.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.capability.CapabilityReport
import com.rmpsdroid.battinsight.permissions.PermissionGrant
import com.rmpsdroid.battinsight.setup.GrantStep
import com.rmpsdroid.battinsight.setup.ManualAdbInstructions

/**
 * Where the user sees and changes what access BattInsight holds.
 *
 * Removal is a first-class feature, not an afterthought. An application that can talk a
 * user into elevating its privileges should be at least as good at giving them back, and
 * being able to see exactly what is held — with a way to remove it — is part of deserving
 * the grant in the first place.
 *
 * Revocation touches only BattInsight's own three permissions. No app-op is changed, and no
 * other package can be named: the typed setup actions fix the target at compile time.
 */
@Composable
fun ManageAccessScreen(
    mode: AccessMode,
    report: CapabilityReport,
    revokeAvailable: Boolean,
    revoking: Boolean,
    lastRevokeResult: List<GrantStep>?,
    onChangeAccessMethod: () -> Unit,
    onRevoke: () -> Unit,
    onCopyRevokeCommands: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf(false) }
    val heldPermissions = report.permissions.statuses.filter { it.isGranted }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Manage access",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )

        InfoCard("Access method", mode.label, describeMode(mode))
        InfoCard(
            "Active backend",
            report.selection.active?.displayName ?: "None",
            report.selection.reason,
        )

        Text(
            text = "Permissions BattInsight holds",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )

        if (heldPermissions.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "None. BattInsight holds no elevated Android permissions.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            report.permissions.statuses.forEach { status ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = status.permission.shortLabel + " — " +
                                when (status.grant) {
                                    PermissionGrant.GRANTED -> "Held"
                                    PermissionGrant.DENIED -> "Not held"
                                    PermissionGrant.UNKNOWN -> "Unknown"
                                },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = status.permission.manifestName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (heldPermissions.isNotEmpty()) {
            Text(
                text = "Remove access",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (revokeAvailable) {
                Text(
                    "Shizuku is available, so BattInsight can remove these itself.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (revoking) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
                } else {
                    Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Remove these permissions")
                    }
                }
            } else {
                Text(
                    "Shizuku is not available, so run these from a computer instead.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ManualAdbInstructions.revokeCommands.forEach { command ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = command,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onCopyRevokeCommands(ManualAdbInstructions.revokeBlock()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy commands")
                }
            }
        }

        lastRevokeResult?.let { steps ->
            Text(
                text = "Last removal",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            steps.forEach { step ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = step.permission.shortLabel + " — " +
                                if (step.succeeded) "Removed" else "Not removed",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = step.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Text(
            text = "Shizuku's own permission",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Shizuku decides for itself which apps it will work with, and keeps that list " +
                "inside its own app. To withdraw BattInsight's access to Shizuku, change it " +
                "in Shizuku. BattInsight does not modify Shizuku's settings.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onChangeAccessMethod, modifier = Modifier.fillMaxWidth()) {
            Text("Change access method")
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        Spacer(Modifier.height(16.dp))
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove these permissions?") },
            text = {
                Text(
                    "BattInsight will remove its own DUMP, PACKAGE_USAGE_STATS and " +
                        "INTERACT_ACROSS_USERS permissions. Detailed diagnostics will stop " +
                        "working until you set access up again. No other app is affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onRevoke()
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InfoCard(label: String, value: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.titleMedium)
            Text(text = detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun describeMode(mode: AccessMode): String = when (mode) {
    AccessMode.SHIZUKU_LIVE ->
        "Diagnostics run through Shizuku. BattInsight holds no elevated permissions itself."
    AccessMode.GRANTED_APP ->
        "BattInsight holds three permissions and works without Shizuku running."
    AccessMode.LIMITED ->
        "No privileged access. Detailed diagnostics are unavailable."
    AccessMode.NOT_CHOSEN ->
        "No access method has been chosen yet."
}

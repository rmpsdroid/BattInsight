package com.rmpsdroid.battinsight.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.setup.GrantStep
import com.rmpsdroid.battinsight.setup.ManualAdbInstructions
import com.rmpsdroid.battinsight.setup.SetupState

/**
 * Onboarding and access setup.
 *
 * One screen function per state, dispatched from [SetupState]. The screens compute nothing:
 * every decision has already been made by `AccessSetupCoordinator`, so what is displayed
 * cannot disagree with what the application will actually do.
 *
 * ## Tone
 *
 * No route is described as unsafe because it is not the recommended one. Shizuku is
 * recommended because it was measured faster and leaves BattInsight holding no elevated
 * permissions — that is a reason, and it is stated. The alternatives are presented as
 * choices with trade-offs, not as risks.
 *
 * Declining is never presented as an error, and nothing here reopens a dialog by itself.
 *
 * ## Vocabulary
 *
 * The primary path avoids implementation terms. "SELinux domain", "Binder" and "AIDL" are
 * facts about how this works, not things a person needs in order to decide, and they live
 * in the Capability Centre instead.
 */
@Composable
fun SetupScreen(
    state: SetupState,
    actions: SetupActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            SetupState.Welcome -> Welcome(actions)
            SetupState.ChoosingAccess -> ChooseAccess(actions)

            SetupState.ShizukuNotInstalled -> ShizukuNotInstalled(actions)
            is SetupState.ShizukuStopped -> ShizukuStopped(state, actions)
            is SetupState.ShizukuUnauthorised -> ShizukuUnauthorised(actions)
            is SetupState.ShizukuReady -> ShizukuReady(state, actions)
            is SetupState.ShizukuUnsupported -> ShizukuUnsupported(state, actions)

            SetupState.GrantConfirmation -> GrantConfirmation(actions)
            is SetupState.GrantInProgress -> GrantInProgress(state)
            is SetupState.GrantFailed -> GrantFailed(state, actions)

            SetupState.ManualAdb -> ManualAdb(actions)
            SetupState.Verifying -> Verifying()

            is SetupState.Ready -> Ready(state, actions)
            is SetupState.Limited -> Limited(state, actions)
            is SetupState.VerificationFailed -> VerificationFailed(state, actions)
            is SetupState.Error -> ErrorState(state, actions)
        }
    }
}

/** Everything a setup screen can ask for. Named by what the user is doing. */
data class SetupActions(
    val chooseShizuku: () -> Unit,
    val chooseIndependentAccess: () -> Unit,
    val chooseManualAdb: () -> Unit,
    val continueWithoutSetup: () -> Unit,
    val openShizukuWebsite: () -> Unit,
    val openShizukuApp: () -> Unit,
    val authoriseShizuku: () -> Unit,
    val useShizuku: () -> Unit,
    val confirmGrants: () -> Unit,
    val copyCommands: (String) -> Unit,
    val verifySetup: () -> Unit,
    val retry: () -> Unit,
    val back: () -> Unit,
    val openCapabilityCentre: () -> Unit,
)

// ------------------------------------------------------------------------------ screens

@Composable
private fun Welcome(actions: SetupActions) {
    Heading("BattInsight")
    Body(
        "Battery diagnostics need privileged access to Android's system statistics. " +
            "Android does not offer these through an ordinary permission prompt.",
    )
    Body("BattInsight supports several access methods. You choose which one to use.")

    Spacer(Modifier.height(8.dp))

    ChoiceCard(
        label = "Recommended",
        title = "Use Shizuku",
        body = "BattInsight reads statistics through Shizuku while it is running. " +
            "BattInsight itself receives no elevated permissions.",
        buttonText = "Set up Shizuku",
        onClick = actions.chooseShizuku,
    )

    ChoiceCard(
        label = "Alternative",
        title = "Set up independent access",
        body = "Use Shizuku once to give BattInsight three Android permissions. " +
            "After that it works on its own, without Shizuku running.",
        buttonText = "See what this grants",
        onClick = actions.chooseIndependentAccess,
    )

    ChoiceCard(
        label = "Advanced",
        title = "Use ADB commands",
        body = "Run three commands from a computer to give BattInsight the same three " +
            "permissions. No extra app needed.",
        buttonText = "Show the commands",
        onClick = actions.chooseManualAdb,
    )

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = actions.continueWithoutSetup, modifier = Modifier.fillMaxWidth()) {
        Text("Explore without setup")
    }
    Caption(
        "You can look around, but detailed battery diagnostics will not be available. " +
            "You can set up access later.",
    )
}

@Composable
private fun ChooseAccess(actions: SetupActions) {
    Heading("Choose an access method")
    Body("You can change this at any time.")

    ChoiceCard(
        label = "Recommended",
        title = "Use Shizuku",
        body = "Faster, resolves app names more completely, and leaves BattInsight " +
            "holding no elevated permissions. Shizuku must be running, and usually needs " +
            "starting again after a reboot.",
        buttonText = "Set up Shizuku",
        onClick = actions.chooseShizuku,
    )
    ChoiceCard(
        label = "Alternative",
        title = "Independent access",
        body = "BattInsight keeps three Android permissions until you remove them, and " +
            "works without Shizuku running.",
        buttonText = "See what this grants",
        onClick = actions.chooseIndependentAccess,
    )
    ChoiceCard(
        label = "Advanced",
        title = "ADB commands",
        body = "The same three permissions, granted from a computer.",
        buttonText = "Show the commands",
        onClick = actions.chooseManualAdb,
    )

    TextButton(onClick = actions.continueWithoutSetup, modifier = Modifier.fillMaxWidth()) {
        Text("Continue without setup")
    }
}

@Composable
private fun ShizukuNotInstalled(actions: SetupActions) {
    Heading("Shizuku is not installed")
    Body(
        "Shizuku is a separate, independent open-source project. It gives apps " +
            "shell-level access to Android without rooting the device.",
    )
    Body(
        "It is not part of BattInsight, and BattInsight cannot install it for you. " +
            "You install it yourself from its official site.",
    )
    Button(onClick = actions.openShizukuWebsite, modifier = Modifier.fillMaxWidth()) {
        Text("Open the Shizuku website")
    }
    Caption("This opens your browser. BattInsight has no internet access of its own.")
    SecondaryActions(actions, checkAgain = true)
}

@Composable
private fun ShizukuStopped(state: SetupState.ShizukuStopped, actions: SetupActions) {
    Heading("Shizuku is installed but not running")
    Body(
        "Open Shizuku and start its service. Shizuku will guide you through pairing " +
            "with your computer or with wireless debugging.",
    )
    Body(
        "Shizuku usually needs starting again after the device restarts. BattInsight " +
            "cannot start it, and does not change your developer settings.",
    )
    state.versionName?.let { Caption("Installed version $it") }
    Button(onClick = actions.openShizukuApp, modifier = Modifier.fillMaxWidth()) {
        Text("Open Shizuku")
    }
    SecondaryActions(actions, checkAgain = true)
}

@Composable
private fun ShizukuUnauthorised(actions: SetupActions) {
    Heading("Shizuku needs to allow BattInsight")
    Body(
        "Shizuku keeps its own list of apps it will work with. Tap below and Shizuku " +
            "will ask you whether to allow BattInsight.",
    )
    Body("If you decline, nothing changes and you can choose another method.")
    Button(onClick = actions.authoriseShizuku, modifier = Modifier.fillMaxWidth()) {
        Text("Ask Shizuku to allow BattInsight")
    }
    SecondaryActions(actions, checkAgain = true)
}

@Composable
private fun ShizukuReady(state: SetupState.ShizukuReady, actions: SetupActions) {
    Heading("Shizuku is ready")
    Body(
        if (state.verified) {
            "BattInsight can run diagnostics through Shizuku. Choose how you want to use it."
        } else {
            "Shizuku has allowed BattInsight, but a test command did not complete. " +
                "You can still continue, and BattInsight will tell you if reading fails."
        },
    )

    ChoiceCard(
        label = "Recommended",
        title = "Use Shizuku",
        body = "BattInsight reads statistics through Shizuku each time. It receives no " +
            "Android permissions of its own. Requires Shizuku to be running.",
        buttonText = "Use Shizuku",
        onClick = actions.useShizuku,
    )
    ChoiceCard(
        label = "Alternative",
        title = "Grant BattInsight independent access",
        body = "Use Shizuku once to give BattInsight three permissions, so it works " +
            "without Shizuku afterwards. You can remove them later.",
        buttonText = "See what this grants",
        onClick = actions.chooseIndependentAccess,
    )
    SecondaryActions(actions, checkAgain = true)
}

@Composable
private fun ShizukuUnsupported(state: SetupState.ShizukuUnsupported, actions: SetupActions) {
    Heading("This Shizuku version is too old")
    Body(
        "BattInsight needs Shizuku version ${state.minimumSupported} or newer, and this " +
            "device is running version ${state.serverVersion}. Updating Shizuku should " +
            "resolve it.",
    )
    Button(onClick = actions.openShizukuWebsite, modifier = Modifier.fillMaxWidth()) {
        Text("Open the Shizuku website")
    }
    SecondaryActions(actions, checkAgain = true)
}

@Composable
private fun GrantConfirmation(actions: SetupActions) {
    Heading("Grant BattInsight independent access")
    Body(
        "BattInsight will use Shizuku once to give itself the three permissions below. " +
            "It will keep them until you remove them.",
    )

    RequiredPermission.entries.forEach { PermissionExplanation(it) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What will not happen", style = MaterialTheme.typography.titleSmall)
            Bullet(
                "BATTERY_STATS is not requested. Testing showed it makes no difference, " +
                    "even though similar apps ask for it.",
            )
            Bullet("No app permission settings are changed apart from these three.")
            Bullet("No root access is used, and none is needed.")
            Bullet("No other app is affected.")
        }
    }

    Button(onClick = actions.confirmGrants, modifier = Modifier.fillMaxWidth()) {
        Text("Grant these 3 permissions")
    }
    TextButton(onClick = actions.back, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
}

@Composable
private fun GrantInProgress(state: SetupState.GrantInProgress) {
    Heading("Setting up access")
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(1.dp))
        Text(
            text = "  Granting ${state.current.shortLabel}…",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics {
                contentDescription = "Granting ${state.current.shortLabel}, please wait"
            },
        )
    }
    state.completed.forEach { StepRow(it) }
}

@Composable
private fun GrantFailed(state: SetupState.GrantFailed, actions: SetupActions) {
    Heading("Setup stopped")
    Body(
        "BattInsight stopped at ${state.failed.permission.shortLabel} and did not " +
            "continue, so nothing further was changed.",
    )
    state.completed.forEach { StepRow(it) }
    StepRow(state.failed)
    Body(state.failed.detail)
    Body("You can try again, or use the ADB commands from a computer instead.")
    Button(onClick = actions.retry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
    OutlinedButton(onClick = actions.chooseManualAdb, modifier = Modifier.fillMaxWidth()) {
        Text("Show ADB commands")
    }
    TextButton(onClick = actions.back, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun ManualAdb(actions: SetupActions) {
    Heading("Set up with ADB")
    Body(
        "Connect this device to a computer with ADB installed, then run these three " +
            "commands. BattInsight cannot run them itself.",
    )

    ManualAdbInstructions.explanations().forEach { (command, explanation) ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Button(
        onClick = { actions.copyCommands(ManualAdbInstructions.grantBlock()) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Copy commands")
    }
    OutlinedButton(onClick = actions.verifySetup, modifier = Modifier.fillMaxWidth()) {
        Text("Verify setup")
    }
    Caption(
        "Verifying reads battery statistics for real. BattInsight reports success only " +
            "if that works, not just because the permissions look granted.",
    )
    TextButton(onClick = actions.back, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun Verifying() {
    Heading("Checking access")
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        Text("  Reading battery statistics…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Ready(state: SetupState.Ready, actions: SetupActions) {
    Heading(
        when (state.mode) {
            AccessMode.SHIZUKU_LIVE -> "Shizuku access is ready"
            AccessMode.GRANTED_APP -> "Independent access is ready"
            else -> "Access is ready"
        },
    )
    Body(state.detail)
    Caption("Verified by actually reading battery statistics, not just by checking settings.")
    Button(onClick = actions.openCapabilityCentre, modifier = Modifier.fillMaxWidth()) {
        Text("Continue")
    }
}

@Composable
private fun Limited(state: SetupState.Limited, actions: SetupActions) {
    Heading("Limited mode")
    Body(state.reason)
    Body(
        "Detailed battery diagnostics are not available in this mode. Basic battery " +
            "information still is.",
    )
    Button(onClick = actions.openCapabilityCentre, modifier = Modifier.fillMaxWidth()) {
        Text("Continue")
    }
    OutlinedButton(onClick = actions.back, modifier = Modifier.fillMaxWidth()) {
        Text("Set up access")
    }
}

@Composable
private fun VerificationFailed(state: SetupState.VerificationFailed, actions: SetupActions) {
    Heading("Setup verification failed")
    Body(
        "Something is inconsistent, and BattInsight is not going to pretend otherwise.",
    )
    Body(state.detail)
    Button(onClick = actions.verifySetup, modifier = Modifier.fillMaxWidth()) {
        Text("Check again")
    }
    OutlinedButton(onClick = actions.back, modifier = Modifier.fillMaxWidth()) {
        Text("Choose a different method")
    }
    TextButton(onClick = actions.openCapabilityCentre, modifier = Modifier.fillMaxWidth()) {
        Text("See the details")
    }
}

@Composable
private fun ErrorState(state: SetupState.Error, actions: SetupActions) {
    Heading("Something went wrong")
    Body(state.detail)
    Button(onClick = actions.retry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
    TextButton(onClick = actions.back, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

// ------------------------------------------------------------------------------- pieces

@Composable
private fun SecondaryActions(actions: SetupActions, checkAgain: Boolean) {
    if (checkAgain) {
        OutlinedButton(onClick = actions.retry, modifier = Modifier.fillMaxWidth()) {
            Text("Check again")
        }
    }
    TextButton(onClick = actions.back, modifier = Modifier.fillMaxWidth()) {
        Text("Choose a different method")
    }
}

@Composable
private fun PermissionExplanation(permission: RequiredPermission) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(permission.shortLabel, style = MaterialTheme.typography.titleSmall)
            Text(
                text = permission.plainExplanation,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = permission.manifestName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepRow(step: GrantStep) {
    // The word carries the meaning; colour only reinforces it, so this stays readable
    // without colour vision and in a screen reader.
    val label = when (step.verdict) {
        GrantStep.Verdict.CHANGED -> "Granted"
        GrantStep.Verdict.ALREADY_HELD -> "Already granted"
        GrantStep.Verdict.REMOVED -> "Removed"
        GrantStep.Verdict.FAILED -> "Failed"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "${step.permission.shortLabel} — $label",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    label: String,
    title: String,
    body: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(buttonText) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Bullet(text: String) {
    Text(text = "• $text", style = MaterialTheme.typography.bodySmall)
}

/** Short, human name for a permission. The manifest name is shown separately. */
internal val RequiredPermission.shortLabel: String
    get() = when (this) {
        RequiredPermission.DUMP -> "Read system diagnostics"
        RequiredPermission.PACKAGE_USAGE_STATS -> "Read app usage"
        RequiredPermission.INTERACT_ACROSS_USERS -> "Read across user profiles"
    }

/** Plain-language explanation, free of implementation vocabulary. */
internal val RequiredPermission.plainExplanation: String
    get() = when (this) {
        RequiredPermission.DUMP ->
            "Lets BattInsight read Android's own diagnostic reports, including the battery " +
                "statistics service that all of this depends on."
        RequiredPermission.PACKAGE_USAGE_STATS ->
            "Lets BattInsight see how long apps have been used. Android's battery " +
                "statistics path requires it before it will return anything."
        RequiredPermission.INTERACT_ACROSS_USERS ->
            "Android's battery statistics service asks for data across user profiles, even " +
                "on a device with only one. Without this it refuses the request."
    }

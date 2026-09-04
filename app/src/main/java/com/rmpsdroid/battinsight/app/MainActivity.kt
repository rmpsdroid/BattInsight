package com.rmpsdroid.battinsight.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.capability.CapabilityCoordinator
import com.rmpsdroid.battinsight.capability.CapabilityReport
import com.rmpsdroid.battinsight.collection.AccessModeBackendSelector
import com.rmpsdroid.battinsight.platform.AndroidAccessPreferenceStore
import com.rmpsdroid.battinsight.platform.AndroidBatterySource
import com.rmpsdroid.battinsight.platform.AndroidBatteryPropertySource
import com.rmpsdroid.battinsight.platform.AndroidBootIdentitySource
import com.rmpsdroid.battinsight.platform.AndroidPackageResolutionSource
import com.rmpsdroid.battinsight.platform.AndroidPermissionStateReader
import com.rmpsdroid.battinsight.platform.AndroidShizukuGateway
import com.rmpsdroid.battinsight.platform.AndroidUsageAccessSource
import com.rmpsdroid.battinsight.platform.GrantedAppProcessRunner
import com.rmpsdroid.battinsight.setup.AccessSetupCoordinator
import com.rmpsdroid.battinsight.setup.GrantStep
import com.rmpsdroid.battinsight.session.SessionCoordinator
import com.rmpsdroid.battinsight.session.SessionStatus
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.setup.SetupState
import com.rmpsdroid.battinsight.shizuku.ShizukuGateway
import com.rmpsdroid.battinsight.shizuku.ShizukuUserServiceRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which part of the application is on screen. */
sealed interface Screen {
    data object Setup : Screen
    data object CapabilityCentre : Screen
    data object ManageAccess : Screen
}

/**
 * Holds the coordinators across configuration changes.
 *
 * Wiring is manual on purpose: swapping backends in tests needs constructor injection, and
 * a dependency-injection framework would not yet earn its weight.
 */
class BattInsightViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val gateway = AndroidShizukuGateway(appContext)
    private val preferences = AndroidAccessPreferenceStore(appContext)

    /** Bound on demand and released with the ViewModel, so nothing outlives the screen. */
    private val shizukuRunner = ShizukuUserServiceRunner(appContext, gateway)
    private val grantedAppRunner = GrantedAppProcessRunner()

    /**
     * The last known access choice.
     *
     * Kept as a field because the backend selector is called synchronously from inside a
     * capability evaluation, which cannot suspend to read a preference. It is refreshed
     * from the store whenever the preference changes, so it tracks the persisted value.
     */
    @Volatile
    private var accessMode: AccessMode = AccessMode.NOT_CHOSEN

    private val capability = CapabilityCoordinator(
        grantedAppRunner = grantedAppRunner,
        shizukuRunner = shizukuRunner,
        shizukuGateway = gateway,
        permissionReader = AndroidPermissionStateReader(appContext),
        batterySource = AndroidBatteryPropertySource(appContext),
        usageSource = AndroidUsageAccessSource(appContext),
        packageSource = AndroidPackageResolutionSource(appContext),
        scope = viewModelScope,
        backendSelector = { shizuku, grantedApp ->
            AccessModeBackendSelector(accessMode).select(shizuku, grantedApp)
        },
    )

    private val setup = AccessSetupCoordinator(
        preferences = preferences,
        permissionReader = AndroidPermissionStateReader(appContext),
        shizukuGateway = gateway,
        setupExecutor = shizukuRunner,
        grantedAppRunner = grantedAppRunner,
        shizukuRunner = shizukuRunner,
        scope = viewModelScope,
    )

    /**
     * The battery session engine.
     *
     * Deliberately independent of the capability and access layers. A battery interval is a
     * fact about the device, and nothing about which backend is selected, or which
     * permissions are held, may move it.
     */
    private val batterySource = AndroidBatterySource(appContext, AndroidBootIdentitySource())

    private val sessions = SessionCoordinator(scope = viewModelScope)

    val report: StateFlow<CapabilityReport> = capability.report
    val setupState: StateFlow<SetupState> = setup.state
    val sessionStatus: StateFlow<SessionStatus> = sessions.status

    private val _screen = MutableStateFlow<Screen>(Screen.Setup)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _mode = MutableStateFlow(AccessMode.NOT_CHOSEN)
    val mode: StateFlow<AccessMode> = _mode.asStateFlow()

    private val _revoking = MutableStateFlow(false)
    val revoking: StateFlow<Boolean> = _revoking.asStateFlow()

    private val _lastRevoke = MutableStateFlow<List<GrantStep>?>(null)
    val lastRevoke: StateFlow<List<GrantStep>?> = _lastRevoke.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.accessMode.collect { stored ->
                accessMode = stored
                _mode.value = stored
                // A user who has already chosen goes straight to the application; only a
                // fresh install is asked the question.
                if (_screen.value == Screen.Setup && stored.isChosen && !setup.state.value.isTransient) {
                    _screen.value = Screen.CapabilityCentre
                }
                capability.refresh()
            }
        }
        setup.refresh()

        // Establish session state from a current reading, then follow live transitions.
        // Reconciliation happens first and only once: it is the only thing that can account
        // for what changed while the process did not exist.
        viewModelScope.launch {
            batterySource.readCurrent(SessionTrigger.APP_START)?.let { sessions.begin(it) }
            batterySource.observations().collect { sessions.observe(it) }
        }
    }

    // ------------------------------------------------------------------------ navigation

    fun openCapabilityCentre() {
        _screen.value = Screen.CapabilityCentre
        capability.refresh()
    }

    fun openManageAccess() {
        _screen.value = Screen.ManageAccess
        capability.refresh()
    }

    fun openSetup() {
        _screen.value = Screen.Setup
        setup.openAccessChoice()
    }

    fun refreshCapabilities() = capability.refresh()

    // ------------------------------------------------------------------- setup delegates

    fun chooseShizukuRoute() = setup.openShizukuRoute()
    fun openGrantConfirmation() = setup.openGrantConfirmation()
    fun openManualAdb() = setup.openManualAdb()
    fun requestShizukuAuthorisation() = setup.requestShizukuAuthorisation()
    fun confirmGrants() = setup.grantIndependentAccess()
    fun verifySetup() = setup.verifySetup()
    fun refreshSetup() = setup.refresh()

    fun continueWithoutSetup() {
        setup.choose(AccessMode.LIMITED)
        _screen.value = Screen.CapabilityCentre
    }

    fun useShizukuLive() {
        viewModelScope.launch {
            setup.choose(AccessMode.SHIZUKU_LIVE)
            capability.refresh()
        }
    }

    fun backToChoice() = setup.openAccessChoice()

    /** Removes BattInsight's own three permissions. Confirmed by the caller first. */
    fun revokeIndependentAccess() {
        if (_revoking.value) return
        viewModelScope.launch {
            _revoking.value = true
            try {
                _lastRevoke.value = setup.revokeIndependentAccess()
            } finally {
                _revoking.value = false
            }
            capability.refresh()
            setup.refresh()
        }
    }

    override fun onCleared() {
        shizukuRunner.release()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BattInsightViewModel(context.applicationContext) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BattInsightApp() }
    }
}

@Composable
private fun BattInsightApp() {
    MaterialTheme {
        val context = androidx.compose.ui.platform.LocalContext.current
        val vm: BattInsightViewModel = viewModel(
            factory = BattInsightViewModel.Factory(context),
        )
        val screen by vm.screen.collectAsStateWithLifecycle()
        val report by vm.report.collectAsStateWithLifecycle()
        val setupState by vm.setupState.collectAsStateWithLifecycle()
        val mode by vm.mode.collectAsStateWithLifecycle()
        val revoking by vm.revoking.collectAsStateWithLifecycle()
        val lastRevoke by vm.lastRevoke.collectAsStateWithLifecycle()
        val sessionStatus by vm.sessionStatus.collectAsStateWithLifecycle()

        // Leaving a secondary screen goes back to the main one rather than out of the app.
        BackHandler(enabled = screen != Screen.CapabilityCentre && mode.isChosen) {
            vm.openCapabilityCentre()
        }

        when (screen) {
            Screen.Setup -> SetupScreen(
                state = setupState,
                actions = SetupActions(
                    chooseShizuku = vm::chooseShizukuRoute,
                    chooseIndependentAccess = vm::openGrantConfirmation,
                    chooseManualAdb = vm::openManualAdb,
                    continueWithoutSetup = vm::continueWithoutSetup,
                    openShizukuWebsite = { context.openShizukuWebsite() },
                    openShizukuApp = { context.openShizukuApp() },
                    authoriseShizuku = vm::requestShizukuAuthorisation,
                    useShizuku = vm::useShizukuLive,
                    confirmGrants = vm::confirmGrants,
                    copyCommands = { context.copyToClipboard(it) },
                    verifySetup = vm::verifySetup,
                    retry = vm::refreshSetup,
                    back = vm::backToChoice,
                    openCapabilityCentre = vm::openCapabilityCentre,
                ),
            )

            Screen.CapabilityCentre -> CapabilityCentreScreen(
                report = report,
                mode = mode,
                sessionStatus = sessionStatus,
                onRefresh = vm::refreshCapabilities,
                onManageAccess = vm::openManageAccess,
            )

            Screen.ManageAccess -> ManageAccessScreen(
                mode = mode,
                report = report,
                revokeAvailable = report.shizuku.isUsable,
                revoking = revoking,
                lastRevokeResult = lastRevoke,
                onChangeAccessMethod = vm::openSetup,
                onRevoke = vm::revokeIndependentAccess,
                onCopyRevokeCommands = { context.copyToClipboard(it) },
                onBack = vm::openCapabilityCentre,
            )
        }
    }
}

// -------------------------------------------------------------------- platform helpers

/**
 * Opens Shizuku's official website in the browser.
 *
 * An `ACTION_VIEW` to an official address only. BattInsight does not download, bundle or
 * install Shizuku, and this needs no `INTERNET` permission of its own: the browser does the
 * work, in its own process.
 */
private fun Context.openShizukuWebsite() {
    startExternal(Intent(Intent.ACTION_VIEW, SHIZUKU_OFFICIAL_SITE.toUri()))
}

/** Opens the installed Shizuku app so it can run its own start flow. */
private fun Context.openShizukuApp() {
    val launch = packageManager.getLaunchIntentForPackage(ShizukuGateway.PACKAGE)
    if (launch != null) {
        startExternal(launch)
    } else {
        Toast.makeText(this, "Shizuku could not be opened", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.startExternal(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (t: android.content.ActivityNotFoundException) {
        Toast.makeText(this, "No app available to open that", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("BattInsight setup commands", text))
    Toast.makeText(this, "Commands copied", Toast.LENGTH_SHORT).show()
}

/** Shizuku's official project site. The only external destination this application opens. */
private const val SHIZUKU_OFFICIAL_SITE = "https://shizuku.rikka.app/"

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCollector
import com.rmpsdroid.battinsight.batterystats.CaptureClock
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.batterystats.CounterDeltaEngine
import com.rmpsdroid.battinsight.batterystats.CounterDeltaResult
import com.rmpsdroid.battinsight.batterystats.DecodeResult
import com.rmpsdroid.battinsight.history.SessionHistoryRepository
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import com.rmpsdroid.battinsight.persistence.RoomSessionHistoryRepository
import com.rmpsdroid.battinsight.persistence.CounterPersistResult
import com.rmpsdroid.battinsight.persistence.RoomBatterySampleStore
import com.rmpsdroid.battinsight.persistence.RoomCounterStore
import com.rmpsdroid.battinsight.persistence.RoomSessionStateStore
import com.rmpsdroid.battinsight.series.BatterySampleStore
import com.rmpsdroid.battinsight.series.BatterySampler
import com.rmpsdroid.battinsight.series.BatterySeries
import com.rmpsdroid.battinsight.persistence.StorageCounts
import com.rmpsdroid.battinsight.platform.GrantedAppProcessRunner
import com.rmpsdroid.battinsight.setup.AccessSetupCoordinator
import com.rmpsdroid.battinsight.setup.GrantStep
import com.rmpsdroid.battinsight.session.SessionCoordinator
import com.rmpsdroid.battinsight.session.SessionStatus
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.setup.SetupState
import com.rmpsdroid.battinsight.shizuku.ShizukuGateway
import com.rmpsdroid.battinsight.shizuku.ShizukuUserServiceRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which part of the application is on screen. */
sealed interface Screen {
    data object Setup : Screen
    data object CapabilityCentre : Screen
    data object ManageAccess : Screen
    data object History : Screen

    /** One battery period, opened from history. */
    data class SessionDetail(val sessionId: String) : Screen
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

    /**
     * Turns a privileged capture into a decoded model.
     *
     * Holds no runner of its own: the active backend comes from the capability report at the
     * moment of capture, so access selection stays exactly where Phase 4 put it.
     */
    private val collector = BatteryStatsCollector(
        clock = object : CaptureClock {
            override fun elapsedRealtimeMillis() = android.os.SystemClock.elapsedRealtime()
            override fun wallClockMillis() = System.currentTimeMillis()
        },
    )

    private val _collectorState = MutableStateFlow<CollectorUiState>(CollectorUiState.Idle)
    val collectorState: StateFlow<CollectorUiState> = _collectorState.asStateFlow()

    /**
     * Durable counters for the current session.
     *
     * Shares the one database rather than opening a second: there is a single Room owner, and
     * a second would be a second answer to "what does this device believe".
     */
    private val counterStore = RoomCounterStore(BattInsightDatabase.get(appContext).counterDao())

    /**
     * The sampled battery series.
     *
     * Cheap enough to sample often: a reading costs ~343 bytes against ~25 KB for a
     * privileged counter capture, which is why the two have different cadences and different
     * retention rules rather than sharing either.
     */
    private val sampleStore: BatterySampleStore =
        RoomBatterySampleStore(BattInsightDatabase.get(appContext).batterySampleDao())

    private val sampler = BatterySampler(sampleStore)

    /** Diagnostic only: how many samples the active session currently retains. */
    private val _retainedSamples = MutableStateFlow(0)
    val retainedSamples: StateFlow<Int> = _retainedSamples.asStateFlow()

    private val _counterState = MutableStateFlow<CounterUiState>(CounterUiState.None)
    val counterState: StateFlow<CounterUiState> = _counterState.asStateFlow()

    /**
     * Read-only history, over the same database.
     *
     * A repository rather than a DAO: nothing above this line can insert, update or delete,
     * which is a stronger guarantee than remembering not to.
     */
    private val history: SessionHistoryRepository = RoomSessionHistoryRepository(
        BattInsightDatabase.get(appContext).sessionDao(),
        BattInsightDatabase.get(appContext).counterDao(),
    )

    private val _historyState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val historyState: StateFlow<HistoryUiState> = _historyState.asStateFlow()

    private val _detailState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val detailState: StateFlow<DetailUiState> = _detailState.asStateFlow()

    /**
     * Opens history and loads the first page.
     *
     * Reading saved periods needs no privileged access at all -- it is BattInsight's own
     * database. History therefore works when Shizuku is not running and when no permission has
     * ever been granted; only a live capture needs a backend.
     */
    fun openHistory() {
        _screen.value = Screen.History
        _historyState.value = HistoryUiState.Loading
        viewModelScope.launch { loadHistoryPage(before = null, append = false) }
    }

    fun loadMoreHistory() {
        val current = _historyState.value as? HistoryUiState.Loaded ?: return
        val oldest = current.rows.lastOrNull()?.startWallClockMillis ?: return
        viewModelScope.launch { loadHistoryPage(before = oldest, append = true) }
    }

    private suspend fun loadHistoryPage(before: Long?, append: Boolean) {
        val existing = (_historyState.value as? HistoryUiState.Loaded)?.rows.orEmpty()
        val result = runCatching { history.recentSessions(before = before) }
        val total = runCatching { history.sessionCount() }.getOrDefault(0)

        result.onFailure { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            // An unreadable store is not an empty one, and the screen says which it is.
            _historyState.value = HistoryUiState.Empty(failure.javaClass.simpleName)
        }
        val rows = result.getOrNull() ?: return

        val combined = if (append) existing + rows else rows
        _historyState.value = if (combined.isEmpty()) {
            HistoryUiState.Empty(null)
        } else {
            HistoryUiState.Loaded(
                rows = combined,
                totalCount = total,
                canLoadMore = combined.size < total,
                formatWallClock = ::formatWallClock,
            )
        }
    }

    fun openSessionDetail(sessionId: String) {
        _screen.value = Screen.SessionDetail(sessionId)
        _detailState.value = DetailUiState.Loading
        viewModelScope.launch {
            val detail = runCatching { history.sessionDetail(sessionId) }.getOrNull()
            _detailState.value = if (detail == null) {
                DetailUiState.Missing
            } else {
                DetailUiState.Loaded(detail, ::formatWallClock, ::resolvePackage)
            }
        }
    }

    /**
     * A wall clock rendered in the user's own locale and zone.
     *
     * Presentation only. No duration is ever computed from these values -- the monotonic clock
     * does that -- so a time-zone change alters how a period is labelled and never how long it
     * is recorded as lasting.
     */
    private fun formatWallClock(millis: Long): String =
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
        ).format(java.util.Date(millis))

    /**
     * What runs under a UID *now*.
     *
     * Enrichment, not historical attribution. Phase 7B deliberately does not persist package
     * mappings, so this cannot claim the name applied when the reading was taken -- which is
     * why the UI always shows the number alongside it.
     */
    private fun resolvePackage(uid: Int): String? =
        runCatching { appContext.packageManager.getNameForUid(uid) }.getOrNull()

    /**
     * Captures batterystats once, through whichever backend access setup selected.
     *
     * The raw payload is still never persisted. What is stored is the decoded, verified
     * subset -- kernel and partial wakelock totals plus the metadata needed to refuse an
     * unsafe comparison -- and the bytes are released with the local.
     *
     * Capture happens only here, on an explicit press. There is no periodic job, no boot
     * receiver and no background trigger; a privileged command runs when a person asks for it.
     */
    fun captureBatteryStats() {
        if (_collectorState.value is CollectorUiState.Capturing) return
        _collectorState.value = CollectorUiState.Capturing
        viewModelScope.launch {
            val active = capability.report.value.selection.active
            val runner = when (active) {
                BackendKind.SHIZUKU_ADB -> shizukuRunner
                BackendKind.GRANTED_APP -> grantedAppRunner
                else -> null
            }
            if (runner == null) {
                _collectorState.value = CollectorUiState.Failed(
                    com.rmpsdroid.battinsight.batterystats.DecodeOutcome.PERMISSION_DENIAL_PAYLOAD,
                    "no usable backend is selected",
                )
                return@launch
            }
            val kind = if (active == BackendKind.SHIZUKU_ADB) {
                BackendIdentity.Kind.SHELL
            } else {
                BackendIdentity.Kind.APP_UID
            }
            val result = collector.collect(runner, kind, android.os.Build.VERSION.RELEASE)
            _collectorState.value = CollectorUiState.from(result)
            if (result is DecodeResult.Success) {
                persist(result)
            }
        }
    }

    /**
     * Stores a decoded capture against the current battery session, then recomputes deltas.
     *
     * Silent when there is no session yet: counters belong to an interval, and one that has
     * not started cannot own a baseline. The store decides baseline-or-latest; this only
     * decides that the attempt is worth making.
     */
    private suspend fun persist(result: DecodeResult.Success) {
        val status = sessions.status.value
        val session = status.session ?: return
        val stored = counterStore.store(
            capture = result.capture,
            batterySessionId = session.id.toString(),
            batterySnapshotId = session.latest.id.toString(),
            counterGeneration = status.counterGeneration,
            bootIdentity = status.bootIdentity,
        )
        _counterState.value = when (stored) {
            is CounterPersistResult.Stored -> deltasFor(session.id.toString(), stored)
            is CounterPersistResult.Rejected ->
                CounterUiState.NotStored(stored.reason.name, stored.detail)
            is CounterPersistResult.Failed ->
                CounterUiState.NotStored(stored.outcome.name, stored.detail)
        }
    }

    private suspend fun deltasFor(
        sessionId: String,
        stored: CounterPersistResult.Stored,
    ): CounterUiState {
        val state = counterStore.state(sessionId)
            ?: return CounterUiState.NotStored("NO_STATE", "nothing was read back")
        val kernel = CounterDeltaEngine.kernelWakelockDeltas(state)
        val partial = CounterDeltaEngine.partialWakelockDeltas(state)
        return CounterUiState.Available(
            role = stored.role.name,
            baselineIsLatest = state.baselineIsLatest,
            latestWallClockMillis = state.latest.captureWallClockMillis,
            elapsedMillis = state.latest.captureElapsedRealtimeMillis -
                state.baseline.captureElapsedRealtimeMillis,
            notComparableReason = (kernel as? CounterDeltaResult.NotComparable)?.detail,
            kernelDeltas = (kernel as? CounterDeltaResult.Success)?.value.orEmpty(),
            partialDeltas = (partial as? CounterDeltaResult.Success)?.value.orEmpty(),
            storedCaptures = counterStore.captureCountFor(sessionId),
        )
    }

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

    /**
     * Durable session storage.
     *
     * Owned here, one instance for the application. The UI never touches a DAO: it observes
     * the coordinator, which owns the store, which owns the database.
     */
    private val sessionStore = RoomSessionStateStore(BattInsightDatabase.get(appContext).sessionDao())

    private val sessions = SessionCoordinator(store = sessionStore, scope = viewModelScope)

    private val _storageCounts = MutableStateFlow<StorageCounts?>(null)
    val storageCounts: StateFlow<StorageCounts?> = _storageCounts.asStateFlow()

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
            _storageCounts.value = sessionStore.counts()
            batterySource.observations().collect { observation ->
                sessions.observe(observation)
                // After the engine, never before: an observation that crossed a power
                // transition belongs to the session it opened, not the one it closed.
                recordSample { sampler.onObservation(activeSessionId(), observation, generation()) }
                _storageCounts.value = sessionStore.counts()
            }
        }
    }

    // ------------------------------------------------------------- lifecycle-visible series

    /**
     * Takes one reading now and records it, if a session owns it.
     *
     * Called when the UI becomes visible again. The reading is reconciled through the session
     * engine first, so a boundary that happened while nothing was watching is accounted for
     * before the sample is attributed.
     */
    suspend fun sampleOnBecomingVisible() {
        val observation = batterySource.readCurrent(SessionTrigger.APP_START) ?: return
        sessions.observe(observation)
        recordSample { sampler.onObservation(activeSessionId(), observation, generation()) }
    }

    /** One cadence tick. Coalesced away if a sample already covers this window. */
    suspend fun sampleOnCadence() {
        val observation = batterySource.readCurrent(SessionTrigger.PERIODIC) ?: return
        recordSample { sampler.onCadenceTick(activeSessionId(), observation, generation()) }
    }

    /**
     * The session a sample would belong to, or null when the engine has none yet.
     *
     * Null is a real answer, not a failure: before the first observation is reconciled there
     * is no interval for a sample to be part of, and inventing an id would create exactly the
     * orphan row the foreign key exists to refuse.
     */
    private fun activeSessionId(): String? = sessions.status.value.session?.id?.toString()

    private fun generation(): CounterGeneration =
        sessions.status.value.session?.counterGeneration ?: CounterGeneration.INITIAL

    /**
     * Takes a sample and republishes the retained count.
     *
     * The count is re-read from the store rather than incremented from the result, so a
     * refused or failed write cannot be published as a successful sample -- the number simply
     * does not move. `SampleResult` is deliberately not surfaced to the UI yet; that is a
     * Phase 9C follow-up, and until then this is what keeps a failure from looking like a
     * success.
     */
    private suspend fun recordSample(block: suspend () -> Unit) {
        block()
        activeSessionId()?.let { _retainedSamples.value = sampleStore.countFor(it) }
    }

    /** The session's series, already divided into segments and gaps. */
    suspend fun seriesFor(sessionId: String): BatterySeries = sampleStore.seriesFor(sessionId)

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
        setContent { BattInsightTheme { BattInsightApp() } }

        // The sampled battery series, and the only timer in this application.
        //
        // `repeatOnLifecycle(STARTED)` rather than a coroutine this class remembers to cancel:
        // it cancels the block on STOPPED and runs it afresh on STARTED, so "the cadence
        // cannot outlive a visible UI" is a property of the primitive instead of a rule
        // someone has to keep obeying. `lifecycle-runtime-ktx` is already a dependency; no new
        // artifact was added for this.
        //
        // Deliberately not ProcessLifecycleOwner, not a service, not WorkManager, not an
        // alarm, and not a manifest receiver. Nothing here can wake a dead process, and the
        // gaps that leaves are rendered as gaps rather than smoothed over.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val vm = ViewModelProvider(
                    this@MainActivity,
                    BattInsightViewModel.Factory(this@MainActivity),
                )[BattInsightViewModel::class.java]

                // Immediately on becoming visible, then on the cadence from that moment --
                // not aligned to a grid, because a grid would imply a regularity that a
                // cancelled-and-restarted timer does not have.
                vm.sampleOnBecomingVisible()
                while (true) {
                    delay(BatterySampleStore.BATTERY_SAMPLE_CADENCE_MILLIS)
                    vm.sampleOnCadence()
                }
            }
        }
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
        val storageCounts by vm.storageCounts.collectAsStateWithLifecycle()
        val collectorState by vm.collectorState.collectAsStateWithLifecycle()
        val counterState by vm.counterState.collectAsStateWithLifecycle()
        val historyState by vm.historyState.collectAsStateWithLifecycle()
        val detailState by vm.detailState.collectAsStateWithLifecycle()

        // Leaving a secondary screen goes back to the main one rather than out of the app.
        // Detail goes back to History rather than all the way out, so the stack reads the way
        // a person navigated it. Nothing intercepts the gesture itself, so predictive back
        // keeps working.
        BackHandler(enabled = screen != Screen.CapabilityCentre && mode.isChosen) {
            when (screen) {
                is Screen.SessionDetail -> vm.openHistory()
                else -> vm.openCapabilityCentre()
            }
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
                storageCounts = storageCounts,
                collectorState = collectorState,
                counterState = counterState,
                onCapture = vm::captureBatteryStats,
                onRefresh = vm::refreshCapabilities,
                onManageAccess = vm::openManageAccess,
                onOpenHistory = vm::openHistory,
            )

            Screen.History -> SessionHistoryScreen(
                state = historyState,
                onOpenSession = vm::openSessionDetail,
                onLoadMore = vm::loadMoreHistory,
                onBack = vm::openCapabilityCentre,
            )

            is Screen.SessionDetail -> SessionDetailScreen(
                state = detailState,
                onBack = vm::openHistory,
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

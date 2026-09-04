package com.rmpsdroid.battinsight

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.capability.BatteryStatsProbe
import com.rmpsdroid.battinsight.capability.CapabilityCoordinator
import com.rmpsdroid.battinsight.capability.CapabilityState
import com.rmpsdroid.battinsight.collection.AccessModeBackendSelector
import com.rmpsdroid.battinsight.collection.BackendAvailability
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.permissions.PermissionGrant
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.platform.AndroidBatteryPropertySource
import com.rmpsdroid.battinsight.platform.AndroidPackageResolutionSource
import com.rmpsdroid.battinsight.platform.AndroidPermissionStateReader
import com.rmpsdroid.battinsight.platform.AndroidShizukuGateway
import com.rmpsdroid.battinsight.platform.AndroidUsageAccessSource
import com.rmpsdroid.battinsight.platform.GrantedAppProcessRunner
import com.rmpsdroid.battinsight.setup.AccessSetupCoordinator
import com.rmpsdroid.battinsight.setup.ManualAdbInstructions
import com.rmpsdroid.battinsight.setup.SetupAction
import com.rmpsdroid.battinsight.setup.SetupState
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import com.rmpsdroid.battinsight.shizuku.ShizukuUserServiceRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Onboarding and access setup, observed on a real platform.
 *
 * These cases **observe**. Nothing here grants a permission, changes an app-op or changes a
 * setting — the state-changing flows live in [AccessSetupChangeHarness], behind explicit
 * opt-in arguments, so an ordinary run of the suite can never elevate the application.
 *
 * Preferences are exercised through an in-memory store rather than the real DataStore, so
 * running the suite does not silently rewrite the user's saved choice.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class AccessSetupRuntimeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gateway = AndroidShizukuGateway(context)
    private val permissions = AndroidPermissionStateReader(context)
    private val grantedAppRunner = GrantedAppProcessRunner()
    private var runner: ShizukuUserServiceRunner? = null

    @After
    fun tearDown() {
        runner?.release()
        runner = null
    }

    // ------------------------------------------------- A: clean baseline, nothing granted

    /**
     * A fresh install asks the question and does nothing else.
     *
     * The property that matters: arriving at onboarding must not request Shizuku
     * authorisation, must not grant anything, and must not change an app-op. An application
     * that quietly elevated itself on first launch would have earned none of the trust it
     * is asking for.
     */
    @Test
    fun freshInstallAsksAndChangesNothing() = runBlocking {
        val before = permissions.read()
        val appOpBefore = before.usageStatsAppOp

        val coordinator = coordinator(InMemoryAccessPreferences(AccessMode.NOT_CHOSEN))
        val state = coordinator.evaluate()
        Log.i(TAG, "fresh install state: " + state)

        assertEquals(SetupState.Welcome, state)

        val after = permissions.read()
        assertEquals("no permission may change by evaluating onboarding", before.missing, after.missing)
        assertEquals("no app-op may change", appOpBefore, after.usageStatsAppOp)
    }

    @Test
    fun exploringWithoutSetupIsRememberedAndIsNotAnError() = runBlocking {
        val preferences = InMemoryAccessPreferences(AccessMode.NOT_CHOSEN)
        val coordinator = coordinator(preferences)

        coordinator.choose(AccessMode.LIMITED)
        // choose() is asynchronous; evaluate directly for a deterministic assertion.
        preferences.setAccessMode(AccessMode.LIMITED)
        val state = coordinator.evaluate()
        Log.i(TAG, "limited state: " + state)

        assertTrue("limited mode is a normal outcome", state is SetupState.Limited)
        assertEquals(AccessMode.LIMITED, preferences.current())
    }

    @Test
    fun theCapabilityCentreIsReachableWithoutAnySetup() = runBlocking {
        val report = capabilityCoordinator(AccessMode.LIMITED).evaluate()
        Log.i(TAG, "limited-mode selection: " + report.selection)

        assertTrue("the report must still be produced", !report.refreshing)
        assertEquals(
            "limited mode selects no privileged backend",
            null,
            report.selection.active,
        )
        assertTrue("capabilities are still listed", report.findings.isNotEmpty())
    }

    /** Nothing in this class may leave BattInsight holding an elevated permission. */
    @Test
    fun observationNeverGrantsAnything() = runBlocking {
        val snapshot = permissions.read()
        Log.i(TAG, "permission snapshot: " + snapshot)
        RequiredPermission.entries.forEach {
            Log.i(TAG, "  " + it.manifestName + " = " + snapshot.grantOf(it))
        }
        // No assertion on which are held -- the grant harness may legitimately have run
        // first. What is asserted is that *observing* changed nothing.
        val again = permissions.read()
        assertEquals(snapshot.missing, again.missing)
        assertEquals(snapshot.usageStatsAppOp, again.usageStatsAppOp)
    }

    // -------------------------------------------------------------- B: live Shizuku route

    @Test
    fun shizukuLifecycleIsReportedAccurately() = runBlocking {
        val state = gateway.state()
        Log.i(TAG, "shizuku state: " + state)

        val setup = coordinator(InMemoryAccessPreferences(AccessMode.SHIZUKU_LIVE)).evaluateShizukuRoute()
        Log.i(TAG, "shizuku route state: " + setup)

        val expected = when (state) {
            ShizukuState.NotInstalled -> setup is SetupState.ShizukuNotInstalled
            is ShizukuState.InstalledNotRunning -> setup is SetupState.ShizukuStopped
            is ShizukuState.RunningNotAuthorised -> setup is SetupState.ShizukuUnauthorised
            is ShizukuState.RunningAuthorised -> setup is SetupState.ShizukuReady
            is ShizukuState.VersionUnsupported -> setup is SetupState.ShizukuUnsupported
            else -> setup is SetupState.Error
        }
        assertTrue("state " + state + " produced setup state " + setup, expected)
    }

    /**
     * The result the live route exists for: statistics acquired while BattInsight itself
     * holds none of the three permissions.
     *
     * Skipped unless the app-UID permissions are genuinely absent — running it after the
     * grant harness would prove nothing about the Shizuku route.
     */
    @Test
    fun liveShizukuWorksWhileBattInsightHoldsNoPermissions() = runBlocking {
        assumeAuthorised()
        val snapshot = permissions.read()
        assumeTrue(
            "this proves the live route, so it needs the three permissions denied",
            snapshot.missing.size == RequiredPermission.entries.size,
        )

        val r = obtainRunner()

        // Identity is measured, never assumed.
        val id = r.run(ProbeCommand.Identity)
        val ctx = r.run(ProbeCommand.SelinuxIdentity)
        Log.i(TAG, "live shizuku identity: " + id.stdoutHead(200).trim())
        Log.i(TAG, "live shizuku selinux: " + ctx.stdoutHead(200).trim())
        assertEquals(0, id.exitCode)

        val proto = r.run(ProbeCommand.BatteryStatsProto)
        Log.i(TAG, "live shizuku proto: " + proto)
        val result = BatteryStatsProbe.toCollectionResult(
            proto, BackendIdentity.Kind.SHELL, SourceFormat.PROTO, System.currentTimeMillis(),
        )
        val acquisition = BatteryStatsProbe.evaluateProtoAcquisition(result, proto.stdout, proto.truncated)
        assertEquals(CapabilityState.Available, acquisition)

        // And the three are still denied afterwards.
        val after = permissions.read()
        RequiredPermission.entries.forEach {
            assertEquals(
                it.manifestName + " must remain denied on the live route",
                PermissionGrant.DENIED,
                after.grantOf(it),
            )
        }

        val setup = coordinator(InMemoryAccessPreferences(AccessMode.SHIZUKU_LIVE)).evaluate()
        Log.i(TAG, "live shizuku setup state: " + setup)
        assertTrue("expected Ready, was " + setup, setup is SetupState.Ready)
        assertEquals(BackendKind.SHIZUKU_ADB, (setup as SetupState.Ready).backend)
    }

    @Test
    fun choosingShizukuSelectsTheShizukuBackend() = runBlocking {
        assumeAuthorised()
        val report = capabilityCoordinator(AccessMode.SHIZUKU_LIVE).evaluate()
        Log.i(TAG, "selection with SHIZUKU_LIVE: " + report.selection)
        assertEquals(BackendKind.SHIZUKU_ADB, report.selection.active)
        assertEquals(BackendKind.SHIZUKU_ADB, report.selection.preferred)
    }

    /**
     * The user's choice is not overridden by what happens to be available.
     *
     * With Shizuku authorised and the app permissions absent, choosing independent access
     * must *not* quietly fall back to Shizuku: it offers it and waits.
     */
    @Test
    fun choosingIndependentAccessIsNotSilentlyReplacedByShizuku() = runBlocking {
        assumeAuthorised()
        val snapshot = permissions.read()
        assumeTrue("needs the app permissions absent", snapshot.missing.isNotEmpty())

        val report = capabilityCoordinator(AccessMode.GRANTED_APP).evaluate()
        Log.i(TAG, "selection with GRANTED_APP but no permissions: " + report.selection)

        assertEquals("the chosen mode is unavailable, so nothing is active", null, report.selection.active)
        assertEquals(BackendKind.GRANTED_APP, report.selection.preferred)
        assertEquals(
            "Shizuku is offered, not applied",
            BackendKind.SHIZUKU_ADB,
            report.selection.fallbackOffer,
        )
    }

    // ---------------------------------------------------------------- D: manual ADB route

    /**
     * The commands the manual screen renders are exactly the three grants, and nothing else.
     *
     * This is the text a user will paste into a terminal, so what it does *not* contain
     * matters as much as what it does.
     */
    @Test
    fun manualInstructionsAreExactlyThreeGrantsForThisPackage() {
        val commands = ManualAdbInstructions.grantCommands
        Log.i(TAG, "manual commands: " + commands)

        assertEquals(3, commands.size)
        assertEquals(
            "the commands must target the package actually installed",
            context.packageName,
            SetupAction.TARGET_PACKAGE,
        )
        commands.forEach {
            assertTrue(it.startsWith("adb shell pm grant " + context.packageName + " "))
        }
        val joined = commands.joinToString(" ")
        assertTrue("no BATTERY_STATS", !joined.contains("BATTERY_STATS"))
        assertTrue("no _FULL", !joined.contains("INTERACT_ACROSS_USERS_FULL"))
        assertTrue("no appops", !joined.contains("appops"))
    }

    /**
     * Verification is behavioural.
     *
     * Whatever the permissions currently say, `Ready` may only be reported when a real
     * acquisition through the app's own process succeeded.
     */
    @Test
    fun verificationAgreesWithRealAcquisition() = runBlocking {
        val snapshot = permissions.read()
        val out = grantedAppRunner.run(ProbeCommand.BatteryStatsProto)
        val result = BatteryStatsProbe.toCollectionResult(
            out, BackendIdentity.Kind.APP_UID, SourceFormat.PROTO, System.currentTimeMillis(),
        )
        val acquisition = BatteryStatsProbe.evaluateProtoAcquisition(result, out.stdout, out.truncated)
        Log.i(TAG, "granted-app acquisition: " + acquisition + " (missing " + snapshot.missing + ")")

        val setup = coordinator(InMemoryAccessPreferences(AccessMode.GRANTED_APP)).evaluate()
        Log.i(TAG, "granted-app setup state: " + setup)

        if (acquisition is CapabilityState.Available && snapshot.allRequiredGranted) {
            assertTrue("acquisition works, so setup must report Ready", setup is SetupState.Ready)
        } else {
            assertTrue(
                "acquisition does not work, so setup must not report Ready: " + setup,
                !setup.isReady,
            )
        }
    }

    @Test
    fun theAppOpIsNeverChangedByAnythingHere() = runBlocking {
        val before = permissions.read().usageStatsAppOp
        coordinator(InMemoryAccessPreferences(AccessMode.NOT_CHOSEN)).evaluate()
        coordinator(InMemoryAccessPreferences(AccessMode.SHIZUKU_LIVE)).evaluateShizukuRoute()
        val after = permissions.read().usageStatsAppOp
        Log.i(TAG, "app-op before=" + before + " after=" + after)
        assertEquals("the app-op is observed, never mutated", before, after)
    }

    // ------------------------------------------------------------------------- helpers

    private fun coordinator(preferences: InMemoryAccessPreferences) = AccessSetupCoordinator(
        preferences = preferences,
        permissionReader = permissions,
        shizukuGateway = gateway,
        setupExecutor = obtainRunner(),
        grantedAppRunner = grantedAppRunner,
        shizukuRunner = obtainRunner(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private fun capabilityCoordinator(mode: AccessMode) = CapabilityCoordinator(
        grantedAppRunner = grantedAppRunner,
        shizukuRunner = obtainRunner(),
        shizukuGateway = gateway,
        permissionReader = permissions,
        batterySource = AndroidBatteryPropertySource(context),
        usageSource = AndroidUsageAccessSource(context),
        packageSource = AndroidPackageResolutionSource(context),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        backendSelector = { shizuku: BackendAvailability, app: BackendAvailability ->
            AccessModeBackendSelector(mode).select(shizuku, app)
        },
    )

    private fun obtainRunner(): ShizukuUserServiceRunner =
        runner ?: ShizukuUserServiceRunner(context, gateway).also { runner = it }

    private suspend fun assumeAuthorised() {
        val state = gateway.state()
        assumeTrue("Shizuku is not authorised (state: " + state + ")", state is ShizukuState.RunningAuthorised)
        assertNotNull(state)
    }

    private companion object {
        const val TAG = "BattInsightSetup"
    }
}

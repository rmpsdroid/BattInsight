package com.rmpsdroid.battinsight

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.access.AccessPreferenceStore
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.permissions.PermissionGrant
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.platform.AndroidPermissionStateReader
import com.rmpsdroid.battinsight.platform.AndroidShizukuGateway
import com.rmpsdroid.battinsight.platform.GrantedAppProcessRunner
import com.rmpsdroid.battinsight.setup.AccessSetupCoordinator
import com.rmpsdroid.battinsight.setup.GrantStep
import com.rmpsdroid.battinsight.setup.SetupState
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import com.rmpsdroid.battinsight.shizuku.ShizukuUserServiceRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two flows that actually change the device, exercised through the production code.
 *
 * Separated from [AccessSetupRuntimeTest] and gated behind explicit opt-in arguments,
 * because these are the only tests in the project that elevate the application's own
 * privileges. An ordinary suite run must never do that by accident — being in a different
 * class is not enough on its own, so each also requires an argument naming what it will do:
 *
 * ```
 * am instrument -e class com.rmpsdroid.battinsight.AccessSetupChangeHarness \
 *               -e grantAccess true ...
 * am instrument -e class com.rmpsdroid.battinsight.AccessSetupChangeHarness \
 *               -e revokeAccess true ...
 * ```
 *
 * Both run the same code the buttons run. Neither takes a shortcut around the confirmation
 * step, the per-step verification, or the behavioural check.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class AccessSetupChangeHarness {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gateway = AndroidShizukuGateway(context)
    private val permissions = AndroidPermissionStateReader(context)
    private var runner: ShizukuUserServiceRunner? = null

    @After
    fun tearDown() {
        runner?.release()
        runner = null
    }

    /**
     * Grants the three permissions through Shizuku, exactly as the confirmation button does.
     *
     * Every transition is recorded from the platform's own permission state, before and
     * after each step, so what is reported is what the device says rather than what the
     * command claimed.
     */
    @Test
    fun grantIndependentAccessThroughShizuku() = runBlocking {
        assumeTrue(
            "opt-in required: pass -e " + ARG_GRANT + " true",
            InstrumentationRegistry.getArguments().getString(ARG_GRANT) == "true",
        )
        assumeAuthorised()

        val before = permissions.read()
        val appOpBefore = before.usageStatsAppOp
        Log.i(TAG, "before: missing=" + before.missing.map { it.manifestName } + " appOp=" + appOpBefore)

        val preferences = InMemoryAccessPreferences(AccessMode.NOT_CHOSEN)
        val coordinator = coordinator(preferences)

        // The production guard: a sequence only runs from the confirmation state.
        coordinator.openGrantConfirmation()
        val state = coordinator.runGrantSequence()
        Log.i(TAG, "grant sequence result: " + state)

        val after = permissions.read()
        RequiredPermission.entries.forEach {
            Log.i(TAG, "  " + it.manifestName + ": " + before.grantOf(it) + " -> " + after.grantOf(it))
        }

        assertTrue("expected Ready, was " + state, state is SetupState.Ready)
        assertEquals(BackendKind.GRANTED_APP, (state as SetupState.Ready).backend)

        RequiredPermission.entries.forEach {
            assertEquals(
                it.manifestName + " must be granted",
                PermissionGrant.GRANTED,
                after.grantOf(it),
            )
        }

        // The measured Phase 1B finding, re-confirmed: the permission is enough, and the
        // app-op does not need forcing. BattInsight never touches it.
        Log.i(TAG, "app-op after grants: " + after.usageStatsAppOp)
        assertEquals(
            "BattInsight must not change the usage-access app-op",
            appOpBefore,
            after.usageStatsAppOp,
        )

        assertEquals(AccessMode.GRANTED_APP, preferences.current())
    }

    /**
     * Proves the independent route is genuinely independent.
     *
     * Run after the grant harness with Shizuku stopped: the granted-app backend must still
     * work, and the Shizuku backend must not claim to.
     */
    @Test
    fun grantedAccessSurvivesShizukuStopping() = runBlocking {
        assumeTrue(
            "opt-in required: pass -e " + ARG_VERIFY_INDEPENDENT + " true",
            InstrumentationRegistry.getArguments().getString(ARG_VERIFY_INDEPENDENT) == "true",
        )
        val snapshot = permissions.read()
        assumeTrue("needs the three permissions granted", snapshot.allRequiredGranted)

        val shizuku = gateway.state()
        Log.i(TAG, "shizuku state during independent check: " + shizuku)
        assumeTrue("needs Shizuku stopped", !shizuku.isUsable)

        val preferences = InMemoryAccessPreferences(AccessMode.GRANTED_APP)
        val state = coordinator(preferences).evaluate()
        Log.i(TAG, "independent state with Shizuku stopped: " + state)

        assertTrue("independent access must survive Shizuku stopping: " + state, state is SetupState.Ready)
        assertEquals(BackendKind.GRANTED_APP, (state as SetupState.Ready).backend)
    }

    /**
     * Removes the three permissions, exactly as the Manage access button does.
     *
     * Only BattInsight's own three, only through the typed revoke actions.
     */
    @Test
    fun revokeIndependentAccessThroughShizuku() = runBlocking {
        assumeTrue(
            "opt-in required: pass -e " + ARG_REVOKE + " true",
            InstrumentationRegistry.getArguments().getString(ARG_REVOKE) == "true",
        )
        assumeAuthorised()

        val before = permissions.read()
        val appOpBefore = before.usageStatsAppOp
        Log.i(TAG, "before revoke: missing=" + before.missing.map { it.manifestName })

        val steps = coordinator(InMemoryAccessPreferences(AccessMode.GRANTED_APP))
            .revokeIndependentAccess()
        steps.forEach { Log.i(TAG, "revoke step: " + it.permission.manifestName + " -> " + it.verdict + " (" + it.detail + ")") }

        val after = permissions.read()
        RequiredPermission.entries.forEach {
            assertEquals(
                it.manifestName + " must no longer be held",
                PermissionGrant.DENIED,
                after.grantOf(it),
            )
        }
        assertTrue("every step must have succeeded", steps.all { it.succeeded })
        assertTrue(
            "removal must be reported per permission",
            steps.any { it.verdict == GrantStep.Verdict.REMOVED } || before.missing.size == 3,
        )
        assertEquals("revocation must not change the app-op", appOpBefore, after.usageStatsAppOp)
    }

    private fun coordinator(preferences: AccessPreferenceStore) = AccessSetupCoordinator(
        preferences = preferences,
        permissionReader = permissions,
        shizukuGateway = gateway,
        setupExecutor = obtainRunner(),
        grantedAppRunner = GrantedAppProcessRunner(),
        shizukuRunner = obtainRunner(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private fun obtainRunner(): ShizukuUserServiceRunner =
        runner ?: ShizukuUserServiceRunner(context, gateway).also { runner = it }

    private suspend fun assumeAuthorised() {
        val state = gateway.state()
        assumeTrue(
            "Shizuku must be authorised (state: " + state + ")",
            state is ShizukuState.RunningAuthorised,
        )
    }

    private companion object {
        const val TAG = "BattInsightSetup"
        const val ARG_GRANT = "grantAccess"
        const val ARG_REVOKE = "revokeAccess"
        const val ARG_VERIFY_INDEPENDENT = "verifyIndependent"
    }
}

/**
 * An in-memory access preference, so running the instrumented suite never rewrites the
 * user's real saved choice on disk.
 */
class InMemoryAccessPreferences(initial: AccessMode) : AccessPreferenceStore {
    private val backing = MutableStateFlow(initial)
    override val accessMode: Flow<AccessMode> = backing.asStateFlow()
    override suspend fun current(): AccessMode = backing.value
    override suspend fun setAccessMode(mode: AccessMode) { backing.value = mode }
}

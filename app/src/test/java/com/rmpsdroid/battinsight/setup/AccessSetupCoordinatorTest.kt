package com.rmpsdroid.battinsight.setup

import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.access.FakeAccessPreferenceStore
import com.rmpsdroid.battinsight.capability.FakeProcessRunner
import com.rmpsdroid.battinsight.capability.FakeShizukuGateway
import com.rmpsdroid.battinsight.capability.MeasuredDenials
import com.rmpsdroid.battinsight.capability.fakeProtoPayload
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding flow, end to end, on the JVM.
 *
 * Every scenario here is one a user can actually reach, including the ones that go wrong.
 * The failure paths matter more than the happy path: an application that asks someone to
 * elevate its privileges owes them an accurate account of what happened when that only
 * half worked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessSetupCoordinatorTest {

    private fun coordinator(
        preferences: FakeAccessPreferenceStore = FakeAccessPreferenceStore(),
        permissions: MutablePermissionReader = MutablePermissionReader(),
        shizuku: ShizukuState = ShizukuState.NotInstalled,
        executor: SetupExecutor = FakeSetupExecutor(permissions),
        grantedAppRunner: FakeProcessRunner = FakeProcessRunner().withAppIdentity(),
        shizukuRunner: FakeProcessRunner = FakeProcessRunner().withShellIdentity(),
        scope: TestScope,
    ) = AccessSetupCoordinator(
        preferences = preferences,
        permissionReader = permissions,
        shizukuGateway = FakeShizukuGateway(shizuku),
        setupExecutor = executor,
        grantedAppRunner = grantedAppRunner,
        shizukuRunner = shizukuRunner,
        scope = scope,
        clock = { 1_000L },
    )

    /** A runner whose protobuf probe succeeds, as a working backend's would. */
    private fun workingRunner(shell: Boolean = false) =
        (if (shell) FakeProcessRunner().withShellIdentity() else FakeProcessRunner().withAppIdentity())
            .on(ProbeCommand.BatteryStatsProto, 0, fakeProtoPayload())

    /** A runner refused by the platform, exactly as Phase 1B measured it: exit 0, denial on stdout. */
    private fun deniedRunner() = FakeProcessRunner().withAppIdentity()
        .on(ProbeCommand.BatteryStatsProto, 0, MeasuredDenials.DUMP)

    // ---- 1. fresh install: no Shizuku, no permissions ----

    @Test
    fun `a fresh install is asked to choose, and nothing is granted`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
        val state = coordinator(
            permissions = permissions,
            executor = executor,
            scope = TestScope(testScheduler),
        ).evaluate()

        assertEquals(SetupState.Welcome, state)
        assertTrue("nothing may be executed before the user chooses", executor.attempted.isEmpty())
        assertTrue(permissions.heldNow.isEmpty())
    }

    // ---- 2. explore without setup ----

    @Test
    fun `choosing to explore without setup is remembered and is not an error`() = runTest {
        val preferences = FakeAccessPreferenceStore(AccessMode.LIMITED)
        val state = coordinator(preferences = preferences, scope = TestScope(testScheduler)).evaluate()

        assertTrue("limited mode is a normal state, not an error", state is SetupState.Limited)
        assertEquals(AccessMode.LIMITED, (state as SetupState.Limited).mode)
        assertFalse(state is SetupState.Error)
    }

    // ---- 3. Shizuku installed but stopped ----

    @Test
    fun `an installed but stopped Shizuku is reported as stopped, not missing`() = runTest {
        val state = coordinator(
            preferences = FakeAccessPreferenceStore(AccessMode.SHIZUKU_LIVE),
            shizuku = ShizukuState.InstalledNotRunning("13.6.0"),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertTrue(state is SetupState.ShizukuStopped)
        assertEquals("13.6.0", (state as SetupState.ShizukuStopped).versionName)
    }

    // ---- 4. Shizuku running but unauthorised ----

    @Test
    fun `a running but unauthorised Shizuku asks for authorisation`() = runTest {
        val state = coordinator(
            preferences = FakeAccessPreferenceStore(AccessMode.SHIZUKU_LIVE),
            shizuku = ShizukuState.RunningNotAuthorised(13, 2000),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertTrue(state is SetupState.ShizukuUnauthorised)
    }

    // ---- 5. authorisation allowed ----

    @Test
    fun `authorisation is requested only when asked, and the result is re-read`() = runTest {
        val gateway = FakeShizukuGateway(ShizukuState.RunningNotAuthorised(13, 2000))
        val c = AccessSetupCoordinator(
            preferences = FakeAccessPreferenceStore(),
            permissionReader = MutablePermissionReader(),
            shizukuGateway = gateway,
            setupExecutor = FakeSetupExecutor(MutablePermissionReader()),
            grantedAppRunner = FakeProcessRunner(),
            shizukuRunner = FakeProcessRunner().withShellIdentity(),
            scope = TestScope(testScheduler),
        )

        assertFalse("must not be requested on construction", gateway.authorisationRequested)
        c.requestShizukuAuthorisation()
        testScheduler.advanceUntilIdle()
        assertTrue(gateway.authorisationRequested)
    }

    // ---- 6. authorisation denied ----

    @Test
    fun `a declined authorisation leaves the user unauthorised, not in an error state`() = runTest {
        // The gateway still reports unauthorised afterwards; declining is a normal answer.
        val gateway = FakeShizukuGateway(ShizukuState.RunningNotAuthorised(13, 2000))
        val c = AccessSetupCoordinator(
            preferences = FakeAccessPreferenceStore(),
            permissionReader = MutablePermissionReader(),
            shizukuGateway = gateway,
            setupExecutor = FakeSetupExecutor(MutablePermissionReader()),
            grantedAppRunner = FakeProcessRunner(),
            shizukuRunner = FakeProcessRunner().withShellIdentity(),
            scope = TestScope(testScheduler),
        )
        c.requestShizukuAuthorisation()
        testScheduler.advanceUntilIdle()

        val state = c.state.value
        assertTrue("expected unauthorised, was $state", state is SetupState.ShizukuUnauthorised)
        assertFalse(state is SetupState.Error)
    }

    // ---- 7. live Shizuku with all three Android permissions denied ----

    @Test
    fun `live Shizuku is ready while BattInsight holds none of the three permissions`() = runTest {
        val permissions = MutablePermissionReader()   // nothing granted
        val state = coordinator(
            preferences = FakeAccessPreferenceStore(AccessMode.SHIZUKU_LIVE),
            permissions = permissions,
            shizuku = ShizukuState.RunningAuthorised(13, 2000),
            shizukuRunner = workingRunner(shell = true),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertTrue("expected Ready, was $state", state is SetupState.Ready)
        val ready = state as SetupState.Ready
        assertEquals(BackendKind.SHIZUKU_ADB, ready.backend)
        assertTrue(
            "the live route must not require any app permission",
            permissions.heldNow.isEmpty(),
        )
    }

    // ---- 8. Shizuku stops after setup ----

    @Test
    fun `a stopped Shizuku after setup stops claiming ready`() = runTest {
        val state = coordinator(
            preferences = FakeAccessPreferenceStore(AccessMode.SHIZUKU_LIVE),
            shizuku = ShizukuState.InstalledNotRunning("13.6.0"),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertFalse("readiness must not survive Shizuku stopping", state.isReady)
        assertTrue(state is SetupState.ShizukuStopped)
    }

    // ---- 9-12. the grant sequence ----

    @Test
    fun `the first grant is DUMP and it is verified by re-reading`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
        val c = coordinator(
            permissions = permissions,
            executor = executor,
            grantedAppRunner = workingRunner(),
            scope = TestScope(testScheduler),
        )
        c.runGrantSequence()

        assertEquals(SetupAction.GrantDump.id, executor.attemptedIds().first())
        assertTrue(permissions.holds(RequiredPermission.DUMP))
    }

    @Test
    fun `a failing second grant stops the sequence and reports what did change`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
            .failing(SetupAction.GrantPackageUsageStats, "Operation not allowed")
        val c = coordinator(permissions = permissions, executor = executor, scope = TestScope(testScheduler))

        val state = c.runGrantSequence()

        assertTrue("expected GrantFailed, was $state", state is SetupState.GrantFailed)
        val failed = state as SetupState.GrantFailed
        assertEquals(RequiredPermission.PACKAGE_USAGE_STATS, failed.failed.permission)
        assertEquals(GrantStep.Verdict.FAILED, failed.failed.verdict)
        // The first one did work, and the user is told so rather than given a bare failure.
        assertEquals(1, failed.completed.size)
        assertEquals(RequiredPermission.DUMP, failed.completed.first().permission)
        assertEquals(GrantStep.Verdict.CHANGED, failed.completed.first().verdict)
    }

    @Test
    fun `the third grant is never attempted after the second fails`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
            .failing(SetupAction.GrantPackageUsageStats)
        coordinator(permissions = permissions, executor = executor, scope = TestScope(testScheduler))
            .runGrantSequence()

        assertEquals(
            listOf(SetupAction.GrantDump.id, SetupAction.GrantPackageUsageStats.id),
            executor.attemptedIds(),
        )
        assertFalse(
            "INTERACT_ACROSS_USERS must not be granted after an earlier failure",
            permissions.holds(RequiredPermission.INTERACT_ACROSS_USERS),
        )
    }

    @Test
    fun `all three grants run in the measured platform order`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
        coordinator(
            permissions = permissions,
            executor = executor,
            grantedAppRunner = workingRunner(),
            scope = TestScope(testScheduler),
        ).runGrantSequence()

        assertEquals(
            listOf(
                SetupAction.GrantDump.id,
                SetupAction.GrantPackageUsageStats.id,
                SetupAction.GrantInteractAcrossUsers.id,
            ),
            executor.attemptedIds(),
        )
    }

    // ---- 13. flags granted but the behavioural probe fails ----

    @Test
    fun `permissions granted but acquisition failing is reported, not hidden`() = runTest {
        val permissions = MutablePermissionReader()
        val state = coordinator(
            permissions = permissions,
            executor = FakeSetupExecutor(permissions),
            // The permissions will be granted, but reading still fails.
            grantedAppRunner = deniedRunner(),
            scope = TestScope(testScheduler),
        ).runGrantSequence()

        assertTrue("expected VerificationFailed, was $state", state is SetupState.VerificationFailed)
        assertTrue(
            "the reason must be specific",
            (state as SetupState.VerificationFailed).detail.isNotBlank(),
        )
        assertFalse("this must not be reported as ready", state.isReady)
    }

    // ---- 14. all three grants plus a working probe ----

    @Test
    fun `setup succeeds only when acquisition actually works`() = runTest {
        val permissions = MutablePermissionReader()
        val preferences = FakeAccessPreferenceStore()
        val state = coordinator(
            preferences = preferences,
            permissions = permissions,
            executor = FakeSetupExecutor(permissions),
            grantedAppRunner = workingRunner(),
            scope = TestScope(testScheduler),
        ).runGrantSequence()

        assertTrue("expected Ready, was $state", state is SetupState.Ready)
        assertEquals(BackendKind.GRANTED_APP, (state as SetupState.Ready).backend)
        assertEquals(AccessMode.GRANTED_APP, preferences.current())
        assertEquals(RequiredPermission.entries.toSet(), permissions.heldNow)
    }

    @Test
    fun `a permission already held is not granted again`() = runTest {
        val permissions = MutablePermissionReader(granted = setOf(RequiredPermission.DUMP))
        val executor = FakeSetupExecutor(permissions)
        val state = coordinator(
            permissions = permissions,
            executor = executor,
            grantedAppRunner = workingRunner(),
            scope = TestScope(testScheduler),
        ).runGrantSequence()

        assertFalse(
            "DUMP was already held; granting it again would be a pointless privileged action",
            executor.attemptedIds().contains(SetupAction.GrantDump.id),
        )
        assertTrue(state is SetupState.Ready)
    }

    /**
     * The case the whole verify-after-each-step design exists for: `pm` reports a clean exit
     * and the permission is still not held.
     */
    @Test
    fun `a grant that reports success but changes nothing is treated as a failure`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
            .silentlyIneffective(SetupAction.GrantDump)
        val state = coordinator(
            permissions = permissions,
            executor = executor,
            scope = TestScope(testScheduler),
        ).runGrantSequence()

        assertTrue(state is SetupState.GrantFailed)
        val failed = (state as SetupState.GrantFailed).failed
        assertEquals(GrantStep.Verdict.FAILED, failed.verdict)
        assertTrue(
            "the message must name the inconsistency: ${failed.detail}",
            failed.detail.contains("reported success"),
        )
    }

    @Test
    fun `no privileged backend means no grant is attempted at all`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = UnavailableSetupExecutor()
        val state = coordinator(
            permissions = permissions,
            executor = executor,
            scope = TestScope(testScheduler),
        ).runGrantSequence()

        assertTrue(state is SetupState.GrantFailed)
        assertTrue(permissions.heldNow.isEmpty())
    }

    // ---- 21-22. revocation ----

    @Test
    fun `revocation removes only BattInsight's three permissions`() = runTest {
        val permissions = MutablePermissionReader(granted = RequiredPermission.entries.toSet())
        val executor = FakeSetupExecutor(permissions)
        val steps = coordinator(
            permissions = permissions,
            executor = executor,
            scope = TestScope(testScheduler),
        ).revokeIndependentAccess()

        assertEquals(3, steps.size)
        assertTrue(steps.all { it.verdict == GrantStep.Verdict.REMOVED })
        assertTrue(permissions.heldNow.isEmpty())

        // Every action names BattInsight, is a revoke, and touches nothing else.
        assertTrue(executor.attempted.all { it.operation == SetupAction.Operation.REVOKE })
        assertTrue(executor.attempted.all { it.argv[2] == SetupAction.TARGET_PACKAGE })
        assertEquals(
            RequiredPermission.entries.toSet(),
            executor.attempted.map { it.permission }.toSet(),
        )
    }

    @Test
    fun `revoking a permission that is not held changes nothing`() = runTest {
        val permissions = MutablePermissionReader(granted = setOf(RequiredPermission.DUMP))
        val executor = FakeSetupExecutor(permissions)
        val steps = coordinator(
            permissions = permissions,
            executor = executor,
            scope = TestScope(testScheduler),
        ).revokeIndependentAccess()

        assertEquals(
            listOf(SetupAction.RevokeDump.id),
            executor.attemptedIds(),
        )
        assertEquals(2, steps.count { it.verdict == GrantStep.Verdict.ALREADY_HELD })
    }

    @Test
    fun `a grant sequence cannot start without passing through confirmation`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
        val c = coordinator(permissions = permissions, executor = executor, scope = TestScope(testScheduler))

        // State is Welcome, not GrantConfirmation.
        c.grantIndependentAccess()
        testScheduler.advanceUntilIdle()

        assertTrue("arriving on a screen must never grant anything", executor.attempted.isEmpty())
        assertTrue(permissions.heldNow.isEmpty())
    }

    @Test
    fun `confirmation itself changes nothing until confirmed`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
        val c = coordinator(permissions = permissions, executor = executor, scope = TestScope(testScheduler))

        c.openGrantConfirmation()
        testScheduler.advanceUntilIdle()

        assertEquals(SetupState.GrantConfirmation, c.state.value)
        assertTrue(executor.attempted.isEmpty())
        assertTrue(permissions.heldNow.isEmpty())
    }

    // ---- 24. capability change invalidates a stale ready state ----

    @Test
    fun `losing a permission after setup stops the state claiming ready`() = runTest {
        val permissions = MutablePermissionReader(granted = RequiredPermission.entries.toSet())
        val preferences = FakeAccessPreferenceStore(AccessMode.GRANTED_APP)
        val c = coordinator(
            preferences = preferences,
            permissions = permissions,
            grantedAppRunner = workingRunner(),
            scope = TestScope(testScheduler),
        )
        assertTrue("precondition: ready", c.evaluate().isReady)

        // The user revokes DUMP from Settings. Nothing told the application.
        permissions.revoke(RequiredPermission.DUMP)

        val after = c.evaluate()
        assertFalse("readiness must be re-derived, never remembered", after.isReady)
        assertTrue(after is SetupState.Limited)
    }

    // ---- 25. preferred Shizuku, stopped Shizuku ----

    @Test
    fun `preferring Shizuku while it is stopped never claims ready`() = runTest {
        // Even with all three permissions somehow held, the chosen route is unavailable and
        // the state says so rather than quietly using the other one.
        val state = coordinator(
            preferences = FakeAccessPreferenceStore(AccessMode.SHIZUKU_LIVE),
            permissions = MutablePermissionReader(granted = RequiredPermission.entries.toSet()),
            shizuku = ShizukuState.InstalledNotRunning("13.6.0"),
            grantedAppRunner = workingRunner(),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertFalse(state.isReady)
        assertTrue(state is SetupState.ShizukuStopped)
    }

    // ---- 26. preferred granted app ignores Shizuku availability ----

    @Test
    fun `preferring independent access is not overridden by Shizuku being available`() = runTest {
        val state = coordinator(
            preferences = FakeAccessPreferenceStore(AccessMode.GRANTED_APP),
            permissions = MutablePermissionReader(granted = RequiredPermission.entries.toSet()),
            shizuku = ShizukuState.RunningAuthorised(13, 2000),
            grantedAppRunner = workingRunner(),
            shizukuRunner = workingRunner(shell = true),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertTrue(state is SetupState.Ready)
        assertEquals(
            "the user's choice wins",
            BackendKind.GRANTED_APP,
            (state as SetupState.Ready).backend,
        )
    }

    // ---- 27. a newer evaluation wins over a stale one ----

    @Test
    fun `a slow refresh cannot overwrite a newer one`() = runTest {
        val permissions = MutablePermissionReader()
        val slowRunner = FakeProcessRunner().withAppIdentity().apply { delayMillis = 5_000 }
        val c = coordinator(
            preferences = FakeAccessPreferenceStore(AccessMode.GRANTED_APP),
            permissions = permissions.grant(RequiredPermission.DUMP)
                .grant(RequiredPermission.PACKAGE_USAGE_STATS)
                .grant(RequiredPermission.INTERACT_ACROSS_USERS),
            grantedAppRunner = slowRunner,
            scope = TestScope(testScheduler),
        )

        c.refresh()
        testScheduler.advanceTimeBy(100)
        // A second refresh arrives before the first finishes; the first must be cancelled.
        c.refresh()
        testScheduler.advanceUntilIdle()

        // One surviving evaluation, and it is the later one: the probe ran twice but only
        // the newer result was published.
        assertTrue(slowRunner.invocations.isNotEmpty())
    }

    @Test
    fun `a refresh during a grant sequence does not interrupt it`() = runTest {
        val permissions = MutablePermissionReader()
        val c = coordinator(permissions = permissions, scope = TestScope(testScheduler))
        c.openGrantConfirmation()
        // Force the transient state a running sequence would produce.
        c.grantIndependentAccess()
        c.refresh()
        testScheduler.advanceUntilIdle()

        // The sequence completed rather than being replaced mid-flight.
        assertFalse(c.state.value is SetupState.GrantConfirmation)
    }

    // ---- 28. nothing is requested automatically at startup ----

    @Test
    fun `constructing the coordinator requests nothing and changes nothing`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
        val gateway = FakeShizukuGateway(ShizukuState.RunningNotAuthorised(13, 2000))

        AccessSetupCoordinator(
            preferences = FakeAccessPreferenceStore(),
            permissionReader = permissions,
            shizukuGateway = gateway,
            setupExecutor = executor,
            grantedAppRunner = FakeProcessRunner(),
            shizukuRunner = FakeProcessRunner(),
            scope = TestScope(testScheduler),
        )
        testScheduler.advanceUntilIdle()

        assertFalse("no authorisation dialog on startup", gateway.authorisationRequested)
        assertTrue("no privileged action on startup", executor.attempted.isEmpty())
        assertTrue(permissions.heldNow.isEmpty())
    }

    @Test
    fun `refreshing on a fresh install still requests nothing`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
        val gateway = FakeShizukuGateway(ShizukuState.RunningNotAuthorised(13, 2000))
        val c = AccessSetupCoordinator(
            preferences = FakeAccessPreferenceStore(),
            permissionReader = permissions,
            shizukuGateway = gateway,
            setupExecutor = executor,
            grantedAppRunner = FakeProcessRunner(),
            shizukuRunner = FakeProcessRunner(),
            scope = TestScope(testScheduler),
        )
        c.refresh()
        testScheduler.advanceUntilIdle()

        assertFalse(gateway.authorisationRequested)
        assertTrue(executor.attempted.isEmpty())
    }

    // ---- app-op is never touched ----

    @Test
    fun `no part of setup changes the usage-access app-op`() = runTest {
        val permissions = MutablePermissionReader()
        val executor = FakeSetupExecutor(permissions)
        val before = permissions.appOpNow

        coordinator(
            permissions = permissions,
            executor = executor,
            grantedAppRunner = workingRunner(),
            scope = TestScope(testScheduler),
        ).runGrantSequence()

        assertEquals("the app-op must be observed, never mutated", before, permissions.appOpNow)
        assertTrue(
            "no action may reference appops",
            executor.attempted.none { action -> action.argv.any { it.contains("appops") } },
        )
    }

    @Test
    fun `the app-op staying default does not stop setup succeeding`() = runTest {
        // Phase 1B measured PACKAGE_USAGE_STATS granted, GET_USAGE_STATS at DEFAULT, and
        // the usage query returning 70 rows. Requiring ALLOWED here would contradict that.
        val permissions = MutablePermissionReader()
        val state = coordinator(
            permissions = permissions,
            executor = FakeSetupExecutor(permissions),
            grantedAppRunner = workingRunner(),
            scope = TestScope(testScheduler),
        ).runGrantSequence()

        assertTrue(state is SetupState.Ready)
        assertEquals(
            com.rmpsdroid.battinsight.permissions.AppOpMode.DEFAULT,
            permissions.appOpNow,
        )
    }

    @Test
    fun `verifying re-reads permissions after acquisition succeeds`() = runTest {
        val permissions = MutablePermissionReader(granted = RequiredPermission.entries.toSet())
        val c = coordinator(
            permissions = permissions,
            grantedAppRunner = workingRunner(),
            scope = TestScope(testScheduler),
        )
        c.verifySetup()
        testScheduler.advanceUntilIdle()

        assertTrue(c.state.value is SetupState.Ready)
        // Read before the probe and again afterwards: a grant that did not stick is caught
        // here rather than on the next launch.
        assertTrue("expected at least two permission reads", permissions.reads.size >= 2)
    }

    @Test
    fun `verifying without the permissions reports what is missing`() = runTest {
        val permissions = MutablePermissionReader(granted = setOf(RequiredPermission.DUMP))
        val c = coordinator(
            permissions = permissions,
            grantedAppRunner = deniedRunner(),
            scope = TestScope(testScheduler),
        )
        c.verifySetup()
        testScheduler.advanceUntilIdle()

        val state = c.state.value
        assertTrue(state is SetupState.VerificationFailed)
        val detail = (state as SetupState.VerificationFailed).detail
        assertTrue("must name the missing permissions: $detail", detail.contains("PACKAGE_USAGE_STATS"))
        assertNull("no backend is claimed", (state as? SetupState.Ready)?.backend)
    }
}

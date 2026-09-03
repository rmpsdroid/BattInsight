package com.rmpsdroid.battinsight.capability

import com.rmpsdroid.battinsight.collection.BackendAvailability
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.permissions.AppOpMode
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.platform.PackageResolutionReading
import com.rmpsdroid.battinsight.platform.UsageQueryOutcome
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 20 capability scenarios required by Phase 3, exercised entirely on the JVM.
 *
 * Each corresponds to a situation that was either measured on a device or that the
 * measurements proved must stay distinguishable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityCoordinatorTest {

    private fun coordinator(
        grantedRunner: FakeProcessRunner = FakeProcessRunner().withAppIdentity(),
        shizukuRunner: FakeProcessRunner = FakeProcessRunner().withShellIdentity(),
        shizuku: ShizukuState = ShizukuState.NotInstalled,
        permissions: FakePermissionReader = FakePermissionReader.none(),
        usage: UsageQueryOutcome = UsageQueryOutcome.Empty,
        battery: FakeBatterySource = FakeBatterySource.measuredAndroid16(),
        packages: PackageResolutionReading = PackageResolutionReading(4, 4),
        scope: TestScope,
    ) = CapabilityCoordinator(
        grantedAppRunner = grantedRunner,
        shizukuRunner = shizukuRunner,
        shizukuGateway = FakeShizukuGateway(shizuku),
        permissionReader = permissions,
        batterySource = battery,
        usageSource = FakeUsageSource(usage),
        packageSource = FakePackageSource(packages),
        scope = scope,
        clock = { 1_000L },
    )

    // ---- 1. no privileged permissions, no Shizuku ----

    @Test
    fun `no permissions and no shizuku means no usable backend`() = runTest {
        val r = coordinator(scope = TestScope(testScheduler)).evaluate()

        assertTrue(r.usableBackends.isEmpty())
        assertNull(r.preferredBackend)
        assertEquals(ShizukuState.NotInstalled, r.shizuku)

        val backend = r.backend(BackendKind.GRANTED_APP)!!
        assertTrue(backend.availability is BackendAvailability.NotReady)
        // Must name the permission the platform will demand first.
        assertTrue(backend.summary.contains("android.permission.DUMP"))
    }

    // ---- 2. DUMP only ----

    @Test
    fun `DUMP alone is not enough and the next missing permission is named`() = runTest {
        val r = coordinator(
            permissions = FakePermissionReader.of(setOf(RequiredPermission.DUMP)),
            scope = TestScope(testScheduler),
        ).evaluate()

        val state = r.finding(Capability.BATTERY_STATS_AGGREGATE)!!.state
        assertTrue(state is CapabilityState.PermissionMissing)
        assertEquals(
            "android.permission.PACKAGE_USAGE_STATS",
            (state as CapabilityState.PermissionMissing).permission,
        )
    }

    // ---- 3. DUMP + PACKAGE_USAGE_STATS ----

    @Test
    fun `two of three permissions still reports the third as missing`() = runTest {
        val r = coordinator(
            permissions = FakePermissionReader.of(
                setOf(RequiredPermission.DUMP, RequiredPermission.PACKAGE_USAGE_STATS),
            ),
            scope = TestScope(testScheduler),
        ).evaluate()

        val state = r.finding(Capability.BATTERY_STATS_AGGREGATE)!!.state
        assertEquals(
            "android.permission.INTERACT_ACROSS_USERS",
            (state as CapabilityState.PermissionMissing).permission,
        )
    }

    // ---- 4. all three permissions, acquisition works ----

    @Test
    fun `all three permissions gives a usable granted-app backend and working acquisition`() = runTest {
        val runner = FakeProcessRunner().withAppIdentity()
            .on(ProbeCommand.BatteryStatsProto, 0, fakeProtoPayload())
            .on(ProbeCommand.BatteryStatsCheckinCurrent, 0, fakeCheckinWithKwl(68, 43))

        val r = coordinator(
            grantedRunner = runner,
            permissions = FakePermissionReader.all(),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertEquals(listOf(BackendKind.GRANTED_APP), r.usableBackends)
        assertEquals(BackendKind.GRANTED_APP, r.preferredBackend)
        assertEquals(CapabilityState.Available, r.finding(Capability.BATTERY_STATS_AGGREGATE)!!.state)

        val identity = (r.backend(BackendKind.GRANTED_APP)!!.availability as BackendAvailability.Ready).identity
        assertEquals(10241, identity.uid)
        assertFalse(identity.isShellDomain)
    }

    // ---- 5. Shizuku installed but stopped ----

    @Test
    fun `shizuku installed but not running is not usable and says so`() = runTest {
        val r = coordinator(
            shizuku = ShizukuState.InstalledNotRunning("13.6.0"),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertFalse(r.usableBackends.contains(BackendKind.SHIZUKU_ADB))
        assertTrue(r.backend(BackendKind.SHIZUKU_ADB)!!.summary.contains("not running"))
    }

    // ---- 6. Shizuku running but unauthorised ----

    @Test
    fun `shizuku running but unauthorised is distinct from not running`() = runTest {
        val r = coordinator(
            shizuku = ShizukuState.RunningNotAuthorised(13, 2000),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertFalse(r.usableBackends.contains(BackendKind.SHIZUKU_ADB))
        // Measured in Phase 1B: pm grant of Shizuku's permission was not sufficient.
        assertTrue(r.backend(BackendKind.SHIZUKU_ADB)!!.summary.contains("has not authorised"))
    }

    // ---- 7. Shizuku authorised, command works ----

    @Test
    fun `authorised shizuku is usable with the measured shell identity and is preferred`() = runTest {
        val shizukuRunner = FakeProcessRunner().withShellIdentity()
            .on(ProbeCommand.BatteryStatsProto, 0, fakeProtoPayload())
            .on(ProbeCommand.BatteryStatsCheckinCurrent, 0, fakeCheckinWithKwl(68, 43))

        val r = coordinator(
            shizukuRunner = shizukuRunner,
            shizuku = ShizukuState.RunningAuthorised(13, 2000),
            permissions = FakePermissionReader.none(),
            scope = TestScope(testScheduler),
        ).evaluate()

        // Works with zero app permissions -- exactly what Phase 1B measured.
        assertTrue(r.usableBackends.contains(BackendKind.SHIZUKU_ADB))
        assertEquals(BackendKind.SHIZUKU_ADB, r.preferredBackend)

        val identity = (r.backend(BackendKind.SHIZUKU_ADB)!!.availability as BackendAvailability.Ready).identity
        assertEquals(2000, identity.uid)
        assertEquals("u:r:shell:s0", identity.selinuxContext)
        assertTrue(identity.isShellDomain)
        assertEquals(CapabilityState.Available, r.finding(Capability.BATTERY_STATS_AGGREGATE)!!.state)
    }

    // ---- 8. Shizuku command denied ----

    @Test
    fun `shizuku command returning a denial is reported as permission missing`() = runTest {
        val shizukuRunner = FakeProcessRunner().withShellIdentity()
            .on(ProbeCommand.BatteryStatsProto, 0, MeasuredDenials.DUMP)

        val r = coordinator(
            shizukuRunner = shizukuRunner,
            shizuku = ShizukuState.RunningAuthorised(13, 2000),
            scope = TestScope(testScheduler),
        ).evaluate()

        val state = r.finding(Capability.BATTERY_STATS_AGGREGATE)!!.state
        assertEquals(
            "android.permission.DUMP",
            (state as CapabilityState.PermissionMissing).permission,
        )
    }

    // ---- 9. non-zero exit ----

    @Test
    fun `non-zero exit is execution failed, not a permission problem`() = runTest {
        val runner = FakeProcessRunner().withAppIdentity()
            .on(ProbeCommand.BatteryStatsProto, 13, "", "something broke")

        val r = coordinator(
            grantedRunner = runner,
            permissions = FakePermissionReader.all(),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertTrue(r.finding(Capability.BATTERY_STATS_AGGREGATE)!!.state is CapabilityState.ExecutionFailed)
    }

    // ---- 10. exit 0 with denial on stdout ----

    @Test
    fun `exit 0 with a denial on stdout is not treated as success`() = runTest {
        val runner = FakeProcessRunner().withAppIdentity()
            .on(ProbeCommand.BatteryStatsProto, 0, MeasuredDenials.MATCH_ANY_USER)

        val r = coordinator(
            grantedRunner = runner,
            permissions = FakePermissionReader.all(),
            scope = TestScope(testScheduler),
        ).evaluate()

        val state = r.finding(Capability.BATTERY_STATS_AGGREGATE)!!.state
        // The grantable permission, never the _FULL variant.
        assertEquals(
            "android.permission.INTERACT_ACROSS_USERS",
            (state as CapabilityState.PermissionMissing).permission,
        )
    }

    // ---- 11. valid proto acquisition ----

    @Test
    fun `structurally valid protobuf establishes acquisition`() = runTest {
        val runner = FakeProcessRunner().withAppIdentity()
            .on(ProbeCommand.BatteryStatsProto, 0, fakeProtoPayload(90_000))
            .on(ProbeCommand.BatteryStatsCheckinCurrent, 0, fakeCheckinWithKwl(1, 1))

        val r = coordinator(
            grantedRunner = runner,
            permissions = FakePermissionReader.all(),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertEquals(CapabilityState.Available, r.finding(Capability.BATTERY_STATS_AGGREGATE)!!.state)
        // Acquisition available is a narrower claim than every collector available.
        assertEquals(BackendKind.GRANTED_APP, r.finding(Capability.BATTERY_STATS_AGGREGATE)!!.viaBackend)
    }

    // ---- 12. kernel wakelocks with values ----

    @Test
    fun `kernel wakelocks with recorded time are available`() = runTest {
        val runner = FakeProcessRunner().withAppIdentity()
            .on(ProbeCommand.BatteryStatsProto, 0, fakeProtoPayload())
            .on(ProbeCommand.BatteryStatsCheckinCurrent, 0, fakeCheckinWithKwl(111, 43))

        val r = coordinator(
            grantedRunner = runner,
            permissions = FakePermissionReader.all(),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertEquals(CapabilityState.Available, r.finding(Capability.KERNEL_WAKELOCKS)!!.state)
    }

    // ---- 13. kernel wakelocks present but all zero ----

    @Test
    fun `kernel wakelocks with names but zero counters are AvailableNoEvents`() = runTest {
        // The Android 16 emulator: 68 named records, every counter zero, because it never
        // suspends. That is the correct answer, not a failure.
        val runner = FakeProcessRunner().withAppIdentity()
            .on(ProbeCommand.BatteryStatsProto, 0, fakeProtoPayload())
            .on(ProbeCommand.BatteryStatsCheckinCurrent, 0, fakeCheckinWithKwl(68, 0))

        val r = coordinator(
            grantedRunner = runner,
            permissions = FakePermissionReader.all(),
            scope = TestScope(testScheduler),
        ).evaluate()

        val f = r.finding(Capability.KERNEL_WAKELOCKS)!!
        assertTrue("expected AvailableNoEvents, was ${f.state}", f.state is CapabilityState.AvailableNoEvents)
        assertTrue(f.reason.contains("68"))
    }

    // ---- 14. usage stats: no access, empty query ----

    @Test
    fun `usage stats empty without access is permission missing, not no-events`() = runTest {
        // queryUsageStats does not throw without access; it returns empty. The permission
        // state is what disambiguates.
        val r = coordinator(
            permissions = FakePermissionReader.none(),
            usage = UsageQueryOutcome.Empty,
            scope = TestScope(testScheduler),
        ).evaluate()

        val state = r.finding(Capability.USAGE_STATS)!!.state
        assertTrue("expected PermissionMissing, was $state", state is CapabilityState.PermissionMissing)
    }

    // ---- 15. usage stats: access granted, legitimately empty ----

    @Test
    fun `usage stats empty with access granted is AvailableNoEvents`() = runTest {
        val r = coordinator(
            permissions = FakePermissionReader.of(setOf(RequiredPermission.PACKAGE_USAGE_STATS)),
            usage = UsageQueryOutcome.Empty,
            scope = TestScope(testScheduler),
        ).evaluate()

        assertTrue(r.finding(Capability.USAGE_STATS)!!.state is CapabilityState.AvailableNoEvents)
    }

    @Test
    fun `usage access via app-op alone is also honoured`() = runTest {
        // The Settings toggle sets the app-op without granting the permission. Either route
        // is valid, so requiring the permission would misreport this as denied.
        val r = coordinator(
            permissions = FakePermissionReader.of(emptySet(), appOp = AppOpMode.ALLOWED),
            usage = UsageQueryOutcome.Empty,
            scope = TestScope(testScheduler),
        ).evaluate()

        assertTrue(r.finding(Capability.USAGE_STATS)!!.state is CapabilityState.AvailableNoEvents)
    }

    @Test
    fun `granted permission with app-op left at DEFAULT still counts as access`() = runTest {
        // Measured: pm grant left the app-op at DEFAULT and the query still returned rows.
        // Requiring MODE_ALLOWED would contradict that.
        val r = coordinator(
            permissions = FakePermissionReader.of(
                setOf(RequiredPermission.PACKAGE_USAGE_STATS), appOp = AppOpMode.DEFAULT,
            ),
            usage = UsageQueryOutcome.Rows(70),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertEquals(CapabilityState.Available, r.finding(Capability.USAGE_STATS)!!.state)
    }

    // ---- 16. usage stats with rows ----

    @Test
    fun `usage stats with rows is available and reports the count`() = runTest {
        val r = coordinator(
            permissions = FakePermissionReader.all(),
            usage = UsageQueryOutcome.Rows(70),
            scope = TestScope(testScheduler),
        ).evaluate()

        val f = r.finding(Capability.USAGE_STATS)!!
        assertEquals(CapabilityState.Available, f.state)
        assertTrue(f.reason.contains("70"))
    }

    // ---- 17/18. BatteryManager sentinel vs supported ----

    @Test
    fun `battery properties with a sentinel are degraded, not fully available`() = runTest {
        val r = coordinator(
            battery = FakeBatterySource.measuredAndroid16(),
            scope = TestScope(testScheduler),
        ).evaluate()

        val f = r.finding(Capability.BATTERY_PROPERTIES)!!
        assertTrue("expected AvailableDegraded, was ${f.state}", f.state is CapabilityState.AvailableDegraded)
        assertTrue(f.reason.contains("5 of 6"))
    }

    @Test
    fun `battery properties all supported is available and needs no permission`() = runTest {
        val r = coordinator(
            permissions = FakePermissionReader.none(),
            battery = FakeBatterySource.allSupported(),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertEquals(CapabilityState.Available, r.finding(Capability.BATTERY_PROPERTIES)!!.state)
    }

    @Test
    fun `battery manager unavailable is source unavailable`() = runTest {
        val r = coordinator(
            battery = FakeBatterySource.unavailable(),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertTrue(r.finding(Capability.BATTERY_PROPERTIES)!!.state is CapabilityState.SourceUnavailable)
    }

    // ---- 19. degraded UID resolution ----

    @Test
    fun `partial UID name resolution is degraded, not failed`() = runTest {
        // Measured: an app UID saw the same UIDs as a shell but fewer name mappings.
        val r = coordinator(
            packages = PackageResolutionReading(uidsProbed = 4, uidsResolved = 2),
            scope = TestScope(testScheduler),
        ).evaluate()

        val f = r.finding(Capability.UID_NAME_RESOLUTION)!!
        assertTrue("expected AvailableDegraded, was ${f.state}", f.state is CapabilityState.AvailableDegraded)
        assertTrue(f.reason.contains("2 of 4"))
    }

    @Test
    fun `full UID resolution is available`() = runTest {
        val r = coordinator(
            packages = PackageResolutionReading(4, 4),
            scope = TestScope(testScheduler),
        ).evaluate()

        assertEquals(CapabilityState.Available, r.finding(Capability.UID_NAME_RESOLUTION)!!.state)
    }

    // ---- 20. refresh cancellation ----

    @Test
    fun `a slow refresh cannot overwrite a newer one`() = runTest {
        val slow = FakeProcessRunner().withAppIdentity().apply { delayMillis = 10_000 }
        val fast = FakeProcessRunner().withAppIdentity()
            .on(ProbeCommand.BatteryStatsProto, 0, fakeProtoPayload())
            .on(ProbeCommand.BatteryStatsCheckinCurrent, 0, fakeCheckinWithKwl(5, 5))

        val scope = TestScope(testScheduler)
        val c = CapabilityCoordinator(
            grantedAppRunner = slow,
            shizukuRunner = fast,
            shizukuGateway = FakeShizukuGateway(ShizukuState.NotInstalled),
            permissionReader = FakePermissionReader.all(),
            batterySource = FakeBatterySource.allSupported(),
            usageSource = FakeUsageSource(UsageQueryOutcome.Rows(1)),
            packageSource = FakePackageSource(PackageResolutionReading(4, 4)),
            scope = scope,
            clock = { 1L },
        )

        c.refresh()          // starts, then blocks on the slow runner
        c.refresh()          // must cancel the first
        scope.advanceUntilIdle()

        // A completed refresh clears the flag; a stale one never got to publish.
        assertFalse(c.report.value.refreshing)
    }

    // ---- backends that are not implemented must say so ----

    @Test
    fun `root backends are reported as not implemented, never as broken`() = runTest {
        val r = coordinator(scope = TestScope(testScheduler)).evaluate()

        listOf(BackendKind.DIRECT_ROOT, BackendKind.SHIZUKU_ROOT).forEach { kind ->
            val status = r.backend(kind)!!
            assertTrue(
                "$kind should be NotImplemented",
                status.availability is BackendAvailability.NotImplemented,
            )
            assertFalse(kind.implemented)
            assertFalse(kind.measured)
        }
    }

    @Test
    fun `unknown report claims nothing before probing`() {
        val r = CapabilityReport.unknown()
        assertTrue(r.findings.all { it.state == CapabilityState.Unknown })
        assertTrue(r.usableBackends.isEmpty())
    }
}

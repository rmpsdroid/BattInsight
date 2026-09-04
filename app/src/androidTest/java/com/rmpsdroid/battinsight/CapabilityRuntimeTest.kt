package com.rmpsdroid.battinsight

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.capability.Capability
import com.rmpsdroid.battinsight.capability.CapabilityCoordinator
import com.rmpsdroid.battinsight.capability.CapabilityState
import com.rmpsdroid.battinsight.collection.BackendAvailability
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import com.rmpsdroid.battinsight.platform.AndroidBatteryPropertySource
import com.rmpsdroid.battinsight.platform.AndroidPackageResolutionSource
import com.rmpsdroid.battinsight.platform.AndroidPermissionStateReader
import com.rmpsdroid.battinsight.platform.AndroidShizukuGateway
import com.rmpsdroid.battinsight.platform.AndroidUsageAccessSource
import com.rmpsdroid.battinsight.platform.GrantedAppProcessRunner
import com.rmpsdroid.battinsight.shizuku.IProbeService
import com.rmpsdroid.battinsight.shizuku.ProbeService
import com.rmpsdroid.battinsight.shizuku.ShizukuGateway
import com.rmpsdroid.battinsight.shizuku.ShizukuState
import com.rmpsdroid.battinsight.shizuku.ShizukuUserServiceRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runtime validation of the capability architecture on a real Android platform.
 *
 * Everything here is a fact that cannot be established on the JVM: which UID a backend
 * actually runs as, which SELinux domain it lands in, whether the platform hides an
 * installed package from us, and whether a bound Shizuku user service survives its own
 * lifecycle. The JVM suite proves the *logic*; this proves the *platform*.
 *
 * ## What these tests will not do
 *
 * They observe. Nothing here grants a permission, changes an app-op, changes a setting, or
 * resets battery statistics. In particular the three permissions BattInsight needs are
 * never granted by a test -- doing so would make the denial paths untestable and would be a
 * setup action, which belongs to a later phase.
 *
 * ## Running against different Shizuku states
 *
 * Most cases adapt to whatever state Shizuku is in and record it. The lifecycle-specific
 * ones are skipped when their precondition does not hold, so the same class is run once per
 * stage of the transition and each run reports on the stage it is in.
 *
 * Ground truth for the package-visibility audit is supplied by the harness, because the
 * whole question is whether the platform is telling us the truth:
 *
 * ```
 * -e shizukuInstalled true
 * ```
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class CapabilityRuntimeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gateway = AndroidShizukuGateway(context)
    private var runner: ShizukuUserServiceRunner? = null

    @After
    fun tearDown() {
        runner?.release()
        runner = null
    }

    // ------------------------------------------------------------------ observed state

    /**
     * Records the whole capability picture. Always runs, whatever is installed, because the
     * report existing and being self-consistent is itself the contract.
     */
    @Test
    fun capabilityReportIsProducedAndSelfConsistent() = runBlocking {
        val report = coordinator().evaluate()

        Log.i(TAG, "=== capability report ===")
        Log.i(TAG, "shizuku: " + report.shizuku)
        report.backends.forEach { Log.i(TAG, "backend " + it.kind + ": " + it.availability) }
        report.findings.forEach { Log.i(TAG, "finding " + it.capability + ": " + it.state + " -- " + it.reason) }
        Log.i(TAG, "permissions missing: " + report.permissions.missing.map { it.manifestName })

        assertTrue("a refresh must complete", !report.refreshing)
        assertEquals("every backend kind must be accounted for", BackendKind.entries.size, report.backends.size)
        // CapabilityReport.unknown() promises a finding for every capability before
        // anything is checked. A refresh must not shrink that: a capability missing from
        // the report reads as one that does not exist, which is a claim we have not earned.
        val covered = report.findings.map { it.capability }
        assertTrue(
            "every capability must carry a finding; missing " + (Capability.entries - covered.toSet()),
            covered.containsAll(Capability.entries.toList()),
        )
        assertEquals("no capability may carry two findings", covered.size, covered.toSet().size)

        // The ones Phase 3 has no probe for must say so rather than claiming anything.
        val unprobed = report.findings.filter { it.capability !in PROBED_CAPABILITIES }
        Log.i(TAG, "capabilities without a probe yet: " + unprobed.map { it.capability })
        unprobed.forEach {
            assertEquals(
                it.capability.toString() + " has no probe and must report Unknown",
                CapabilityState.Unknown,
                it.state,
            )
        }
        // Root backends were never measured, so they must claim nothing.
        report.backends.filter { it.kind == BackendKind.SHIZUKU_ROOT || it.kind == BackendKind.DIRECT_ROOT }
            .forEach {
                assertTrue(
                    it.kind.toString() + " must not claim availability",
                    it.availability is BackendAvailability.NotImplemented,
                )
            }
    }

    /**
     * Observing must never change what BattInsight holds.
     *
     * Phase 3.1 stated this as "the three permissions are always missing", which was true
     * while nothing in the product could grant them. Phase 4 added a deliberate, opt-in
     * grant flow, so an absolute assertion would now fail for a legitimate reason and say
     * nothing useful. The property actually worth defending is unchanged and is asserted
     * directly: everything in this class reads, and reading moves nothing.
     */
    @Test
    fun observationNeverChangesWhatIsHeld() = runBlocking {
        val reader = AndroidPermissionStateReader(context)
        val before = reader.read()
        Log.i(TAG, "permission snapshot: " + before)

        // Exercise the observation surface of this class.
        coordinator().evaluate()

        val after = reader.read()
        assertEquals("no permission may change by observing", before.missing, after.missing)
        assertEquals("no app-op may change by observing", before.usageStatsAppOp, after.usageStatsAppOp)
    }

    /**
     * Without the permissions, the application's own process must be refused -- and the
     * refusal must be classified as a missing permission, not as a broken source.
     *
     * Asserted against the app-UID backend specifically, not against the report's aggregate
     * finding: once Shizuku is authorised the coordinator prefers it, and the aggregate then
     * correctly reads Available *through Shizuku* while the app's own UID is still denied.
     * Those are two different facts and the test must not confuse them.
     */
    @Test
    fun theAppUidBackendIsRefusedAndSaysWhy() = runBlocking {
        // Only meaningful while the permissions are absent. After the Phase 4 grant flow has
        // run they are legitimately held, and the app UID is legitimately no longer refused.
        val snapshot = AndroidPermissionStateReader(context).read()
        assumeTrue(
            "this covers the ungranted state; the app currently holds " +
                (RequiredPermission.entries - snapshot.missing.toSet()),
            snapshot.missing.isNotEmpty(),
        )

        val out = GrantedAppProcessRunner().run(ProbeCommand.BatteryStatsProto)
        Log.i(TAG, "app-uid proto probe: " + out + " head=" + out.stdoutHead(200).trim())
        assertEquals("the platform answers a denial with exit 0", 0, out.exitCode)
        assertTrue(
            "expected a denial on stdout, got: " + out.stdoutHead(200).trim(),
            out.stdoutHead(400).contains("Permission Denial") ||
                out.stdoutHead(400).contains("Security exception"),
        )

        val report = coordinator().evaluate()
        val granted = report.backends.first { it.kind == BackendKind.GRANTED_APP }
        Log.i(TAG, "granted-app backend: " + granted.availability)
        assertTrue(
            "the app-UID backend must not be Ready without its permissions, was " + granted.availability,
            granted.availability !is BackendAvailability.Ready,
        )
    }

    // ------------------------------------------------------- package visibility audit

    /**
     * The audit question: on Android 11+ the platform filters which packages we can see. If
     * Shizuku is installed but `getPackageInfo` reports otherwise, BattInsight would tell
     * the user to install an app they already have -- advice that is not just useless but
     * actively misleading.
     *
     * Ground truth comes from the harness rather than from the platform, because the
     * platform's answer is the thing under test.
     */
    @Test
    fun installedShizukuIsVisibleToPackageManager() {
        val claimed = InstrumentationRegistry.getArguments().getString(ARG_SHIZUKU_INSTALLED)
        assumeTrue("ground truth not supplied; pass -e " + ARG_SHIZUKU_INSTALLED + " true|false", claimed != null)
        val actuallyInstalled = claimed.toBoolean()

        val visible = try {
            context.packageManager.getPackageInfo(ShizukuGateway.PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        Log.i(TAG, "visibility audit: installed=" + actuallyInstalled + " visibleToUs=" + visible)

        assertEquals(
            "package visibility filtering is hiding an installed Shizuku from us; " +
                "detection would report NotInstalled when the truth is InstalledNotRunning",
            actuallyInstalled,
            visible,
        )
    }

    @Test
    fun shizukuStateMatchesTheGroundTruthWhenSupplied() = runBlocking {
        val claimed = InstrumentationRegistry.getArguments().getString(ARG_SHIZUKU_INSTALLED)
        assumeTrue("ground truth not supplied", claimed != null)
        val state = gateway.state()
        Log.i(TAG, "shizuku state with installed=" + claimed + ": " + state)

        if (!claimed.toBoolean()) {
            assertTrue("uninstalled Shizuku must report NotInstalled but was " + state, state is ShizukuState.NotInstalled)
        } else {
            assertTrue(
                "installed Shizuku must never report NotInstalled, but was " + state,
                state !is ShizukuState.NotInstalled,
            )
        }
    }

    // --------------------------------------------------------------- user service tests

    /**
     * The measurement the Shizuku backend exists for: a whitelisted probe running under a
     * different identity than our own. `uid=2000` and `u:r:shell:s0` are asserted because
     * they were measured, but the code path derives them at runtime rather than assuming.
     */
    @Test
    fun userServiceRunsProbesUnderTheShellIdentity() = runBlocking {
        assumeAuthorised()
        val r = obtainRunner()

        val id = r.run(ProbeCommand.Identity)
        Log.i(TAG, "user service id: " + id + " -> " + id.stdoutHead(200).trim())
        assertEquals("identity probe must exit 0", 0, id.exitCode)
        val idText = id.stdoutHead(200)
        assertTrue("expected uid=2000(shell) but got " + idText.trim(), idText.contains("uid=2000"))

        val ctx = r.run(ProbeCommand.SelinuxIdentity)
        Log.i(TAG, "user service context: " + ctx.stdoutHead(200).trim())
        assertTrue(
            "expected the shell SELinux domain but got " + ctx.stdoutHead(200).trim(),
            ctx.stdoutHead(200).contains(":shell:"),
        )
    }

    /**
     * Acquisition through Shizuku, without any of the three permissions granted to us. This
     * is the whole point of the backend: the shell domain already has what our UID does not.
     */
    @Test
    fun userServiceAcquiresBatteryStatisticsWithoutAppPermissions() = runBlocking {
        assumeAuthorised()
        val r = obtainRunner()

        val proto = r.run(ProbeCommand.BatteryStatsProto)
        Log.i(TAG, "user service proto: " + proto)
        assertEquals(0, proto.exitCode)
        assertTrue("expected a substantial protobuf, got " + proto.stdoutBytes + " bytes", proto.stdoutBytes > 1024)
        assertTrue("payload must not be a denial message", !proto.stdoutHead(400).contains("Permission Denial"))

        val report = coordinator().evaluate()
        val aggregate = report.findings.first { it.capability == Capability.BATTERY_STATS_AGGREGATE }
        Log.i(TAG, "aggregate via " + aggregate.viaBackend + ": " + aggregate.state)
        assertEquals(BackendKind.SHIZUKU_ADB, aggregate.viaBackend)
        assertEquals(CapabilityState.Available, aggregate.state)

        val kwl = report.findings.first { it.capability == Capability.KERNEL_WAKELOCKS }
        Log.i(TAG, "kernel wakelocks: " + kwl.state + " -- " + kwl.reason)
        // On an emulator that never suspends, records exist with zero counters. Either
        // healthy shape is acceptable; a failure is not.
        assertTrue(
            "kernel wakelocks should be obtainable through the shell domain but were " + kwl.state,
            kwl.state is CapabilityState.Available || kwl.state is CapabilityState.AvailableNoEvents,
        )
    }

    /**
     * The security boundary, exercised the way an attacker would: bind the same remote
     * service directly and hand it something that is not a probe identifier. The remote side
     * resolves identifiers against the sealed whitelist, so none of these can reach a
     * process -- there is no command parameter to abuse in the first place.
     */
    @Test
    fun userServiceRefusesAnythingThatIsNotAWhitelistedProbeIdentifier() {
        runBlocking { assumeAuthorised() }
        val remote = bindDirectly()
        assertNotNull("could not bind the user service", remote)

        listOf(
            "id; id",
            "sh -c id",
            "/system/bin/sh",
            "dumpsys batterystats --reset",
            "dumpsys batterystats --enable full-wake-history",
            "",
            "IDENTITY",
            "identity ",
        ).forEach { attempt ->
            val bundle = remote!!.executeProbe(attempt)
            val rejection = bundle.getString(ProbeService.KEY_REJECTION)
            Log.i(TAG, "attempt '" + attempt + "' -> rejection=" + rejection)
            assertNotNull("'" + attempt + "' must be refused, not executed", rejection)
            assertTrue(
                "'" + attempt + "' must not have produced output",
                (bundle.getByteArray(ProbeService.KEY_STDOUT) ?: ByteArray(0)).isEmpty(),
            )
            assertTrue(
                "'" + attempt + "' must not report an exit code, because nothing ran",
                !bundle.getBoolean(ProbeService.KEY_HAS_EXIT, false),
            )
        }

        // And the same channel still works for a real identifier, so the refusal is
        // selective rather than the service being broken.
        val ok = remote!!.executeProbe(ProbeCommand.Identity.id)
        assertTrue("a whitelisted id must still run", ok.getBoolean(ProbeService.KEY_HAS_EXIT, false))
    }

    @Test
    fun releasingTheRunnerDoesNotBreakLaterProbes() = runBlocking {
        assumeAuthorised()
        val r = obtainRunner()
        assertEquals(0, r.run(ProbeCommand.Identity).exitCode)
        r.release()
        // A released runner must rebind rather than fail, because the capability centre can
        // be refreshed at any time after the screen that owned it went away.
        val after = r.run(ProbeCommand.Identity)
        Log.i(TAG, "probe after release: " + after)
        assertEquals("the runner must rebind after release", 0, after.exitCode)
    }

    @Test
    fun concurrentProbesShareASingleBind() = runBlocking {
        assumeAuthorised()
        val r = obtainRunner()
        val results = kotlinx.coroutines.coroutineScope {
            listOf(
                async(Dispatchers.IO) { r.run(ProbeCommand.Identity) },
                async(Dispatchers.IO) { r.run(ProbeCommand.SelinuxIdentity) },
                async(Dispatchers.IO) { r.run(ProbeCommand.Identity) },
            ).map { it.await() }
        }
        results.forEach { assertEquals("every concurrent probe must succeed: " + it, 0, it.exitCode) }
    }

    /**
     * When Shizuku is installed but not running, or running but not authorised, the backend
     * must report itself unusable rather than appearing to work.
     */
    @Test
    fun anUnusableShizukuNeverReportsReady() = runBlocking {
        val state = gateway.state()
        assumeTrue("this case covers the unusable states only", !state.isUsable)
        Log.i(TAG, "unusable shizuku state: " + state)

        val report = coordinator().evaluate()
        val shizuku = report.backends.first { it.kind == BackendKind.SHIZUKU_ADB }
        Log.i(TAG, "shizuku backend availability: " + shizuku.availability)
        assertTrue(
            "an unusable Shizuku must not be Ready, but was " + shizuku.availability,
            shizuku.availability !is BackendAvailability.Ready,
        )

        // And a probe attempted anyway must fail cleanly rather than hang or throw.
        val out = obtainRunner().run(ProbeCommand.Identity)
        Log.i(TAG, "probe against unusable shizuku: " + out)
        assertTrue("a probe with no usable backend must not report an exit code", out.exitCode == null)
    }

    // ------------------------------------------------------------------------ helpers

    private fun coordinator() = CapabilityCoordinator(
        grantedAppRunner = GrantedAppProcessRunner(),
        shizukuRunner = obtainRunner(),
        shizukuGateway = gateway,
        permissionReader = AndroidPermissionStateReader(context),
        batterySource = AndroidBatteryPropertySource(context),
        usageSource = AndroidUsageAccessSource(context),
        packageSource = AndroidPackageResolutionSource(context),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private fun obtainRunner(): ShizukuUserServiceRunner =
        runner ?: ShizukuUserServiceRunner(context, gateway).also { runner = it }

    private suspend fun assumeAuthorised() {
        val state = gateway.state()
        assumeTrue("Shizuku is not authorised (state: " + state + ")", state is ShizukuState.RunningAuthorised)
    }

    /** Binds the remote service directly, to exercise the Binder contract itself. */
    /**
     * Binds a second, independent connection to the same remote service.
     *
     * Retried, boundedly, for the reason `ShizukuUserServiceRunner` documents: Shizuku
     * delivers a previous service record's teardown to whatever connection is registered
     * under that tag, so a bind attempted while an earlier test's service is still going
     * away sees `onServiceDisconnected` before `onServiceConnected`. The production runner
     * handles that; this ad-hoc helper has to as well, or the security assertion below
     * fails for a reason that has nothing to do with security.
     */
    private fun bindDirectly(): IProbeService? {
        repeat(DIRECT_BIND_ATTEMPTS) { attempt ->
            bindDirectlyOnce()?.let { return it }
            if (attempt < DIRECT_BIND_ATTEMPTS - 1) Thread.sleep(DIRECT_BIND_RETRY_MS)
        }
        return null
    }

    private fun bindDirectlyOnce(): IProbeService? {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ProbeService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("probe_direct")
            .debuggable(false)
            .version(1)

        var bound: IProbeService? = null
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound = binder?.let { IProbeService.Stub.asInterface(it) }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bound = null
                latch.countDown()
            }
        }
        Shizuku.bindUserService(args, conn)
        latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        directConnection = args to conn
        if (bound == null) {
            // Release the failed attempt before trying again, so records do not accumulate.
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            directConnection = null
        }
        return bound
    }

    private var directConnection: Pair<Shizuku.UserServiceArgs, ServiceConnection>? = null

    @After
    fun unbindDirect() {
        directConnection?.let { (args, conn) ->
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
        directConnection = null
    }

    private companion object {
        const val TAG = "BattInsightRuntime"
        const val ARG_SHIZUKU_INSTALLED = "shizukuInstalled"
        const val BIND_TIMEOUT_SECONDS = 20L

        /** Bounded, matching the production runner's handling of the same race. */
        const val DIRECT_BIND_ATTEMPTS = 3
        const val DIRECT_BIND_RETRY_MS = 400L

        /** What Phase 3 actually probes. Growing this is Phase 4's job, not a test's. */
        val PROBED_CAPABILITIES = listOf(
            Capability.BATTERY_STATS_AGGREGATE,
            Capability.KERNEL_WAKELOCKS,
            Capability.USAGE_STATS,
            Capability.BATTERY_PROPERTIES,
            Capability.UID_NAME_RESOLUTION,
        )
    }
}

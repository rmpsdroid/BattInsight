package com.rmpsdroid.batterydiagnostics.capability

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests exist to stop the state model being collapsed back into a boolean.
 *
 * Each asserts a distinction that a real measurement made necessary. If someone later
 * merges two of these states, a test should fail and point at the measurement that
 * justified keeping them apart.
 */
class CapabilityStateTest {

    @Test
    fun `no events is not the same as source unavailable`() {
        // Android 16 emulator: 68 named kernel wakelocks, every counter zero, because the
        // device never suspends. The source is healthy; there is simply nothing to report.
        val healthyButQuiet: CapabilityState =
            CapabilityState.AvailableNoEvents("device has not suspended")
        val absent: CapabilityState =
            CapabilityState.SourceUnavailable("/sys/kernel/debug/wakeup_sources")

        assertNotEquals(healthyButQuiet, absent)
    }

    @Test
    fun `not supported is not the same as permission missing`() {
        // The specific wrong answer both predecessors gave: telling a user to check
        // permissions when the kernel does not expose the data at all.
        val kernelLacksIt: CapabilityState =
            CapabilityState.NotSupported("debugfs not mounted on Android 12+ user builds")
        val fixable: CapabilityState =
            CapabilityState.PermissionMissing("android.permission.DUMP")

        assertNotEquals(kernelLacksIt, fixable)
    }

    @Test
    fun `degraded is distinct from available`() {
        // App UID saw the same 98 UIDs as ADB shell but 152 name mappings against 180.
        // Acquisition succeeded; naming did not.
        val partial: CapabilityState =
            CapabilityState.AvailableDegraded("package visibility limits UID name resolution")

        assertNotEquals(CapabilityState.Available, partial)
    }

    @Test
    fun `unknown is neither available nor unavailable`() {
        val unprobed: CapabilityState = CapabilityState.Unknown

        assertNotEquals(CapabilityState.Available, unprobed)
        assertNotEquals(
            CapabilityState.SourceUnavailable("anything") as CapabilityState,
            unprobed,
        )
    }

    @Test
    fun `permission missing carries the permission name so onboarding can act on it`() {
        val state = CapabilityState.PermissionMissing("android.permission.PACKAGE_USAGE_STATS")

        // "Check your permissions" is not an acceptable message; the exact one must be known.
        assertTrue(state.permission.startsWith("android.permission."))
    }

    @Test
    fun `every state is representable and distinguishable`() {
        val all: List<CapabilityState> = listOf(
            CapabilityState.Available,
            CapabilityState.AvailableNoEvents("a"),
            CapabilityState.AvailableDegraded("b"),
            CapabilityState.PermissionMissing("c"),
            CapabilityState.NotSupported("d"),
            CapabilityState.SourceUnavailable("e"),
            CapabilityState.ExecutionFailed("f"),
            CapabilityState.Unknown,
        )

        assertTrue("states collapsed", all.toSet().size == all.size)
    }
}

package com.rmpsdroid.battinsight.access

import com.rmpsdroid.battinsight.collection.AccessModeBackendSelector
import com.rmpsdroid.battinsight.collection.BackendAvailability
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.BackendKind
import com.rmpsdroid.battinsight.setup.FakeAccessPreferenceStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The access preference, and the rule that turns it into a backend.
 *
 * The property being defended: a privileged mode is never silently substituted for another.
 * The two working modes differ in what BattInsight itself ends up holding, so switching is
 * a decision the user makes, not a fallback the application performs quietly.
 */
class AccessModeTest {

    private val ready = BackendAvailability.Ready(
        BackendIdentity(uid = 2000, selinuxContext = "u:r:shell:s0", kind = BackendIdentity.Kind.SHELL),
    )
    private val notReady = BackendAvailability.NotReady("Shizuku is installed but not running")
    private val appReady = BackendAvailability.Ready(
        BackendIdentity(uid = 10241, selinuxContext = "u:r:untrusted_app:s0", kind = BackendIdentity.Kind.APP_UID),
    )
    private val appNotReady = BackendAvailability.NotReady("Missing android.permission.DUMP")

    // ---- 23. the preference survives recreation ----

    @Test
    fun `a stored access mode survives recreation`() = runTest {
        val store = FakeAccessPreferenceStore()
        store.setAccessMode(AccessMode.SHIZUKU_LIVE)

        val restarted = store.recreate()
        assertEquals(AccessMode.SHIZUKU_LIVE, restarted.current())
    }

    @Test
    fun `limited mode survives recreation, so the user is not asked again`() = runTest {
        val store = FakeAccessPreferenceStore()
        store.setAccessMode(AccessMode.LIMITED)
        assertEquals(AccessMode.LIMITED, store.recreate().current())
    }

    @Test
    fun `an unrecognised stored value falls back to asking rather than assuming`() {
        assertEquals(AccessMode.NOT_CHOSEN, AccessMode.fromStoredValue("SHIZUKU_ROOT"))
        assertEquals(AccessMode.NOT_CHOSEN, AccessMode.fromStoredValue(null))
        assertEquals(AccessMode.NOT_CHOSEN, AccessMode.fromStoredValue(""))
        assertEquals(AccessMode.SHIZUKU_LIVE, AccessMode.fromStoredValue("SHIZUKU_LIVE"))
    }

    @Test
    fun `no mode selects a root backend`() {
        AccessMode.entries.forEach {
            assertTrue(
                "$it must not select an unmeasured root backend",
                it.backend != BackendKind.SHIZUKU_ROOT && it.backend != BackendKind.DIRECT_ROOT,
            )
        }
    }

    @Test
    fun `only the granted-app mode expects BattInsight to hold permissions`() {
        assertTrue(AccessMode.GRANTED_APP.requiresAppPermissions)
        assertFalse(AccessMode.SHIZUKU_LIVE.requiresAppPermissions)
        assertFalse(AccessMode.LIMITED.requiresAppPermissions)
        assertFalse(AccessMode.NOT_CHOSEN.requiresAppPermissions)
    }

    // ---- backend selection ----

    @Test
    fun `choosing Shizuku uses Shizuku when it is ready`() {
        val s = AccessModeBackendSelector(AccessMode.SHIZUKU_LIVE).select(ready, appNotReady)
        assertEquals(BackendKind.SHIZUKU_ADB, s.active)
        assertEquals(BackendKind.SHIZUKU_ADB, s.preferred)
        assertNull(s.fallbackOffer)
    }

    @Test
    fun `choosing Shizuku while it is stopped offers the alternative without taking it`() {
        val s = AccessModeBackendSelector(AccessMode.SHIZUKU_LIVE).select(notReady, appReady)

        assertNull("a working alternative must not be substituted silently", s.active)
        assertEquals(BackendKind.GRANTED_APP, s.fallbackOffer)
        assertTrue(s.canOfferFallback)
        assertTrue("the reason must name the real problem: ${s.reason}", s.reason.contains("not running"))
    }

    @Test
    fun `choosing independent access is not overridden by Shizuku being ready`() {
        val s = AccessModeBackendSelector(AccessMode.GRANTED_APP).select(ready, appReady)
        assertEquals(BackendKind.GRANTED_APP, s.active)
        assertNull("no fallback is needed when the choice works", s.fallbackOffer)
    }

    @Test
    fun `choosing independent access while it is unavailable offers Shizuku, unapplied`() {
        val s = AccessModeBackendSelector(AccessMode.GRANTED_APP).select(ready, appNotReady)
        assertNull(s.active)
        assertEquals(BackendKind.SHIZUKU_ADB, s.fallbackOffer)
    }

    @Test
    fun `limited mode selects no backend at all`() {
        val s = AccessModeBackendSelector(AccessMode.LIMITED).select(ready, appReady)
        assertNull(s.active)
        assertNull(s.preferred)
        assertNull("limited mode offers nothing; the user said no", s.fallbackOffer)
    }

    @Test
    fun `no choice selects no backend and says so`() {
        val s = AccessModeBackendSelector(AccessMode.NOT_CHOSEN).select(ready, appReady)
        assertNull(s.active)
        assertFalse(s.hasActiveBackend)
        assertTrue(s.reason.isNotBlank())
    }

    @Test
    fun `nothing available yields no backend and a stated reason`() {
        val s = AccessModeBackendSelector(AccessMode.SHIZUKU_LIVE).select(notReady, appNotReady)
        assertNull(s.active)
        assertNull(s.fallbackOffer)
        assertNotNull(s.reason)
        assertTrue(s.reason.isNotBlank())
    }

    @Test
    fun `every combination yields exactly one deterministic answer`() {
        val availabilities = listOf(
            ready,
            notReady,
            BackendAvailability.Unknown,
            BackendAvailability.Failed("boom"),
            BackendAvailability.NotImplemented("no root measured"),
        )
        AccessMode.entries.forEach { mode ->
            availabilities.forEach { shizuku ->
                availabilities.forEach { app ->
                    val selector = AccessModeBackendSelector(mode)
                    val first = selector.select(shizuku, app)
                    val second = selector.select(shizuku, app)
                    assertEquals("selection must be deterministic", first, second)
                    assertTrue("every answer must carry a reason", first.reason.isNotBlank())
                    assertTrue(
                        "an offered fallback must never also be active",
                        first.fallbackOffer == null || first.active == null,
                    )
                }
            }
        }
    }
}

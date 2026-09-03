package com.rmpsdroid.batterydiagnostics.permissions

import com.rmpsdroid.batterydiagnostics.collection.BackendIdentity
import com.rmpsdroid.batterydiagnostics.collection.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards on the measured facts encoded in the foundation contracts. */
class FoundationContractsTest {

    // ---- permissions ----

    @Test
    fun `minimum set is exactly the three measured permissions`() {
        assertEquals(
            listOf(
                "android.permission.DUMP",
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.INTERACT_ACROSS_USERS",
            ),
            RequiredPermission.minimumSet.map { it.manifestName },
        )
    }

    @Test
    fun `BATTERY_STATS is not in the required set`() {
        // Measured unnecessary in Phase 1B. Both predecessors told users to grant it.
        assertFalse(
            RequiredPermission.minimumSet.any {
                it.manifestName == RequiredPermission.NOT_REQUIRED_BATTERY_STATS
            },
        )
    }

    @Test
    fun `grant command is well formed`() {
        assertEquals(
            "pm grant com.example.app android.permission.DUMP",
            RequiredPermission.DUMP.grantCommand("com.example.app"),
        )
    }

    // ---- source formats ----

    @Test
    fun `checkin uses the non-clearing argument`() {
        // --checkin is documented by the platform to write and clear old completed stats.
        // -c does not. Using --checkin is prohibited project-wide.
        assertEquals("-c", SourceFormat.CHECKIN.dumpsysArgument)
        assertFalse(
            SourceFormat.entries.any { it.dumpsysArgument.contains("--checkin") },
        )
    }

    @Test
    fun `no format carries a state-changing argument`() {
        val forbidden = listOf("--reset", "--reset-all", "--write", "--new-daily", "--read-daily")
        SourceFormat.entries.forEach { format ->
            forbidden.forEach { bad ->
                assertFalse(
                    "${format.name} must not use $bad",
                    format.dumpsysArgument.contains(bad),
                )
            }
        }
    }

    @Test
    fun `text is not a parse target but proto and checkin are`() {
        assertFalse(SourceFormat.TEXT.isParseTarget)
        assertEquals(
            setOf(SourceFormat.PROTO, SourceFormat.CHECKIN),
            SourceFormat.parseTargets.toSet(),
        )
    }

    // ---- backend identity ----

    @Test
    fun `shell domain is recognised from the measured Shizuku identity`() {
        // Phase 1B measured ADB-started Shizuku at uid 2000 / u:r:shell:s0.
        val shizuku = BackendIdentity(2000, "u:r:shell:s0", BackendIdentity.Kind.SHELL)
        assertTrue(shizuku.isShellDomain)
    }

    @Test
    fun `app uid is not a shell domain`() {
        val app = BackendIdentity(
            uid = 10241,
            selinuxContext = "u:r:untrusted_app:s0:c241,c256,c512,c768",
            kind = BackendIdentity.Kind.APP_UID,
        )
        assertFalse(app.isShellDomain)
    }

    @Test
    fun `unknown identity is not mistaken for a real one`() {
        assertEquals(-1, BackendIdentity.UNKNOWN.uid)
        assertTrue(BackendIdentity.UNKNOWN.selinuxContext.isEmpty())
    }
}

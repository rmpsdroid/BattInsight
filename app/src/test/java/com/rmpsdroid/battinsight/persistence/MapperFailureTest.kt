package com.rmpsdroid.battinsight.persistence

import com.rmpsdroid.battinsight.session.BootIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the mapper does with stored data it cannot honestly interpret.
 *
 * Every case here has a tempting lenient alternative -- default the enum, coerce the boot
 * kind, skip the missing snapshot -- and each of those turns "this history is unreadable"
 * into a confident false statement about the user's battery. Refusing is the feature.
 *
 * Pure: no database, no Robolectric. These are decisions about bytes, not about SQLite.
 */
class MapperFailureTest {

    private val good = Mappers.toEntity(fullSnapshot())

    // ------------------------------------------------------------- unreadable enum names

    @Test
    fun `an unrecognised enum name is refused, never defaulted to UNKNOWN`() {
        val e = assertThrows(SnapshotMappingException::class.java) {
            Mappers.toDomain(good.copy(batteryStatus = "SUPERCHARGING"))
        }
        assertTrue(
            "the message must name the offending value: ${e.message}",
            e.message!!.contains("SUPERCHARGING"),
        )
    }

    @Test
    fun `each stored enum column is validated independently`() {
        listOf(
            good.copy(plugSource = "SOLAR"),
            good.copy(batteryHealth = "MOSTLY_FINE"),
            good.copy(trigger = "TELEPATHY"),
            good.copy(observationTrigger = "TELEPATHY"),
            good.copy(counterSource = "GUESSWORK"),
        ).forEach { corrupt ->
            assertThrows(SnapshotMappingException::class.java) { Mappers.toDomain(corrupt) }
        }
    }

    // ------------------------------------------------------------------ boot identity

    /**
     * The rule the comparability layer depends on: strength never increases on the way back.
     *
     * A `Derived` boot time is a diagnostic estimate. If a round trip could return it as a
     * `Kernel` identity, every monotonic comparison downstream would treat an estimate as
     * proof, and would conclude things the original measurement never supported.
     */
    @Test
    fun `a derived boot identity never reloads as a kernel identity`() {
        val derived = Mappers.toEntity(fullSnapshot(boot = BootIdentity.Derived(1_234_567L)))
        val restored = Mappers.toDomain(derived).bootIdentity

        assertEquals(BootIdentity.Derived(1_234_567L), restored)
        assertTrue("must not be promoted to proof", restored !is BootIdentity.Kernel)
    }

    @Test
    fun `a boot kind whose value column is empty is refused rather than coerced`() {
        assertThrows(SnapshotMappingException::class.java) {
            Mappers.toDomain(good.copy(bootKind = Mappers.BOOT_KERNEL, bootKernelId = null))
        }
        assertThrows(SnapshotMappingException::class.java) {
            Mappers.toDomain(good.copy(bootKind = Mappers.BOOT_DERIVED, bootDerivedMillis = null))
        }
    }

    @Test
    fun `an unrecognised boot kind is refused, not treated as unknown`() {
        val e = assertThrows(SnapshotMappingException::class.java) {
            Mappers.toDomain(good.copy(bootKind = "PSYCHIC"))
        }
        assertTrue(e.message!!.contains("PSYCHIC"))
    }

    // ------------------------------------------------------------------ identifiers

    @Test
    fun `a malformed identifier is refused`() {
        val e = assertThrows(SnapshotMappingException::class.java) {
            Mappers.toDomain(good.copy(snapshotId = "not-a-uuid"))
        }
        assertTrue(e.message!!.contains("not a valid identifier"))
    }

    // ------------------------------------------------------------------ dangling references

    /**
     * A session naming a snapshot that is not stored is corrupt, not merely incomplete.
     *
     * The lenient reading -- treat the missing start as absent and carry on -- would produce
     * a session with no beginning, and every duration computed from it would be wrong rather
     * than missing.
     */
    @Test
    fun `a session referencing an unstored snapshot is refused`() {
        val session = Mappers.toEntity(activeSession())
        val e = assertThrows(SnapshotMappingException::class.java) {
            Mappers.toDomain(session, snapshotsById = emptyMap())
        }
        assertTrue(e.message!!.contains("not stored"))
    }

    @Test
    fun `a closed session missing its end snapshot is refused`() {
        val closed = closedSession()
        val entity = Mappers.toEntity(closed)
        // Only the start snapshot survives. In a closed session `latest` and `end` are the
        // same row, so removing the end removes both -- which is precisely the shape a
        // partially deleted history would have.
        val available = mapOf(closed.start.id.toString() to closed.start)

        assertThrows(SnapshotMappingException::class.java) {
            Mappers.toDomain(entity, available)
        }
    }
}

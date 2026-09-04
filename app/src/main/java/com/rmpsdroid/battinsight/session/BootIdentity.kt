package com.rmpsdroid.battinsight.session

import kotlin.math.abs

/**
 * Which boot an observation belongs to.
 *
 * Everything monotonic depends on this. `elapsedRealtime` restarts at zero on every boot,
 * so two readings can only be ordered — or subtracted — once they are known to share one.
 *
 * The type carries *how well* that is known, because the honest answers are three, not two.
 * The predecessor deleted all history on every boot, which is one way of never having to
 * answer the question; refusing an invalid comparison and keeping the data is the other.
 */
sealed interface BootIdentity {

    /**
     * The kernel's own identifier, from `/proc/sys/kernel/random/boot_id`.
     *
     * Authoritative in both directions: equal means the same boot, unequal means a
     * different one. This is the only variant that can prove sameness.
     */
    data class Kernel(val id: String) : BootIdentity

    /**
     * A fallback derived from wall clock minus elapsed realtime.
     *
     * Used when the kernel identifier cannot be read. It can establish that two
     * observations are from **different** boots — a large jump in apparent boot time cannot
     * happen within one — but it can never establish that they are from the same one,
     * because two separate boots can easily produce close values, and because the two
     * clocks drift relative to each other anyway.
     *
     * Modelled explicitly rather than papered over: a fallback that quietly claimed
     * sameness would be inventing certainty the data does not contain, and every counter
     * delta downstream would inherit that invention.
     */
    data class Derived(val approximateBootWallClockMillis: Long) : BootIdentity

    /** No identity could be established at all. */
    data object Unknown : BootIdentity

    /** A short form for diagnostics. Never logs a full identifier. */
    val abbreviated: String
        get() = when (this) {
            is Kernel -> id.take(8)
            is Derived -> "~" + (approximateBootWallClockMillis / 1000)
            Unknown -> "unknown"
        }

    /** Whether this identity can ever prove two observations share a boot. */
    val canProveSameness: Boolean get() = this is Kernel

    companion object {
        /**
         * How far two derived boot times may differ and still be treated as inconclusive
         * rather than certainly different.
         *
         * Generous on purpose. The two clocks drift, `elapsedRealtime` may or may not
         * advance during deep sleep on a given device, and NTP corrections move the wall
         * clock underneath us. A tolerance that is too tight would report a boot change
         * that never happened, which is worse than reporting "unknown": one is a false
         * statement, the other is an accurate one.
         */
        const val DERIVED_TOLERANCE_MILLIS: Long = 10 * 60 * 1000L
    }
}

/** How two boot identities relate. Three answers, because there are three. */
enum class BootRelation {
    /** Proven to be the same boot. Monotonic comparison is permitted. */
    SAME,

    /** Proven to be different boots. Monotonic comparison is meaningless. */
    DIFFERENT,

    /** Not established either way. Nothing monotonic may be concluded. */
    UNKNOWN,
}

/**
 * Compares two boot identities without ever guessing.
 *
 * The asymmetry is deliberate: [BootIdentity.Derived] may return [BootRelation.DIFFERENT]
 * but never [BootRelation.SAME], and a [BootIdentity.Kernel] compared against a
 * [BootIdentity.Derived] is [BootRelation.UNKNOWN] because they measure different things.
 */
fun BootIdentity.relationTo(other: BootIdentity): BootRelation = when {
    this is BootIdentity.Kernel && other is BootIdentity.Kernel ->
        if (id == other.id) BootRelation.SAME else BootRelation.DIFFERENT

    this is BootIdentity.Derived && other is BootIdentity.Derived -> {
        val drift = abs(approximateBootWallClockMillis - other.approximateBootWallClockMillis)
        // Only ever DIFFERENT or UNKNOWN. A derived identity cannot prove sameness.
        if (drift > BootIdentity.DERIVED_TOLERANCE_MILLIS) BootRelation.DIFFERENT
        else BootRelation.UNKNOWN
    }

    else -> BootRelation.UNKNOWN
}

/** Reads the device's boot identity. An interface so the engine is testable without Android. */
interface BootIdentitySource {
    fun read(): BootIdentity
}

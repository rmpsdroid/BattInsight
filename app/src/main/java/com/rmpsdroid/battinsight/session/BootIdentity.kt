package com.rmpsdroid.battinsight.session

/**
 * Which boot an observation belongs to.
 *
 * Everything monotonic depends on this. `elapsedRealtime` restarts at zero on every boot,
 * so two readings can only be ordered -- or subtracted -- once they are known to share one.
 *
 * The type carries *how well* that is known, because the honest answers are three, not two.
 * The predecessor deleted all history on every boot, which is one way of never having to
 * answer the question; refusing an invalid comparison and keeping the data is the other.
 *
 * ## Only the kernel identifier proves anything
 *
 * [Kernel] is the sole authoritative variant. Everything else yields
 * [BootRelation.UNKNOWN], in both directions, always.
 *
 * That is stricter than it first appears necessary, and the reason is worth stating: an
 * earlier version of this file treated a large change in the *estimated* boot time as proof
 * of a reboot. It is not. Android's contract is explicit that `System.currentTimeMillis`
 * may be changed by the user or the network and may jump either way at any moment, while
 * `SystemClock.elapsedRealtime` continues undisturbed -- so a clock correction of six hours
 * moves the estimate by six hours on one uninterrupted boot. Reading that as a reboot would
 * have split a real session and labelled the break *device restarted*, which is worse than
 * admitting ignorance: a confident false statement rather than an honest "cannot tell".
 */
sealed interface BootIdentity {

    /**
     * The kernel's own identifier, from `/proc/sys/kernel/random/boot_id`.
     *
     * The only variant that establishes anything. Equal means the same boot; unequal means
     * a different one. Measured readable by an ordinary application on Android 16.
     */
    data class Kernel(val id: String) : BootIdentity

    /**
     * A non-authoritative estimate of when this boot began, from wall clock minus elapsed
     * realtime.
     *
     * Used when the kernel identifier cannot be read. It **proves nothing**: it cannot
     * establish that two observations share a boot, and -- the correction this variant now
     * embodies -- it cannot establish that they do not either, because the wall clock it is
     * built from moves independently of any reboot.
     *
     * It is retained because the estimate is genuinely useful in a diagnostic export, where
     * an approximate boot time helps a human make sense of a timeline. It simply may not
     * decide anything.
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

    /**
     * Whether this identity can establish a boot relation at all, in either direction.
     *
     * True only for [Kernel]. Named for the relation rather than for sameness because the
     * correction this file embodies is symmetric: the fallback can prove neither that two
     * observations share a boot nor that they do not.
     */
    val canProveBootRelation: Boolean get() = this is Kernel
}

/** How two boot identities relate. Three answers, because there are three. */
enum class BootRelation {
    /** Proven to be the same boot. Monotonic comparison is permitted. */
    SAME,

    /** Proven to be different boots. Monotonic comparison is meaningless. */
    DIFFERENT,

    /**
     * Not established either way.
     *
     * The answer whenever the kernel identifier is unavailable. It is not a failure state:
     * it is the accurate description of what the available evidence supports, and callers
     * are expected to behave conservatively rather than pick a side.
     */
    UNKNOWN,
}

/**
 * Compares two boot identities without ever guessing.
 *
 * Total, and deliberately unexciting: two kernel identifiers decide, and every other
 * combination is [BootRelation.UNKNOWN].
 *
 * Nothing here consults a clock, an estimate or a tolerance. There is no tolerance left to
 * consult -- a threshold on the derived estimate was the defect this function was rewritten
 * to remove, and reintroducing one would reintroduce it.
 */
fun BootIdentity.relationTo(other: BootIdentity): BootRelation =
    if (this is BootIdentity.Kernel && other is BootIdentity.Kernel) {
        if (id == other.id) BootRelation.SAME else BootRelation.DIFFERENT
    } else {
        BootRelation.UNKNOWN
    }

/** Reads the device's boot identity. An interface so the engine is testable without Android. */
interface BootIdentitySource {
    fun read(): BootIdentity
}

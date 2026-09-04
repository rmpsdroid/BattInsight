package com.rmpsdroid.battinsight.access

import com.rmpsdroid.battinsight.collection.BackendKind

/**
 * How the user has chosen to give BattInsight access to system statistics.
 *
 * This is a **preference**, not a capability. It records what the user picked, and says
 * nothing about whether that route currently works: Shizuku can stop, permissions can be
 * revoked, and a device can reboot. `CapabilityCoordinator` remains the only authority on
 * what is actually possible right now. Every screen that shows readiness derives it from a
 * fresh capability report, never from this value alone.
 *
 * The two working modes differ in *security posture*, not merely convenience, which is why
 * the user chooses rather than the application guessing:
 *
 *  - [SHIZUKU_LIVE] leaves BattInsight holding none of the three elevated permissions. The
 *    privileged work happens in a Shizuku-owned process for as long as Shizuku runs.
 *  - [GRANTED_APP] gives BattInsight `DUMP`, `PACKAGE_USAGE_STATS` and
 *    `INTERACT_ACROSS_USERS` permanently, until revoked, in exchange for working without
 *    Shizuku running.
 *
 * ## Why there is no AUTO mode
 *
 * An automatic mode would have to pick between those two on the user's behalf. They are not
 * interchangeable — one permanently elevates this application's own privileges and the
 * other does not — so choosing silently would be making a security decision for someone
 * without telling them. A deterministic rule could be written; it still would not be an
 * honest one. Fallback is therefore *offered*, never applied (see `BackendSelection`).
 */
enum class AccessMode {

    /**
     * Nothing chosen yet. The state of a fresh install.
     *
     * Distinct from [LIMITED]: this means the question has not been asked, so onboarding
     * should ask it. [LIMITED] means the user answered "not now", which must be respected.
     */
    NOT_CHOSEN,

    /**
     * Use the running Shizuku service directly.
     *
     * Measured in Phase 1B/3.1: faster than app-UID execution, better UID and package-name
     * visibility, and it needs none of BattInsight's declared permissions. Shizuku must be
     * running, and an ADB-started or Wireless-Debugging Shizuku typically needs starting
     * again after a reboot.
     */
    SHIZUKU_LIVE,

    /**
     * Use BattInsight's own process, holding the three permissions.
     *
     * Works without Shizuku afterwards. Phase 1B measured somewhat poorer package-name
     * resolution from the app UID than from the shell domain.
     */
    GRANTED_APP,

    /**
     * Explore the application without any privileged access.
     *
     * A deliberate choice, remembered so onboarding does not ask again. Detailed battery
     * diagnostics are unavailable in this mode and the UI says so plainly rather than
     * presenting it as an error.
     */
    LIMITED,
    ;

    /** The backend this mode wants, or null when the mode asks for no privileged backend. */
    val backend: BackendKind?
        get() = when (this) {
            SHIZUKU_LIVE -> BackendKind.SHIZUKU_ADB
            GRANTED_APP -> BackendKind.GRANTED_APP
            NOT_CHOSEN, LIMITED -> null
        }

    /** Whether the user has answered the access question at all. */
    val isChosen: Boolean get() = this != NOT_CHOSEN

    /** Whether this mode expects BattInsight itself to hold the three permissions. */
    val requiresAppPermissions: Boolean get() = this == GRANTED_APP

    val label: String
        get() = when (this) {
            NOT_CHOSEN -> "Not set up"
            SHIZUKU_LIVE -> "Shizuku"
            GRANTED_APP -> "Independent access"
            LIMITED -> "Limited"
        }

    companion object {
        /** Parses a stored value, tolerating anything unrecognised. */
        fun fromStoredValue(raw: String?): AccessMode =
            entries.firstOrNull { it.name == raw } ?: NOT_CHOSEN
    }
}

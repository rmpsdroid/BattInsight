package com.rmpsdroid.battinsight.collection

import com.rmpsdroid.battinsight.access.AccessMode

/**
 * Which backend will actually be used, and why.
 *
 * Three separate facts, kept separate because conflating them is how an application starts
 * quietly doing something the user did not ask for:
 *
 *  - [preferred] is what the user chose.
 *  - [active] is what will really run, which may be nothing.
 *  - [fallbackOffer] is a working alternative that is *not* being used, so the UI can offer
 *    it as a decision rather than take it silently.
 *
 * The UI renders this. It does not compute it: a screen that worked out its own answer
 * could disagree with the one the collection layer acts on, and the user would be told
 * something false.
 */
data class BackendSelection(
    val preferred: BackendKind?,
    val active: BackendKind?,
    /** Plain-language explanation, safe to display verbatim. */
    val reason: String,
    /** A usable backend deliberately not selected. Offered, never applied. */
    val fallbackOffer: BackendKind? = null,
) {
    val hasActiveBackend: Boolean get() = active != null

    /** True when the user's choice is unavailable but something else would work. */
    val canOfferFallback: Boolean get() = active == null && fallbackOffer != null

    companion object {
        val unknown = BackendSelection(
            preferred = null,
            active = null,
            reason = "Access not yet evaluated",
        )
    }
}

/**
 * Decides which backend to use from the user's choice and what is actually working.
 *
 * Deterministic and total: every combination of mode and availability yields exactly one
 * answer with a stated reason.
 *
 * ## The rule that matters
 *
 * A privileged mode is never silently substituted for another. The two modes differ in
 * security posture — [AccessMode.GRANTED_APP] leaves BattInsight permanently holding three
 * elevated permissions, [AccessMode.SHIZUKU_LIVE] leaves it holding none — so switching
 * between them is the user's decision, not a fallback the application performs while their
 * back is turned. When the chosen mode is unavailable and the other would work, that is
 * reported as [BackendSelection.fallbackOffer] and nothing changes until the user says so.
 */
class AccessModeBackendSelector(private val mode: AccessMode) {

    fun select(
        shizuku: BackendAvailability,
        grantedApp: BackendAvailability,
    ): BackendSelection {
        val shizukuReady = shizuku is BackendAvailability.Ready
        val grantedReady = grantedApp is BackendAvailability.Ready

        return when (mode) {
            AccessMode.SHIZUKU_LIVE -> when {
                shizukuReady -> BackendSelection(
                    preferred = BackendKind.SHIZUKU_ADB,
                    active = BackendKind.SHIZUKU_ADB,
                    reason = "Using Shizuku, as you chose",
                )
                grantedReady -> BackendSelection(
                    preferred = BackendKind.SHIZUKU_ADB,
                    active = null,
                    reason = describeUnavailable(shizuku, "Shizuku"),
                    fallbackOffer = BackendKind.GRANTED_APP,
                )
                else -> BackendSelection(
                    preferred = BackendKind.SHIZUKU_ADB,
                    active = null,
                    reason = describeUnavailable(shizuku, "Shizuku"),
                )
            }

            AccessMode.GRANTED_APP -> when {
                grantedReady -> BackendSelection(
                    preferred = BackendKind.GRANTED_APP,
                    active = BackendKind.GRANTED_APP,
                    reason = "Using BattInsight's own access, as you chose",
                )
                shizukuReady -> BackendSelection(
                    preferred = BackendKind.GRANTED_APP,
                    active = null,
                    reason = describeUnavailable(grantedApp, "Independent access"),
                    fallbackOffer = BackendKind.SHIZUKU_ADB,
                )
                else -> BackendSelection(
                    preferred = BackendKind.GRANTED_APP,
                    active = null,
                    reason = describeUnavailable(grantedApp, "Independent access"),
                )
            }

            AccessMode.LIMITED -> BackendSelection(
                preferred = null,
                active = null,
                reason = "Limited mode: no privileged access is being used",
            )

            AccessMode.NOT_CHOSEN -> BackendSelection(
                preferred = null,
                active = null,
                reason = "No access method chosen yet",
            )
        }
    }

    private fun describeUnavailable(availability: BackendAvailability, label: String): String =
        when (availability) {
            is BackendAvailability.Ready -> "$label is ready"
            is BackendAvailability.NotReady -> "$label is not available: ${availability.reason}"
            is BackendAvailability.NotImplemented -> "$label is not implemented"
            is BackendAvailability.Failed -> "$label failed: ${availability.detail}"
            BackendAvailability.Unknown -> "$label has not been checked"
        }
}

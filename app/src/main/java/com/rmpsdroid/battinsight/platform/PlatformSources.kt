package com.rmpsdroid.battinsight.platform

/**
 * Whether one `BatteryManager` property carries a real measurement.
 *
 * The distinction is necessary, not pedantic: Phase 1B measured
 * `BATTERY_PROPERTY_ENERGY_COUNTER` returning `Long.MIN_VALUE` on Android 16. A reader
 * that treats every returned long as data would have recorded that as a battery reading.
 */
sealed interface PropertySupport {
    data class Supported(val value: Long) : PropertySupport
    /** The platform returned its documented "not supported" sentinel. */
    data class Sentinel(val raw: Long) : PropertySupport
    data class Unsupported(val reason: String) : PropertySupport
    data class Error(val detail: String) : PropertySupport
}

/** The battery properties worth probing individually. */
enum class BatteryProperty { CHARGE_COUNTER, CURRENT_NOW, CURRENT_AVERAGE, CAPACITY, ENERGY_COUNTER, STATUS }

/**
 * What `BatteryManager` and the sticky battery broadcast provide.
 *
 * All of this is available with **no permission at all**, which is why it is the realistic
 * non-root baseline. Phase 1A found every sysfs battery node permission-denied on the one
 * physical device tested, and `charge_full_design` absent on the emulator.
 */
data class BatteryPropertyReading(
    val properties: Map<BatteryProperty, PropertySupport>,
    /** Fields from the sticky `ACTION_BATTERY_CHANGED` intent, if it was obtainable. */
    val stickyPresent: Boolean,
    val level: Int? = null,
    val scale: Int? = null,
    val status: Int? = null,
    val health: Int? = null,
    val plugged: Int? = null,
    val technology: String? = null,
    val temperatureTenthsC: Int? = null,
    val voltageMilliVolts: Int? = null,
) {
    val supportedCount: Int get() = properties.values.count { it is PropertySupport.Supported }
    val probedCount: Int get() = properties.size

    companion object {
        val unavailable = BatteryPropertyReading(emptyMap(), stickyPresent = false)
    }
}

interface BatteryPropertySource {
    suspend fun read(): BatteryPropertyReading
}

// ---------------------------------------------------------------------------- usage stats

/**
 * Outcome of a real usage-statistics query.
 *
 * `queryUsageStats` does **not** throw when access is absent -- Phase 1B measured it
 * returning an empty list, indistinguishable from a genuinely quiet window. So the query
 * result alone can never establish access; it must be read together with permission and
 * app-op state.
 */
sealed interface UsageQueryOutcome {
    data class Rows(val count: Int) : UsageQueryOutcome
    /** The call returned normally with nothing in it. Meaning depends on access state. */
    data object Empty : UsageQueryOutcome
    data class Threw(val exception: String, val message: String?) : UsageQueryOutcome
    data object NotAttempted : UsageQueryOutcome
}

interface UsageAccessSource {
    /** Queries a short recent window. Returns row counts only -- never usage content. */
    suspend fun query(windowMillis: Long = DEFAULT_WINDOW_MS): UsageQueryOutcome

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 24L * 60 * 60 * 1000
    }
}

// -------------------------------------------------------------------- package resolution

/**
 * How completely a UID can be turned into an application name.
 *
 * Phase 1B measured an app UID holding all three permissions seeing the **same 98 UIDs** as
 * an ADB shell but only **152 of 180** name mappings, because of package-visibility
 * filtering. A Shizuku shell session matched the shell exactly. Acquisition succeeding and
 * naming succeeding are therefore different questions.
 *
 * Phase 3 only characterises this. Solving it -- targeted `<queries>` versus
 * `QUERY_ALL_PACKAGES`, and the store-policy implications -- is future work, and
 * `QUERY_ALL_PACKAGES` is deliberately not declared.
 */
data class PackageResolutionReading(
    val uidsProbed: Int,
    val uidsResolved: Int,
    val threw: String? = null,
) {
    val allResolved: Boolean get() = threw == null && uidsProbed > 0 && uidsResolved == uidsProbed
    val noneResolved: Boolean get() = threw == null && uidsProbed > 0 && uidsResolved == 0

    companion object {
        val notAttempted = PackageResolutionReading(uidsProbed = 0, uidsResolved = 0)
    }
}

interface PackageResolutionSource {
    /**
     * Attempts to resolve a small set of UIDs known to exist on every device.
     *
     * Returns counts only. Never returns or logs the installed package list.
     */
    suspend fun probe(): PackageResolutionReading
}

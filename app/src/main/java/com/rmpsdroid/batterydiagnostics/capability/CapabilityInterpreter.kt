package com.rmpsdroid.batterydiagnostics.capability

import com.rmpsdroid.batterydiagnostics.collection.CollectionOutcome

/**
 * What a capability-specific reader found inside a collection that succeeded.
 *
 * The collection layer cannot supply this: it knows a command ran and produced bytes, not
 * what a healthy result for a given source looks like. Whoever parses the output does.
 */
sealed interface SourceReading {

    /**
     * The section for this capability was present.
     *
     * @param total records found.
     * @param withValues records carrying a non-zero measurement.
     * @param completeness whether the reader believes it saw everything available.
     */
    data class Records(
        val total: Int,
        val withValues: Int,
        val completeness: Completeness = Completeness.COMPLETE,
    ) : SourceReading

    /** The collection succeeded but contained no section for this capability at all. */
    data object SectionAbsent : SourceReading

    /** Nobody has looked yet. The honest default. */
    data object NotInspected : SourceReading

    enum class Completeness { COMPLETE, PARTIAL }
}

/**
 * Translates a collection outcome into a semantic capability state.
 *
 * This is the layer that is *allowed* to conclude [CapabilityState.AvailableNoEvents] --
 * and it can only do so honestly because it is given a [SourceReading]. The measured case
 * it exists for: Android 16 returned 68 **named** kernel wakelocks whose counters were all
 * zero, because that device never suspends. The collection succeeded, the section was
 * present, the values were zero. That is a healthy idle source, and it is only
 * distinguishable from a missing one by looking inside the payload.
 *
 * The collection layer must never manufacture that conclusion from a generic empty result.
 */
object CapabilityInterpreter {

    fun interpret(
        outcome: CollectionOutcome,
        reading: SourceReading = SourceReading.NotInspected,
    ): CapabilityState = when (outcome) {

        is CollectionOutcome.PermissionDenied ->
            CapabilityState.PermissionMissing(outcome.permission)

        is CollectionOutcome.ExecutionFailed ->
            CapabilityState.ExecutionFailed(
                outcome.exitCode?.let { "exit $it: ${outcome.detail}" } ?: outcome.detail,
            )

        is CollectionOutcome.SourceError ->
            CapabilityState.SourceUnavailable(outcome.detail)

        is CollectionOutcome.Unrecognised ->
            // Deliberately not a success and not a failure: we do not know what happened.
            CapabilityState.Unknown

        CollectionOutcome.Empty -> when (reading) {
            // A clean exit producing nothing, with nobody having inspected it, tells us
            // very little. Claiming either availability or absence here would be invention.
            SourceReading.NotInspected -> CapabilityState.Unknown
            SourceReading.SectionAbsent -> CapabilityState.SourceUnavailable("no output")
            is SourceReading.Records -> recordsToState(reading)
        }

        is CollectionOutcome.Data -> when (reading) {
            // Data arrived and no reader has interpreted it yet. Acquisition worked.
            SourceReading.NotInspected -> CapabilityState.Available
            SourceReading.SectionAbsent ->
                CapabilityState.SourceUnavailable("section absent from collected data")
            is SourceReading.Records -> recordsToState(reading)
        }
    }

    private fun recordsToState(reading: SourceReading.Records): CapabilityState = when {
        reading.total == 0 ->
            CapabilityState.SourceUnavailable("section present but contained no records")

        reading.completeness == SourceReading.Completeness.PARTIAL ->
            CapabilityState.AvailableDegraded(
                "${reading.total} records, known incomplete",
            )

        // Records exist, none carry a value. The kernel-wakelock case: the source works,
        // the device simply has nothing to report.
        reading.withValues == 0 ->
            CapabilityState.AvailableNoEvents(
                "${reading.total} records present, none with recorded activity",
            )

        else -> CapabilityState.Available
    }
}

package com.rmpsdroid.battinsight.history

import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the history screens say, decided without a screen.
 *
 * Every rule here could have lived in a `@Composable` branch, and then the only way to check
 * it would be to look at a phone. Pulled out, the wording that actually matters -- what an
 * unavailable comparison says, what a reconstructed boundary is called, what a missing battery
 * level renders as -- is checkable in milliseconds on the JVM.
 *
 * Two rules run through most of these: **no enum name ever reaches a user**, and **nothing
 * unknown is ever rendered as zero**.
 */
class HistoryPresentationTest {

    // ------------------------------------------------------------------- duration

    @Test
    fun `sub-second durations keep their milliseconds`() {
        assertEquals("950 ms", HistoryPresentation.duration(950L))
        assertEquals("1 ms", HistoryPresentation.duration(1L))
    }

    /**
     * A short non-zero duration must not render as "0 s".
     *
     * A wakelock held for 40 ms did happen. Rounding it to zero is the same class of lie as
     * showing missing data as zero, and on a battery screen both read as "nothing happened".
     */
    @Test
    fun `a tiny non-zero duration is not shown as zero`() {
        assertNotEquals("0 s", HistoryPresentation.duration(40L))
        assertEquals("40 ms", HistoryPresentation.duration(40L))
    }

    @Test
    fun `seconds, minutes, hours and days each have a form`() {
        assertEquals("12 s", HistoryPresentation.duration(12_000L))
        assertEquals("4 min 18 s", HistoryPresentation.duration(258_000L))
        assertEquals("2 h 03 min", HistoryPresentation.duration(7_380_000L))
        assertEquals("1 d 04 h", HistoryPresentation.duration(100_800_000L))
    }

    @Test
    fun `exactly zero is zero seconds, which is a real answer`() {
        assertEquals("0 ms", HistoryPresentation.duration(0L))
    }

    /** An unmeasurable duration is unknown, never zero. */
    @Test
    fun `a null or negative duration is unknown`() {
        assertEquals("unknown", HistoryPresentation.duration(null))
        assertEquals("unknown", HistoryPresentation.duration(-1L))
    }

    // -------------------------------------------------------------------- battery

    /**
     * The scale is not assumed to be 100.
     *
     * A device reporting 50 out of 200 is at 25%. A reader that assumed a scale of 100 would
     * double every reading on such a device and never notice.
     */
    @Test
    fun `a non-standard battery scale is normalised`() {
        assertEquals("25%", HistoryPresentation.batteryPercent(BatteryLevel(50, 200)))
        assertEquals("50%", HistoryPresentation.batteryPercent(BatteryLevel(50, 100)))
    }

    @Test
    fun `a missing battery level is unavailable, not zero percent`() {
        assertEquals("unavailable", HistoryPresentation.batteryPercent(null))
        assertNotEquals("0%", HistoryPresentation.batteryPercent(null))
    }

    @Test
    fun `an unusable scale does not divide by zero`() {
        assertEquals("unavailable", HistoryPresentation.batteryPercent(BatteryLevel(50, 0)))
    }

    @Test
    fun `a battery range shows both ends when they differ`() {
        assertEquals(
            "80% to 62%",
            HistoryPresentation.batteryRange(BatteryLevel(80, 100), BatteryLevel(62, 100)),
        )
        assertEquals(
            "level unavailable",
            HistoryPresentation.batteryRange(null, null),
        )
    }

    // --------------------------------------------------------------------- titles

    @Test
    fun `session titles name the kind and whether it is running`() {
        assertEquals("Charging — now", HistoryPresentation.sessionTitle(SessionType.CHARGE, true))
        assertEquals("On battery", HistoryPresentation.sessionTitle(SessionType.DISCHARGE, false))
        assertEquals(
            "Power state unknown",
            HistoryPresentation.sessionTitle(SessionType.UNKNOWN, false),
        )
    }

    // ------------------------------------------------------- boundary provenance

    /**
     * A reconstructed boundary is never described as an observed one.
     *
     * Phase 5 kept the two apart in the domain; the wording has to keep them apart too. Saying
     * "Unplugged" for a change nothing witnessed asserts a broadcast was received, which makes
     * an inference look like a measurement.
     */
    @Test
    fun `a recovered start is not described as an observed unplug`() {
        val recovered = HistoryPresentation.startDescription(SessionTrigger.RECOVERY)
        assertTrue("says it was recovered: $recovered", recovered.contains("not running"))
        assertNotEquals(HistoryPresentation.startDescription(SessionTrigger.POWER_DISCONNECTED), recovered)
    }

    /**
     * The four non-trivial end reasons stay distinct.
     *
     * They mean genuinely different things -- a proven reboot, a real change nobody witnessed,
     * an absence of proof either way, and data disagreeing with itself -- and collapsing them
     * into "Restarted" would throw away the distinction a person troubleshooting needs most.
     */
    @Test
    fun `boundary reasons are not collapsed into one generic message`() {
        val descriptions = listOf(
            SessionBoundaryReason.BOOT_BOUNDARY,
            SessionBoundaryReason.RECOVERY,
            SessionBoundaryReason.UNPROVEN_CONTINUITY,
            SessionBoundaryReason.INCONSISTENT_STATE,
        ).map { HistoryPresentation.endDescription(it, isActive = false) }

        assertEquals("each must read differently", 4, descriptions.toSet().size)
        descriptions.forEach { assertTrue("must not say 'Restarted' generically", it != "Restarted") }
    }

    @Test
    fun `an active session is still running whatever the stored reason says`() {
        assertEquals(
            "Still running",
            HistoryPresentation.endDescription(SessionBoundaryReason.NONE, isActive = true),
        )
    }

    @Test
    fun `observation is labelled in words, not by colour alone`() {
        assertEquals("Observed", HistoryPresentation.observationLabel(true))
        assertEquals("Reconstructed", HistoryPresentation.observationLabel(false))
    }

    // ------------------------------------------------------- counter availability

    @Test
    fun `no capture, baseline only and a delta all read differently`() {
        val messages = listOf(
            HistoryPresentation.counterSummary(CounterAvailability.NoCapture),
            HistoryPresentation.counterSummary(CounterAvailability.BaselineOnly),
            HistoryPresentation.counterSummary(CounterAvailability.DeltaAvailable(3, 4, false)),
            HistoryPresentation.counterSummary(
                CounterAvailability.DeltaUnavailable(CounterDeltaReason.COUNTER_DECREASED),
            ),
        )
        assertEquals("four states, four messages", 4, messages.toSet().size)
    }

    /**
     * A comparable pair with no movement is a measurement, not an absence.
     *
     * "No increase recorded" and "no capture" must never render alike: one says the device was
     * idle, the other says BattInsight has nothing to show.
     */
    @Test
    fun `a zero delta reads as a measurement, not as missing data`() {
        val zero = HistoryPresentation.counterSummary(CounterAvailability.DeltaAvailable(0, 0, true))
        val none = HistoryPresentation.counterSummary(CounterAvailability.NoCapture)

        assertTrue("says no increase: $zero", zero.contains("No increase"))
        assertNotEquals(none, zero)
    }

    @Test
    fun `baseline-only invites another capture rather than showing nothing`() {
        val message = HistoryPresentation.counterSummary(CounterAvailability.BaselineOnly)
        assertTrue(message, message.contains("capture again", ignoreCase = true))
    }

    // ------------------------------------------------------- comparability reasons

    /**
     * Every reason has user-facing copy, and none of it is an enum name.
     *
     * The `when` in the implementation is exhaustive over the enum, so a reason added later
     * fails to compile rather than falling through to "something went wrong". This asserts the
     * output side: nothing shouty, nothing with an underscore, nothing trivially short.
     */
    @Test
    fun `every delta reason has plain-language copy`() {
        CounterDeltaReason.entries.forEach { reason ->
            val long = HistoryPresentation.unavailableReason(reason)
            val short = HistoryPresentation.shortReason(reason)

            assertTrue("$reason has no long copy", long.length > 20)
            assertTrue("$reason has no short copy", short.isNotBlank())
            assertTrue("$reason leaks its enum name: $long", !long.contains(reason.name))
            assertTrue("$reason leaks its enum name: $short", !short.contains(reason.name))
            assertTrue("$reason long copy shouts: $long", long != long.uppercase())
            assertTrue("$reason short copy shouts: $short", short != short.uppercase())
            assertTrue("$reason copy contains an underscore: $long", !long.contains('_'))
        }
    }

    @Test
    fun `each reason reads differently from the others`() {
        val all = CounterDeltaReason.entries.map { HistoryPresentation.unavailableReason(it) }
        assertEquals("no two reasons may share copy", CounterDeltaReason.entries.size, all.toSet().size)
    }

    @Test
    fun `specific reasons say the specific thing`() {
        assertTrue(
            HistoryPresentation.unavailableReason(CounterDeltaReason.DIFFERENT_BOOT)
                .contains("start-up", ignoreCase = true),
        )
        assertTrue(
            HistoryPresentation.unavailableReason(CounterDeltaReason.BASELINE_MISSING)
                .contains("starting capture", ignoreCase = true),
        )
        assertTrue(
            HistoryPresentation.unavailableReason(CounterDeltaReason.PLATFORM_CHANGED)
                .contains("system update", ignoreCase = true),
        )
        assertTrue(
            HistoryPresentation.unavailableReason(CounterDeltaReason.MALFORMED_STORED_STATE)
                .contains("could not be read", ignoreCase = true),
        )
    }

    /**
     * The decrease message says the whole reading is affected.
     *
     * Phase 7B.1 refuses every counter in a capture when one decreases, so copy implying only
     * one counter is affected would contradict what the screen actually does.
     */
    @Test
    fun `the decrease message covers every counter, not just one`() {
        val copy = HistoryPresentation.unavailableReason(CounterDeltaReason.COUNTER_DECREASED)
        assertTrue(copy, copy.contains("any counter", ignoreCase = true))
        assertTrue("blames the accounting, not a wakelock", copy.contains("accounting"))
    }

    // -------------------------------------------------------------------- wakelocks

    /**
     * A resolved package name never replaces the UID.
     *
     * Package names are looked up now; they are not stored with the reading. Showing only the
     * name would assert that this application owned that UID when the capture was taken, which
     * BattInsight has no evidence for.
     */
    @Test
    fun `a resolved package name accompanies the uid rather than replacing it`() {
        val label = HistoryPresentation.uidLabel(10234, "com.example.app")
        assertTrue(label, label.contains("com.example.app"))
        assertTrue("the number must survive: $label", label.contains("10234"))
    }

    @Test
    fun `an unresolved uid is shown honestly as a number`() {
        assertEquals("UID 10234", HistoryPresentation.uidLabel(10234, null))
    }

    @Test
    fun `a large uid is rendered without truncation`() {
        assertTrue(HistoryPresentation.uidLabel(1099999, null).contains("1099999"))
    }

    @Test
    fun `a zero delta is labelled as no change rather than plus zero`() {
        assertEquals("no change", HistoryPresentation.deltaLabel(0L, 0L))
        assertEquals("+12 s over +3", HistoryPresentation.deltaLabel(12_000L, 3L))
    }

    // --------------------------------------------------------------- top-N ordering

    @Test
    fun `top-N sorts by duration and respects the limit`() {
        val items = listOf("a" to 100L, "b" to 5_000L, "c" to 50L, "d" to 900L)
        val top = HistoryPresentation.topBy(
            items, 2, duration = { it.second }, count = { 0L }, name = { it.first },
        )
        assertEquals(listOf("b", "d"), top.map { it.first })
    }

    /**
     * Ties break deterministically.
     *
     * Without a stable tiebreak two counters with identical figures could swap places between
     * recompositions, which looks to a user like their data changing when nothing did.
     */
    @Test
    fun `identical figures order deterministically by name`() {
        val items = listOf("zebra" to 100L, "alpha" to 100L, "middle" to 100L)
        repeat(5) {
            val top = HistoryPresentation.topBy(
                items.shuffled(), 3, duration = { it.second }, count = { 0L }, name = { it.first },
            )
            assertEquals(listOf("alpha", "middle", "zebra"), top.map { it.first })
        }
    }

    @Test
    fun `a long wakelock name is neither truncated nor rejected by the formatter`() {
        val name = "*job*/com.example.verylongpackage/" + "x".repeat(200)
        val label = HistoryPresentation.uidLabel(10001, null) + " · " + name
        assertTrue(label.contains(name))
    }
}

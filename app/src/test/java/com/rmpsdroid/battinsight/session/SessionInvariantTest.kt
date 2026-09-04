package com.rmpsdroid.battinsight.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Properties that must hold for *every* sequence of observations, not just the ones someone
 * thought to write down.
 *
 * Scenario tests check the cases we imagined. These check the cases we did not, by driving
 * long randomised sequences of plausible device behaviour — plug changes, repeats,
 * reboots, stale events, wall-clock jumps — and asserting the invariants survive all of it.
 *
 * The generator is seeded, so a failure is reproducible rather than a story about a build
 * that once went red.
 */
class SessionInvariantTest {

    private fun run(seed: Int, steps: Int = 400, block: (Step) -> Unit) {
        val random = Random(seed)
        val engine = SessionEngine(SequentialIds())
        var state = SessionEngineState.empty
        var elapsed = 0L
        var boot = 0
        var wall = EPOCH
        var attached = false

        repeat(steps) {
            // Time usually advances; occasionally an event arrives stale and out of order.
            val stale = random.nextInt(100) < 8
            elapsed += if (stale) -random.nextLong(1, 30_000) else random.nextLong(1, 5 * MINUTE)
            if (elapsed < 0) elapsed = 0

            // The wall clock wanders independently, as a real one does.
            wall += random.nextLong(-2 * HOUR, 2 * HOUR)

            // Occasionally the device reboots: new identity, monotonic clock restarts.
            if (random.nextInt(100) < 3) {
                boot += 1
                elapsed = random.nextLong(0, 60_000)
            }

            // Occasionally the cable moves.
            if (random.nextInt(100) < 15) attached = !attached

            val status = if (attached) {
                listOf(BatteryStatus.CHARGING, BatteryStatus.FULL, BatteryStatus.NOT_CHARGING)
                    .random(random)
            } else {
                listOf(BatteryStatus.DISCHARGING, BatteryStatus.UNKNOWN).random(random)
            }

            val observation = observation(
                elapsedMillis = elapsed,
                status = status,
                plug = if (attached) PlugSource.AC else PlugSource.NONE,
                boot = kernelBoot("boot-$boot"),
                wallClockMillis = wall,
                level = random.nextInt(0, 101),
            )

            val before = state
            val transition = engine.accept(state, observation)
            state = transition.state
            block(Step(before, observation, transition))
        }
    }

    private data class Step(
        val before: SessionEngineState,
        val observation: BatteryObservation,
        val transition: SessionTransition,
    )

    @Test
    fun `session duration is never negative`() {
        (1..8).forEach { seed ->
            run(seed) { step ->
                step.transition.state.session?.let {
                    assertTrue(
                        "seed $seed produced a negative duration: ${it.elapsedMillis}",
                        it.elapsedMillis >= 0,
                    )
                }
            }
        }
    }

    @Test
    fun `accepted observations never move monotonic time backwards within a boot`() {
        (1..8).forEach { seed ->
            run(seed) { step ->
                val previous = step.before.lastAccepted ?: return@run
                val current = step.transition.state.lastAccepted ?: return@run
                if (previous.bootIdentity.relationTo(current.bootIdentity) != BootRelation.SAME) {
                    return@run
                }
                assertTrue(
                    "seed $seed rewound time on one boot: " +
                        "${current.time.elapsedRealtime.millis} after ${previous.time.elapsedRealtime.millis}",
                    current.time.elapsedRealtime >= previous.time.elapsedRealtime,
                )
            }
        }
    }

    @Test
    fun `a rejected observation never alters state`() {
        (1..8).forEach { seed ->
            run(seed) { step ->
                if (step.transition.result is TransitionResult.Rejected) {
                    assertEquals(
                        "seed $seed mutated state on a rejection",
                        step.before,
                        step.transition.state,
                    )
                }
            }
        }
    }

    @Test
    fun `a session identity changes only when the session type changes or the boot does`() {
        (1..8).forEach { seed ->
            run(seed) { step ->
                val old = step.before.session ?: return@run
                val new = step.transition.state.session ?: return@run
                if (old.id == new.id) return@run

                val bootChanged = old.latest.bootIdentity
                    .relationTo(step.observation.bootIdentity) != BootRelation.SAME
                val typeChanged = old.type != new.type
                assertTrue(
                    "seed $seed changed session identity with no boundary: " +
                        "${old.type} -> ${new.type}, boot ${old.latest.bootIdentity.abbreviated} " +
                        "-> ${step.observation.bootIdentity.abbreviated}",
                    bootChanged || typeChanged,
                )
            }
        }
    }

    @Test
    fun `the active session always contains its own start snapshot`() {
        (1..8).forEach { seed ->
            run(seed) { step ->
                step.transition.state.session?.let {
                    assertEquals(
                        "seed $seed: a snapshot must belong to the session that holds it",
                        it.id,
                        it.start.sessionId,
                    )
                    assertEquals(it.id, it.latest.sessionId)
                }
            }
        }
    }

    @Test
    fun `snapshots from different boots are never comparable, in any sequence`() {
        (1..8).forEach { seed ->
            val seen = mutableListOf<BatterySnapshot>()
            run(seed, steps = 150) { step ->
                step.transition.state.lastAccepted?.let { seen += it }
            }
            seen.forEachIndexed { i, a ->
                seen.drop(i + 1).forEach { b ->
                    if (a.bootIdentity.relationTo(b.bootIdentity) == BootRelation.DIFFERENT) {
                        assertTrue(
                            "seed $seed compared across boots",
                            SnapshotComparability.forDuration(a, b) !is Comparability.Comparable,
                        )
                        assertTrue(
                            SnapshotComparability.forCounters(a, b) !is Comparability.Comparable,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `snapshots from different counter generations are never counter-comparable`() {
        val generations = (1L..6L).map { CounterGeneration(it) }
        generations.forEach { a ->
            generations.forEach { b ->
                val earlier = snapshot(0, generation = a, source = CounterSource.PROTO)
                val later = snapshot(HOUR, generation = b, source = CounterSource.PROTO)
                val comparable = SnapshotComparability.forCounters(earlier, later).isComparable
                assertEquals(
                    "generation $a vs $b",
                    a == b,
                    comparable,
                )
            }
        }
    }

    @Test
    fun `wall-clock movement never changes a session duration`() {
        // The same monotonic sequence, run twice: once with a well-behaved wall clock and
        // once with one lurching hours in both directions. The durations must be identical.
        fun durations(chaotic: Boolean): List<Long> {
            val engine = SessionEngine(SequentialIds())
            var state = SessionEngineState.empty
            val random = Random(99)
            val out = mutableListOf<Long>()
            (1..120).forEach { i ->
                val elapsed = i * MINUTE
                val wall = if (chaotic) {
                    EPOCH + elapsed + random.nextLong(-6 * HOUR, 6 * HOUR)
                } else {
                    EPOCH + elapsed
                }
                state = engine.accept(
                    state,
                    discharging(elapsed, wallClockMillis = wall, utcOffsetMinutes = if (chaotic) i % 24 * 60 else 0),
                ).state
                state.session?.let { out += it.elapsedMillis }
            }
            return out
        }

        assertEquals(
            "a user changing clock or timezone must not alter measured duration",
            durations(chaotic = false),
            durations(chaotic = true),
        )
    }

    @Test
    fun `repeating one observation many times produces exactly one session`() {
        val engine = SessionEngine(SequentialIds())
        var state = engine.reconcile(null, discharging(0)).state
        val id = state.session!!.id
        val ids = mutableSetOf(id)

        repeat(200) { i ->
            state = engine.accept(state, discharging(1_000L * (i + 1))).state
            ids += state.session!!.id
        }
        assertEquals("duplicates must never mint identities", setOf(id), ids)
    }

    @Test
    fun `repeated process restarts alone never create a new session`() {
        val engine = SessionEngine(SequentialIds())
        var state = engine.reconcile(null, discharging(0, trigger = SessionTrigger.APP_START)).state
        val id = state.session!!.id

        repeat(50) { i ->
            // The process dies and restarts; nothing about the device changed.
            state = engine.reconcile(
                state,
                discharging((i + 1) * MINUTE, trigger = SessionTrigger.APP_START),
            ).state
        }

        assertEquals("process lifetime is not battery-session identity", id, state.session!!.id)
        assertEquals(50 * MINUTE, state.session!!.elapsedMillis)
    }

    /**
     * The invariant the Phase 5 suite was missing.
     *
     * Its wall-clock test varied the clock but pinned a `Kernel` identity, and its
     * `Derived` tests varied the estimate directly without ever moving a clock. Neither
     * combination exercised the real device path -- one boot, a clock that jumps, and an
     * estimate recomputed from that clock -- so the unsound fallback rule survived a
     * green suite.
     *
     * This drives exactly that path: a single uninterrupted boot with no kernel identifier,
     * a wall clock lurching by up to a day in both directions, and the estimate rebuilt from
     * it every time, as the adapter does. No boot boundary may ever appear.
     */
    @Test
    fun `wall-clock movement cannot manufacture a boot boundary when only the fallback exists`() {
        (1..12).forEach { seed ->
            val random = Random(seed)
            val engine = SessionEngine(SequentialIds())
            var elapsed = 0L
            var state = engine.reconcile(
                null,
                discharging(elapsed, boot = BootIdentity.Derived(EPOCH), wallClockMillis = EPOCH),
            ).state
            val sessionId = state.session!!.id
            var attached = false
            var cableEverMoved = false

            repeat(200) {
                elapsed += random.nextLong(1, 5 * MINUTE)
                // One boot throughout. The clock wanders; the estimate follows it.
                val wall = EPOCH + elapsed + random.nextLong(-24 * HOUR, 24 * HOUR)
                val estimate = wall - elapsed

                // The cable moves occasionally, which is a legitimate boundary and is
                // allowed; only a *boot* boundary is forbidden here.
                if (random.nextInt(100) < 10) {
                    attached = !attached
                    cableEverMoved = true
                }

                val transition = engine.accept(
                    state,
                    observation(
                        elapsedMillis = elapsed,
                        status = if (attached) BatteryStatus.CHARGING else BatteryStatus.DISCHARGING,
                        plug = if (attached) PlugSource.AC else PlugSource.NONE,
                        boot = BootIdentity.Derived(estimate),
                        wallClockMillis = wall,
                    ),
                )
                state = transition.state

                val result = transition.result
                if (result is TransitionResult.Boundary) {
                    assertTrue(
                        "seed $seed manufactured a boot boundary from a clock change",
                        result.reason != SessionBoundaryReason.BOOT_BOUNDARY,
                    )
                    assertTrue(
                        "seed $seed claimed a boot change with no proof",
                        result.trigger != SessionTrigger.BOOT_CHANGED,
                    )
                }
            }

            if (!cableEverMoved) {
                // The cable never moved, so nothing legitimate could have ended the
                // interval. Any change of identity would have come from the clock, which is
                // exactly what must not happen.
                //
                // This guard originally tested `!attached`, which was wrong: a cable that
                // toggled and returned leaves `attached` false while having produced real
                // power transitions, so the assertion could fail for a legitimate reason.
                // That was a defect in the test, not in the engine.
                assertEquals(
                    "seed $seed lost the session to clock movement alone",
                    sessionId,
                    state.session!!.id,
                )
            }
        }
    }

    /**
     * A fallback identity can never produce a proven relation, over a wide random spread.
     */
    @Test
    fun `no pair of fallback estimates ever produces a proven boot relation`() {
        val random = Random(4242)
        repeat(2_000) {
            val a = BootIdentity.Derived(random.nextLong(0, Long.MAX_VALUE / 2))
            val b = BootIdentity.Derived(random.nextLong(0, Long.MAX_VALUE / 2))
            assertEquals(BootRelation.UNKNOWN, a.relationTo(b))
            assertEquals(BootRelation.UNKNOWN, b.relationTo(a))
        }
    }

    @Test
    fun `a counter generation only ever moves forward`() {
        (1..8).forEach { seed ->
            var previous = CounterGeneration.INITIAL
            run(seed) { step ->
                val current = step.transition.state.counterGeneration
                assertTrue(
                    "seed $seed moved the generation backwards: $previous -> $current",
                    current >= previous,
                )
                previous = current
            }
        }
    }
}

package com.rmpsdroid.battinsight.session

/**
 * Milliseconds since boot, from a monotonic source.
 *
 * A separate type because the whole engine depends on never confusing this with wall
 * clock. The predecessor recorded only wall clock and had the monotonic alternative
 * commented out, which is why its statistics reset at a fixed time of day: a user in a
 * different timezone, or one whose clock synchronised, saw their session silently move.
 *
 * Monotonic **within a boot only**. Comparing across boots is meaningless, and
 * [BootIdentity] exists to make that comparison refusable rather than merely wrong.
 */
@JvmInline
value class ElapsedRealtime(val millis: Long) : Comparable<ElapsedRealtime> {

    override fun compareTo(other: ElapsedRealtime): Int = millis.compareTo(other.millis)

    /**
     * Duration between two readings from the same boot.
     *
     * The caller is responsible for having established that they *are* from the same boot;
     * [SnapshotComparability] is what establishes it.
     */
    operator fun minus(earlier: ElapsedRealtime): Long = millis - earlier.millis

    override fun toString(): String = "+${millis}ms"
}

/**
 * When an observation happened, on both clocks at once.
 *
 * Both are recorded because they answer different questions and neither can answer the
 * other's. [elapsedRealtime] orders events and measures durations; [wallClockMillis] is
 * what the user's device said the time was, which is what an export or a timestamp on
 * screen has to show.
 *
 * [utcOffsetMinutes] is stored rather than derived later because the offset at capture time
 * is not recoverable afterwards: a device that moves timezone, or crosses a DST boundary,
 * cannot reconstruct what its own clock read at a past moment. An export that says
 * "14:05" without the offset it was captured under is ambiguous, and the predecessor's
 * `Period: n/a` bug came from exactly this class of missing field.
 *
 * Nothing in this class computes a duration from wall clock. That is the point.
 */
data class CaptureTime(
    val elapsedRealtime: ElapsedRealtime,
    /** Epoch milliseconds as the device's clock read them at capture. Display and export only. */
    val wallClockMillis: Long,
    /** Minutes east of UTC at capture, including any DST offset in force at that moment. */
    val utcOffsetMinutes: Int,
) {
    /**
     * Approximately when this boot began, on the wall clock.
     *
     * Deliberately named *approximate*: it is a subtraction of two clocks that drift
     * relative to each other, so it is a weak signal. It is never used to claim two
     * observations share a boot — only, with a generous tolerance, to establish that they
     * cannot. See [BootIdentity.Derived].
     */
    val approximateBootWallClockMillis: Long
        get() = wallClockMillis - elapsedRealtime.millis

    companion object {
        /** For tests and for callers that already hold the three values. */
        fun of(elapsedRealtimeMillis: Long, wallClockMillis: Long, utcOffsetMinutes: Int = 0) =
            CaptureTime(ElapsedRealtime(elapsedRealtimeMillis), wallClockMillis, utcOffsetMinutes)
    }
}

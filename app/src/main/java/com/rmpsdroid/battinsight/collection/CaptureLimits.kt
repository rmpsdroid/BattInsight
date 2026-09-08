package com.rmpsdroid.battinsight.collection

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Bytes read, plus whether the ceiling stopped us before the stream ended. */
class BoundedCapture(val bytes: ByteArray, val truncated: Boolean)

/** How much of a stream was moved, and whether the ceiling stopped it early. */
class CopyReport(val byteCount: Long, val truncated: Boolean)

/**
 * The one place a capture ceiling is defined, for every backend.
 *
 * ## Why this exists as a shared object
 *
 * Until Phase 10A.3 there were **two** independently declared `MAX_CAPTURE_BYTES`
 * constants, one in the Shizuku service and one in the granted-app runner, both 1 MiB and
 * neither aware of the other. That is how the two backends came to disagree about the same
 * device: at 1,066,676 measured bytes the granted-app path silently returned a truncated
 * prefix while the Shizuku path failed outright. A completeness policy that differs by
 * backend is not a policy.
 *
 * ## Why the ceiling is what it is
 *
 * The ceiling is a **memory** bound, not a transport bound. Since Phase 10A.3 no payload
 * crosses a Binder by value, so nothing here is chosen to fit a transaction budget --
 * choosing a number for that reason is precisely the defect that was fixed, and any number
 * picked that way becomes wrong the moment a device's statistics grow.
 *
 * What genuinely bounds the payload is that [com.rmpsdroid.battinsight.batterystats.BatteryStatsDecoder]
 * takes a `ByteArray`, so one complete capture is materialised once in the application
 * process. Accumulating it costs more than its own size transiently: [ByteArrayOutputStream]
 * grows by doubling and `toByteArray` copies, so peak cost is roughly **three times** the
 * final payload. At this ceiling that is about 48 MiB of transient heap -- survivable on the
 * devices this application supports, and not something to be casual about.
 *
 * 16 MiB is about **thirteen times** the largest payload measured on real hardware
 * (1,256,660 bytes on a Samsung SM-M156B, Android 15). No capture measured so far comes near
 * it, and reaching it is reported as truncated rather than decoded as if complete.
 *
 * What reaching it does **not** establish is anything about the producer. An earlier version
 * of this note called such a stream "a runaway or hostile producer"; no measurement supports
 * that, and the withdrawn wording is recorded here rather than quietly deleted because
 * inferring a fault from a number is the habit this file exists to break. The payloads
 * measured on one healthy device across one boot went 852,557 -> 1,066,676 -> 1,247,842 ->
 * 1,256,660 with nothing wrong anywhere in that sequence. If that growth ever reaches this
 * ceiling it means the ceiling wants revisiting, not that the device is misbehaving -- so
 * this is a limit on what BattInsight will hold at once, and it says only that.
 */
object CaptureLimits {

    /** The completeness ceiling, identical for every backend. See the class note. */
    const val MAX_CAPTURE_BYTES: Int = 16 * 1024 * 1024

    /**
     * Ceiling for short diagnostic output that is still carried by value.
     *
     * Applies to setup actions, whose entire output is a line or two from `pm`. Small enough
     * that a Binder reply carrying it can never approach a transaction budget.
     */
    const val MAX_MESSAGE_BYTES: Int = 64 * 1024

    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val INITIAL_SINK_BYTES = 256 * 1024

    /**
     * Reads a stream to a ceiling, closing it, and reports whether it was cut short.
     *
     * Truncation is reported rather than swallowed: a payload cut off before the evidence a
     * probe was looking for is otherwise indistinguishable from one that genuinely lacked
     * it. Phase 3.1 found exactly that defect, where a 512 KB prefix missed the kernel
     * wakelock block entirely because it sits at 84-88% of the payload.
     */
    fun readBoundedAndClose(
        input: InputStream,
        limit: Int = MAX_CAPTURE_BYTES,
    ): BoundedCapture = input.use { source ->
        val sink = ByteArrayOutputStream(minOf(limit, INITIAL_SINK_BYTES))
        val report = copyBounded(source, sink, limit)
        BoundedCapture(sink.toByteArray(), report.truncated)
    }

    /**
     * Moves bytes from one stream to another up to a ceiling, without holding them.
     *
     * Neither stream is closed: the producer side needs to close its pipe explicitly to
     * signal end of stream, and doing that here would take the decision away from the code
     * that knows when the stream is really finished.
     *
     * Peak memory is one [COPY_BUFFER_BYTES] buffer regardless of payload size, which is what
     * lets the privileged process forward an arbitrarily large capture without ever holding
     * it. The application still materialises the result once for the decoder; see the class
     * note, which says so rather than claiming a constant-memory pipeline that does not exist.
     */
    fun copyBounded(from: InputStream, to: OutputStream, limit: Int = MAX_CAPTURE_BYTES): CopyReport {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            if (total >= limit) {
                // Something remains unread, so what was moved is a prefix.
                return CopyReport(total, from.read() != -1)
            }
            val room = minOf(buffer.size.toLong(), limit - total).toInt()
            val read = from.read(buffer, 0, room)
            if (read <= 0) return CopyReport(total, false)
            to.write(buffer, 0, read)
            total += read
        }
    }
}

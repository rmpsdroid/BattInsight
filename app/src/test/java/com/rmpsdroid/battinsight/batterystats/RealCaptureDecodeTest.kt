package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The decoder against real captures from real devices.
 *
 * This is the case that matters. Synthetic fixtures prove the parser does what its author
 * expected; only a genuine 800 KB capture from a Samsung on Android 10 and an emulator on
 * Android 16 proves it does what the *platform* does.
 *
 * The captures are the Phase 1A corpus and they stay outside the repository -- they are
 * hundreds of kilobytes of the maintainer's own device state, including a full package list.
 * These cases skip unless the archive is pointed at:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest -Dbattinsight.fixtures=/path/to/PHASE1A_CAPABILITY_DISCOVERY
 * ```
 *
 * CI has no archive, so CI skips them. The sanitized in-repository fixtures in
 * `CheckinDecoderTest` are what keeps the parser honest there.
 */
class RealCaptureDecodeTest {

    // --------------------------------------------------------------- Android 16 emulator

    @Test
    fun `android 16 capture decodes with the measured version block`() {
        val payload = fixture(A16) ?: return skip()

        val result = CheckinDecoder().decode(payload, metadataFor(payload))

        val capture = assertSuccess(result)
        // Measured in Phase 1A: 9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1
        assertEquals(9, capture.version.recordFormatVersion)
        assertEquals(36, capture.version.checkinVersion)
        assertEquals(215L, capture.version.parcelVersion)
        assertEquals("BE2A.250530.026.D1", capture.version.startPlatformVersion)
        assertTrue(
            "one build fingerprint means no OS update inside the window",
            !capture.version.spansPlatformChange,
        )
    }

    /**
     * The Android 16 emulator enumerates kernel wakelocks and every one of them is zero.
     *
     * That is the correct answer for a device that never truly suspends, and it is precisely
     * the case a careless decoder gets wrong in both directions: reporting "no kernel
     * wakelocks" (they are there, named) or reporting activity (there is none).
     */
    @Test
    fun `android 16 reports named kernel wakelocks that are all idle`() {
        val payload = fixture(A16) ?: return skip()

        val capture = assertSuccess(CheckinDecoder().decode(payload, metadataFor(payload)))

        assertEquals("Phase 1A measured 68 kwl records", 68, capture.kernelWakelockCount)
        assertEquals(
            "all zero on an emulator that never suspends",
            0,
            capture.activeKernelWakelocks.size,
        )
        assertTrue(
            "the empty-named record must survive rather than be dropped",
            capture.kernelWakelocks.any { it.name.isEmpty() },
        )
        assertTrue(capture.kernelWakelocks.any { it.name == "IdleMaint" })
    }

    @Test
    fun `android 16 decodes partial wakelocks and uid mappings`() {
        val payload = fixture(A16) ?: return skip()

        val capture = assertSuccess(CheckinDecoder().decode(payload, metadataFor(payload)))

        assertEquals("Phase 1A measured 315 wl records", 315, capture.partialWakelockCount)
        assertTrue("uid mappings must be present", capture.uidPackages.isNotEmpty())
        // Shared system UID: many packages map onto 1000, and all of them must survive.
        val system = capture.uidPackages.filter { it.uid == 1000 }
        assertTrue("uid 1000 hosts many packages, was ${system.size}", system.size > 5)
        assertTrue(capture.uidPackages.any { it.packageName == "com.android.settings" })
    }

    // -------------------------------------------------------------- Android 10 hardware

    /**
     * Android 10 is parser evidence, not a supported runtime.
     *
     * The application floor is API 33. This capture exists because it is the only measured
     * evidence of a *populated* kernel wakelock block and of quoted fields containing commas,
     * and a parser that only ever sees idle emulator output is a parser with no coverage of
     * the interesting cases.
     */
    @Test
    fun `android 10 capture decodes with a different version block`() {
        val payload = fixture(A10) ?: return skip()

        val capture = assertSuccess(CheckinDecoder().decode(payload, metadataFor(payload)))

        // 9,0,i,vers,34,1310906,QP1A.190711.020,QP1A.190711.020
        assertEquals("record format is stable across six Android releases", 9, capture.version.recordFormatVersion)
        assertEquals(34, capture.version.checkinVersion)
        assertEquals(1310906L, capture.version.parcelVersion)
    }

    /**
     * The parcel version went *down* between Android 10 and 16.
     *
     * 1310906 to 215. Any parser that gates on magnitude, or assumes monotonicity, is already
     * wrong. This test exists to keep that fact in front of whoever next edits version
     * handling.
     */
    @Test
    fun `the parcel version is not monotonic across platforms`() {
        val a10 = fixture(A10) ?: return skip()
        val a16 = fixture(A16) ?: return skip()

        val older = assertSuccess(CheckinDecoder().decode(a10, metadataFor(a10))).version
        val newer = assertSuccess(CheckinDecoder().decode(a16, metadataFor(a16))).version

        assertTrue("Android 16 is the newer platform", newer.checkinVersion > older.checkinVersion)
        assertTrue(
            "yet its parcel version is far smaller: ${newer.parcelVersion} < ${older.parcelVersion}",
            newer.parcelVersion < older.parcelVersion,
        )
    }

    @Test
    fun `android 10 reports kernel wakelocks that actually accumulated time`() {
        val payload = fixture(A10) ?: return skip()

        val capture = assertSuccess(CheckinDecoder().decode(payload, metadataFor(payload)))

        assertEquals("Phase 1A measured 111 kwl records", 111, capture.kernelWakelockCount)
        assertEquals(
            "Phase 1A measured 43 of them non-zero",
            43,
            capture.activeKernelWakelocks.size,
        )
        val bt = capture.kernelWakelocks.first { it.name == "bt_read_wake_lock" }
        assertEquals(681038L, bt.totalTimeMillis)
        assertEquals(678L, bt.count)
    }

    /**
     * The quoted-comma case, on real data.
     *
     * The Android 10 capture contains
     * `9,0,l,wr,"Abort:Some devices failed to suspend, or early wake event detected",0,0`.
     * A naive `split(",")` shifts every field after it. `wr` is not decoded, but the line
     * still has to be *split* correctly, and getting it wrong would corrupt the record count
     * and could shift a neighbouring record's numbers.
     */
    @Test
    fun `a quoted field containing a comma is one field`() {
        val payload = fixture(A10) ?: return skip()
        val line = payload.toString(Charsets.UTF_8).lineSequence()
            .firstOrNull { it.contains(",wr,\"Abort:Some devices failed to suspend") }
        assumeTrue("this capture has no comma-bearing wakeup reason", line != null)

        val fields = CheckinDecoder.splitCheckinLine(line!!)

        assertEquals("wr", fields[3])
        assertTrue(
            "the reason must survive as one field: ${fields[4]}",
            fields[4].contains("failed to suspend, or early wake"),
        )
        assertEquals("and the numbers after it must not shift", "0", fields[5].trim())
    }

    // ------------------------------------------------------------------- cross-cutting

    /**
     * A real capture is only partly understood, and says so.
     *
     * Phase 7A decodes four record types out of the twenty-five Android 16 emits. That is a
     * deliberate scope, and the honest expression of it is a populated `unsupportedTags` map
     * rather than silence.
     */
    @Test
    fun `undecoded record types are counted, not ignored`() {
        val payload = fixture(A16) ?: return skip()

        val capture = assertSuccess(CheckinDecoder().decode(payload, metadataFor(payload)))

        // Android 16 emits 46 distinct aggregate record types; this phase decodes 4.
        assertEquals("distinct undecoded record types", 42, capture.unsupportedTags.size)
        assertTrue("including per-process records", capture.unsupportedTags.containsKey("pr"))
        assertTrue(
            "and the fact is surfaced as a warning",
            capture.warnings.any { it.kind == DecodeWarning.Kind.UNSUPPORTED_TAG },
        )
        // Decoded tags must never appear as unsupported.
        listOf("vers", "uid", "kwl", "wl").forEach {
            assertTrue("$it is decoded", !capture.unsupportedTags.containsKey(it))
        }
    }

    /**
     * String-pool lines are history, not aggregate records.
     *
     * `9,hsp,<index>,<uid>,"<string>"` puts a **UID** exactly where an aggregate record puts
     * its tag. Treating it as a record turned every distinct UID in the pool into a fictional
     * record type -- 124 of them on this capture against 42 real ones, which made the
     * "undecoded record types" figure meaningless and put it on screen.
     *
     * This is the same defect class as the `h` lines, found later, in the same place.
     */
    @Test
    fun `history string-pool lines do not become fictional record types`() {
        val payload = fixture(A16_PRODUCTION) ?: return skip()

        val capture = assertSuccess(CheckinDecoder().decode(payload, metadataFor(payload)))

        assertEquals("the real number of undecoded record types", 42, capture.unsupportedTags.size)
        // Every key must be a tag name, never a bare number lifted out of a string-pool line.
        val numeric = capture.unsupportedTags.keys.filter { it.toLongOrNull() != null }
        assertEquals("no UID may appear as a record type", emptyList<String>(), numeric)
        assertTrue("and the pool is counted as history", capture.historyLineCount > 38_000)
    }

    /**
     * The same bytes decode identically regardless of which backend is claimed.
     *
     * The decoder must be blind to the privilege mechanism. Backend choice is Phase 4's
     * concern and reaches the decoder only as metadata for diagnostics.
     */
    @Test
    fun `the backend recorded in metadata does not change the decoded model`() {
        val payload = fixture(A16) ?: return skip()

        val viaShell = CheckinDecoder().decode(
            payload, metadataFor(payload, BackendIdentity.Kind.SHELL),
        ).captureOrNull!!
        val viaApp = CheckinDecoder().decode(
            payload, metadataFor(payload, BackendIdentity.Kind.APP_UID),
        ).captureOrNull!!

        assertEquals(viaShell.kernelWakelocks, viaApp.kernelWakelocks)
        assertEquals(viaShell.partialWakelocks, viaApp.partialWakelocks)
        assertEquals(viaShell.uidPackages, viaApp.uidPackages)
        assertEquals(viaShell.version, viaApp.version)
    }

    /**
     * The 512 KB defect, pinned against a real capture.
     *
     * Phase 3.1 found that reading only the first 512 KB missed kernel wakelocks entirely,
     * because the `kwl` block sits at 84-88% of the payload. Feeding the decoder that prefix
     * must produce TRUNCATED -- never a successful capture reporting zero kernel wakelocks.
     */
    @Test
    fun `a prefix of a real capture is truncated, never a capture with no wakelocks`() {
        val payload = fixture(A10_PRODUCTION) ?: return skip()
        assumeTrue("this capture is smaller than the old prefix limit", payload.size > PREFIX)
        val prefix = payload.copyOf(PREFIX)

        val result = CheckinDecoder().decode(
            prefix, metadataFor(prefix).copy(truncated = true),
        )

        assertEquals(DecodeOutcome.TRUNCATED, result.outcome)
        assertTrue(
            "and it must not present as a successful decode",
            result.captureOrNull == null,
        )
    }

    // ------------------------------------------- the production command, not just --checkin

    /**
     * The format production actually captures, which is a mixture of two formats.
     *
     * `-c` interleaves aggregate records with battery history: `9,h,<elapsed>,<events...>`,
     * where the field that holds a record tag in an aggregate line holds event data instead.
     * A decoder that dispatches on that field without recognising history first invents an
     * "unsupported record type" for every `+r`, `-w` and `Bl=100` in the payload -- 51,639 of
     * them here, which would bury the genuine ones.
     *
     * This test exists because the first version of this decoder did exactly that, and only
     * testing the production format revealed it.
     */
    @Test
    fun `the production -c capture decodes, and history is counted rather than mistaken for records`() {
        val payload = fixture(A16_PRODUCTION) ?: return skip()

        val capture = assertSuccess(CheckinDecoder().decode(payload, metadataFor(payload)))

        // 38,689 event lines plus 232 string-pool lines. Both are the history block.
        assertEquals("history events plus string pool", 38_921, capture.historyLineCount)
        assertEquals(36, capture.version.checkinVersion)
        assertEquals("aggregate records survive the mixture", 68, capture.kernelWakelockCount)
        assertEquals(315, capture.partialWakelockCount)

        // The proof that history was not mistaken for records: event fragments must not
        // appear as record tags.
        listOf("+r", "-r", "+w", "-w", "Bl=100", "+ca").forEach { fragment ->
            assertTrue(
                "history fragment '$fragment' must not be reported as a record type",
                !capture.unsupportedTags.containsKey(fragment),
            )
        }
    }

    @Test
    fun `the android 10 production capture decodes too`() {
        val payload = fixture(A10_PRODUCTION) ?: return skip()

        val capture = assertSuccess(CheckinDecoder().decode(payload, metadataFor(payload)))

        // 51,639 event lines plus 169 string-pool lines.
        assertEquals("history events plus string pool", 51_808, capture.historyLineCount)
        assertEquals(34, capture.version.checkinVersion)
        assertEquals(108, capture.kernelWakelockCount)
        assertEquals(231, capture.partialWakelockCount)
        assertTrue("a real device accumulates kernel wakelock time", capture.activeKernelWakelocks.isNotEmpty())
    }

    /**
     * Partial wakelock durations must come from the partial block, not the full block.
     *
     * The `wl` layout puts the marker letter *second* in each six-field group, so a parser
     * that expects it first reads the previous group's total as this group's marker. On real
     * data that produces plausible-looking numbers taken from the wrong column, which is the
     * worst kind of wrong.
     */
    @Test
    fun `partial wakelock values are not the full-wakelock values`() {
        val payload = fixture(A10_PRODUCTION) ?: return skip()

        // Any wakelock whose full and partial totals differ will do. Pinning a specific name
        // made this skip on a capture that happened not to contain it, and a test that skips
        // is a test that proves nothing.
        val line = payload.toString(Charsets.UTF_8).lineSequence().firstOrNull { candidate ->
            val f = CheckinDecoder.splitCheckinLine(candidate)
            f.size >= 16 && f.getOrNull(3) == "wl" &&
                f[6].trim() == "f" && f[12].trim() == "p" &&
                f[5].trim().toLongOrNull()?.let { it > 0L } == true &&
                f[5].trim() != f[11].trim()
        }
        assumeTrue("this capture has no wakelock with differing full and partial totals", line != null)

        val fields = CheckinDecoder.splitCheckinLine(line!!)
        val fullTotal = fields[5].trim().toLong()
        val partialTotal = fields[11].trim().toLong()
        val uid = fields[1].trim().toInt()
        val name = fields[4]

        val capture = assertSuccess(CheckinDecoder().decode(payload, metadataFor(payload)))
        val decoded = capture.partialWakelocks.first { it.uid == uid && it.name == name }

        assertEquals("the partial figure is what is stored", partialTotal, decoded.totalTimeMillis)
        assertNotEquals(
            "and it is not the full figure, which sits three fields earlier",
            fullTotal,
            decoded.totalTimeMillis,
        )
    }

    // ------------------------------------------------------------------------ helpers

    private fun assertSuccess(result: DecodeResult): BatteryStatsCapture {
        assertTrue("expected success, was ${result.outcome}: $result", result.succeeded)
        return (result as DecodeResult.Success).capture
    }

    private fun metadataFor(
        payload: ByteArray,
        backend: BackendIdentity.Kind = BackendIdentity.Kind.SHELL,
    ) = CaptureMetadata(
        sourceFormat = SourceFormat.CHECKIN,
        sourceFormatVersion = null,
        captureElapsedRealtimeMillis = 1_000L,
        captureWallClockMillis = 1_700_000_000_000L,
        backendKind = backend,
        platformVersion = null,
        payloadByteCount = payload.size,
        payloadHash = null,
        truncated = false,
    )

    private fun fixture(relativePath: String): ByteArray? {
        val root = fixtureRoot ?: return null
        val file = File(root, relativePath)
        assumeTrue("fixture archive is present but $relativePath is missing", file.isFile)
        return file.readBytes()
    }

    private fun skip() = assumeTrue("no fixture archive configured; set battinsight.fixtures", false)

    private companion object {
        const val A16 = "fixtures/emu-pixel8-a16/shell/bs_checkin.stdout"
        const val A10 = "fixtures/dev-samsung-m305f-a10/shell/bs_checkin.stdout"

        /**
         * The captures from `-c`, which is what production actually runs.
         *
         * Distinct from the `--checkin` captures above, and not interchangeable with them:
         * `-c` returns the aggregate block *and* the battery history in one call, so these
         * are three to four times larger and contain tens of thousands of history lines.
         * Decoding is only proved by the format the product uses.
         */
        const val A16_PRODUCTION = "fixtures/emu-pixel8-a16/shell/bs_c_lowercase.stdout"
        const val A10_PRODUCTION = "fixtures/dev-samsung-m305f-a10/shell/bs_c_lowercase.stdout"

        /** The prefix Phase 3.1 used to read, kept so the regression stays pinned. */
        const val PREFIX = 512 * 1024

        val fixtureRoot: File? = (
            System.getProperty("battinsight.fixtures")
                ?: System.getenv("BATTINSIGHT_FIXTURES")
            )?.let { File(it) }?.takeIf { it.isDirectory }
    }
}

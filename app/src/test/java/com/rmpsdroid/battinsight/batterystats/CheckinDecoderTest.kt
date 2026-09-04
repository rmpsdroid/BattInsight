package com.rmpsdroid.battinsight.batterystats

import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder against records built here, line by line.
 *
 * These are synthetic on purpose. Real captures prove the parser matches the platform;
 * synthetic records prove it behaves correctly on the cases a real capture happens not to
 * contain -- a malformed number, a duplicate, a value that overflows 64 bits, a name in
 * Cyrillic. Waiting for a device to produce those is not a test strategy.
 *
 * Nothing here is a real device's data, so nothing here carries privacy weight and it can
 * live in the repository. The genuine captures stay outside it; see [RealCaptureDecodeTest].
 *
 * Pure JVM: no Android, no Robolectric. The decoder takes bytes and metadata and returns a
 * value, which is the whole point of keeping it free of the platform.
 */
class CheckinDecoderTest {

    private val decoder = CheckinDecoder()

    // --------------------------------------------------------------- format and version

    @Test
    fun `a minimal well-formed capture decodes`() {
        val result = decode(VERS_A16, KWL_ACTIVE, WL_ACTIVE, UID_MAPPING)

        val capture = success(result)
        assertEquals(36, capture.version.checkinVersion)
        assertEquals(1, capture.kernelWakelockCount)
        assertEquals(1, capture.partialWakelockCount)
        assertEquals(1, capture.uidPackages.size)
        assertEquals(0, capture.historyLineCount)
    }

    @Test
    fun `a proto payload is refused by the checkin decoder rather than misread`() {
        val result = decoder.decode(
            byteArrayOf(0x0a, 0x7f, 0x08, 0x24),
            metadata(4).copy(sourceFormat = SourceFormat.PROTO),
        )
        assertEquals(DecodeOutcome.UNSUPPORTED_FORMAT, result.outcome)
    }

    @Test
    fun `an unverified checkin version still decodes, but says so`() {
        val capture = success(decode("9,0,i,vers,99,7,BUILD.A,BUILD.A", KWL_ACTIVE))

        assertEquals(99, capture.version.checkinVersion)
        assertTrue(
            "an unmeasured version must be flagged, not silently trusted",
            capture.warnings.any { it.kind == DecodeWarning.Kind.UNVERIFIED_VERSION },
        )
        assertEquals("yet the records still decode", 1, capture.kernelWakelockCount)
    }

    @Test
    fun `a window spanning an OS update is flagged as not comparable`() {
        val capture = success(decode("9,0,i,vers,36,215,BUILD.OLD,BUILD.NEW", KWL_ACTIVE))

        assertTrue(capture.version.spansPlatformChange)
        assertTrue(
            capture.warnings.any {
                it.kind == DecodeWarning.Kind.UNVERIFIED_VERSION && it.detail.contains("OS update")
            },
        )
    }

    /**
     * Counters without a version block are refused.
     *
     * The version gates every number in the payload. Decoding the records anyway would
     * produce a capture that looks complete and whose field meanings are guesses.
     */
    @Test
    fun `a capture with no version record is incomplete, not successful`() {
        val result = decode(KWL_ACTIVE, WL_ACTIVE)
        assertEquals(DecodeOutcome.INCOMPLETE, result.outcome)
    }

    @Test
    fun `platform version and format version are not confused`() {
        val capture = success(decode(VERS_A16, KWL_ACTIVE))
        // The record-format version is 9; the checkin version is 36; the platform string is
        // a build fingerprint. Three different domains, none derived from another.
        assertEquals(9, capture.version.recordFormatVersion)
        assertEquals(36, capture.version.checkinVersion)
        assertEquals("BE2A.250530.026.D1", capture.version.startPlatformVersion)
        assertEquals(9, capture.metadata.sourceFormatVersion)
    }

    // ------------------------------------------------------------------------- denial

    /**
     * A denial must never parse as an empty capture.
     *
     * `dumpsys` was measured returning **exit status 0** with the denial on stdout, so exit
     * code proves nothing. If this decoded to "success, zero wakelocks", the application
     * would tell the user their device has no wakelocks -- a false statement they have no way
     * to challenge.
     */
    @Test
    fun `a permission denial payload is its own outcome`() {
        listOf(
            "Permission Denial: can't dump BatteryStats from pid=1234, uid=10234",
            "java.lang.SecurityException: caller does not have android.permission.DUMP",
            "SecurityException: MATCH_ANY_USER requires android.permission.INTERACT_ACROSS_USERS",
            "Error: missing android.permission.PACKAGE_USAGE_STATS",
        ).forEach { denial ->
            val result = decoder.decode(denial.toByteArray(), metadata(denial.length))
            assertEquals(
                "\"$denial\" must be a denial",
                DecodeOutcome.PERMISSION_DENIAL_PAYLOAD,
                result.outcome,
            )
            assertNull(result.captureOrNull)
        }
    }

    /**
     * A denial wins even when statistics follow it.
     *
     * A partial capture behind a denial is not a capture; treating the records as usable
     * would report numbers from an accounting window we were not permitted to read.
     */
    @Test
    fun `a denial ahead of records still reports as a denial`() {
        val result = decode("Permission Denial: can't dump BatteryStats", VERS_A16, KWL_ACTIVE)
        assertEquals(DecodeOutcome.PERMISSION_DENIAL_PAYLOAD, result.outcome)
    }

    /**
     * A wakelock that happens to be named like a denial is not a denial.
     *
     * Denial text arrives at the start of a short payload. Scanning the whole of an 800 KB
     * capture for those words would let any application name a wakelock "SecurityException"
     * and make the user's whole capture unreadable.
     */
    @Test
    fun `a denial-shaped name deep inside a real capture does not trigger denial detection`() {
        val filler = (1..400).joinToString("\n") { "9,0,l,kwl,\"filler_$it\",0,0,-1,-1" }
        val result = decode(VERS_A16, filler, "9,0,l,kwl,\"SecurityException\",5,1,-1,-1")

        val capture = success(result)
        assertTrue(capture.kernelWakelocks.any { it.name == "SecurityException" })
    }

    // ------------------------------------------------------------- empty and malformed

    @Test
    fun `an empty payload is empty, not malformed`() {
        assertEquals(DecodeOutcome.EMPTY, decoder.decode(ByteArray(0), metadata(0)).outcome)
    }

    @Test
    fun `a payload with no records at all is malformed`() {
        val junk = "this is not checkin output\nnor is this\n"
        assertEquals(DecodeOutcome.MALFORMED, decoder.decode(junk.toByteArray(), metadata(junk.length)).outcome)
    }

    // ---------------------------------------------------------------------- truncation

    /**
     * The Phase 3.1 defect, in miniature.
     *
     * Reading a 512 KB prefix missed kernel wakelocks entirely because `kwl` sits at 84-88%
     * of the payload. The rule that prevents it recurring: a payload the caller marked
     * truncated can never come back as a success, however well the part we saw parsed.
     */
    @Test
    fun `a truncated payload is never a successful capture`() {
        val bytes = listOf(VERS_A16, KWL_ACTIVE).joinToString("\n").toByteArray()
        val result = decoder.decode(bytes, metadata(bytes.size).copy(truncated = true))

        assertEquals(DecodeOutcome.TRUNCATED, result.outcome)
        assertNull("a prefix must not present as a capture", result.captureOrNull)
    }

    @Test
    fun `a truncated payload reports what it managed to see`() {
        val bytes = listOf(VERS_A16, KWL_ACTIVE).joinToString("\n").toByteArray()
        val result = decoder.decode(bytes, metadata(bytes.size).copy(truncated = true))

        val failure = result as DecodeResult.Failure
        assertTrue(
            "the detail must distinguish 'missing' from 'absent': ${failure.detail}",
            failure.detail.contains("missing, not absent"),
        )
    }

    // ------------------------------------------------------------- kernel wakelocks

    @Test
    fun `kernel wakelock time and count decode with their units`() {
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"bt_read_wake_lock\",681038,678,-1,-1"))

        val kwl = capture.kernelWakelocks.single()
        assertEquals("bt_read_wake_lock", kwl.name)
        assertEquals("milliseconds, per AOSP's checkin rounding", 681_038L, kwl.totalTimeMillis)
        assertEquals(678L, kwl.count)
        assertEquals(AggregationWindow.SINCE_CHARGED, kwl.window)
        assertTrue(kwl.hasActivity)
    }

    /**
     * A zero record is a measurement, not an absence.
     *
     * The Android 16 emulator enumerates 68 kernel wakelocks and every one is zero, because
     * it never truly suspends. "Present and idle" and "not reported" are different facts and
     * the model keeps them apart.
     */
    @Test
    fun `a zero kernel wakelock is present and idle, not missing`() {
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"ac\",0,0,-1,-1"))

        assertEquals("the record exists", 1, capture.kernelWakelockCount)
        assertEquals("but nothing accumulated", 0, capture.activeKernelWakelocks.size)
        assertTrue(!capture.kernelWakelocks.single().hasActivity)
    }

    @Test
    fun `a capture with no kwl records at all reports none`() {
        val capture = success(decode(VERS_A16, WL_ACTIVE))
        assertEquals(0, capture.kernelWakelockCount)
    }

    @Test
    fun `an empty kernel wakelock name is kept`() {
        // Android 16 emits exactly one such record. Dropping it undercounts the device.
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"\",0,0,-1,-1"))
        assertEquals(1, capture.kernelWakelockCount)
        assertEquals("", capture.kernelWakelocks.single().name)
    }

    @Test
    fun `a kernel wakelock name containing a comma survives quoting`() {
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"wake,lock,with,commas\",7,2,-1,-1"))

        assertEquals("wake,lock,with,commas", capture.kernelWakelocks.single().name)
        assertEquals("and the numbers after it must not shift", 7L, capture.kernelWakelocks.single().totalTimeMillis)
    }

    @Test
    fun `a non-ascii wakelock name decodes as written`() {
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"будильник-唤醒锁\",3,1,-1,-1"))
        assertEquals("будильник-唤醒锁", capture.kernelWakelocks.single().name)
    }

    @Test
    fun `a very long wakelock name is not truncated`() {
        val name = "x".repeat(4096)
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"$name\",1,1,-1,-1"))
        assertEquals(4096, capture.kernelWakelocks.single().name.length)
    }

    @Test
    fun `a duplicated kernel wakelock name keeps both records and warns`() {
        val capture = success(
            decode(VERS_A16, "9,0,l,kwl,\"dup\",1,1,-1,-1", "9,0,l,kwl,\"dup\",2,2,-1,-1"),
        )

        assertEquals("neither record may be dropped", 2, capture.kernelWakelockCount)
        assertTrue(capture.warnings.any { it.kind == DecodeWarning.Kind.DUPLICATE_RECORD })
    }

    // ------------------------------------------------------------ partial wakelocks

    @Test
    fun `the partial block is read, not the full block`() {
        // full total 7713, partial total 4242.
        val capture = success(
            decode(
                VERS_A16,
                "9,1000,l,wl,WindowManager,7713,f,3,-1,-1,-1,4242,p,9,0,0,0,0,bp,0,0,0,0,0,w,0,0,0,0",
            ),
        )

        val wl = capture.partialWakelocks.single()
        assertEquals(1000, wl.uid)
        assertEquals("WindowManager", wl.name)
        assertEquals("the partial figure", 4242L, wl.totalTimeMillis)
        assertEquals(9L, wl.count)
        assertNotEquals("must not be the full figure", 7713L, wl.totalTimeMillis)
    }

    @Test
    fun `a wakelock record whose markers are misplaced is malformed, not unknown`() {
        // 'f' and 'p' swapped for numbers: a known tag with a broken layout.
        val capture = success(
            decode(VERS_A16, "9,1000,l,wl,Broken,0,0,0,0,0,0,0,0,0,0,0,0,bp,0,0,0,0,0,w,0,0,0,0"),
        )

        assertEquals("the record must not be accepted", 0, capture.partialWakelockCount)
        assertTrue(
            "and it must be reported as malformed rather than counted as an unknown tag",
            capture.warnings.any { it.kind == DecodeWarning.Kind.MALFORMED_RECORD && it.tag == "wl" },
        )
        assertTrue(!capture.unsupportedTags.containsKey("wl"))
    }

    @Test
    fun `a wakelock record too short to reach the partial block is refused`() {
        val capture = success(decode(VERS_A16, "9,1000,l,wl,Short,0,f,0,0,0"))

        assertEquals(0, capture.partialWakelockCount)
        assertTrue(capture.warnings.any { it.kind == DecodeWarning.Kind.TRUNCATED_RECORD })
    }

    // ------------------------------------------------------------- uid and package

    @Test
    fun `one uid maps to many packages`() {
        val capture = success(
            decode(
                VERS_A16,
                "9,0,i,uid,1000,android",
                "9,0,i,uid,1000,com.android.settings",
                "9,0,i,uid,10234,com.example.app",
            ),
        )

        assertEquals(3, capture.uidPackages.size)
        assertEquals(2, capture.uidPackages.count { it.uid == 1000 })
    }

    /**
     * Statistics survive when the name does not.
     *
     * Phase 3 measured that an ordinary application resolves fewer package names than the
     * shell does, because package-visibility filtering applies to it. A UID with no mapping
     * is therefore normal, and discarding its counters would silently delete real data on
     * exactly the backend that is most restricted.
     */
    @Test
    fun `a uid with no package mapping keeps its statistics`() {
        val capture = success(
            decode(VERS_A16, "9,10234,l,wl,SyncLock,0,f,0,0,0,0,500,p,4,0,0,0,bp,0,0,0,0,0,w,0,0,0,0"),
        )

        assertTrue("no mapping was supplied", capture.uidPackages.isEmpty())
        assertEquals("yet the statistic survives", 1, capture.partialWakelockCount)
        assertEquals(10234, capture.partialWakelocks.single().uid)
        assertEquals(500L, capture.partialWakelocks.single().totalTimeMillis)
    }

    @Test
    fun `a uid record with an empty package name is refused, not stored blank`() {
        val capture = success(decode(VERS_A16, "9,0,i,uid,1000,", KWL_ACTIVE))

        assertEquals(0, capture.uidPackages.size)
        assertTrue(capture.warnings.any { it.kind == DecodeWarning.Kind.MALFORMED_RECORD && it.tag == "uid" })
    }

    // ------------------------------------------------------- unknown and malformed

    @Test
    fun `an unknown tag is counted and does not stop the capture`() {
        val capture = success(
            decode(VERS_A16, "9,0,l,zzz,some,vendor,extension", "9,0,l,qqq,1", KWL_ACTIVE),
        )

        assertEquals(1, capture.unsupportedTags["zzz"])
        assertEquals(1, capture.unsupportedTags["qqq"])
        assertEquals("records after the unknown tags still decode", 1, capture.kernelWakelockCount)
    }

    @Test
    fun `an unknown tag does not corrupt its neighbours`() {
        val capture = success(
            decode(
                VERS_A16,
                "9,0,l,kwl,\"before\",11,1,-1,-1",
                "9,0,l,vendorext,\"junk\",not,a,number,,,",
                "9,0,l,kwl,\"after\",22,2,-1,-1",
            ),
        )

        assertEquals(2, capture.kernelWakelockCount)
        assertEquals(11L, capture.kernelWakelocks[0].totalTimeMillis)
        assertEquals(22L, capture.kernelWakelocks[1].totalTimeMillis)
    }

    @Test
    fun `a malformed number is refused rather than becoming zero`() {
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"bad\",not-a-number,5,-1,-1"))

        assertEquals("a record we cannot read must not be invented", 0, capture.kernelWakelockCount)
        assertTrue(capture.warnings.any { it.kind == DecodeWarning.Kind.MALFORMED_NUMBER })
    }

    @Test
    fun `a negative cumulative counter is refused`() {
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"neg\",-5,3,-1,-1"))

        assertEquals(0, capture.kernelWakelockCount)
        assertTrue(capture.warnings.any { it.kind == DecodeWarning.Kind.MALFORMED_NUMBER })
    }

    /**
     * A value beyond 64 bits becomes a typed refusal, never a wrapped negative.
     *
     * Silent overflow would produce a negative duration, which downstream looks exactly like
     * a counter reset -- a wrong answer that is expensive to trace back to its cause.
     */
    @Test
    fun `a counter that overflows 64 bits is refused with a warning`() {
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"huge\",99999999999999999999999,1,-1,-1"))

        assertEquals(0, capture.kernelWakelockCount)
        assertTrue(capture.warnings.any { it.kind == DecodeWarning.Kind.MALFORMED_NUMBER })
    }

    @Test
    fun `a legitimately large counter decodes`() {
        val capture = success(decode(VERS_A16, "9,0,l,kwl,\"big\",9223372036854775807,1,-1,-1"))
        assertEquals(Long.MAX_VALUE, capture.kernelWakelocks.single().totalTimeMillis)
    }

    // ------------------------------------------------------------------------ history

    @Test
    fun `history lines are counted, never treated as records`() {
        val capture = success(
            decode(
                VERS_A16,
                "9,h,0:RESET:TIME:1788344548223",
                "9,h,0,Bl=100,Bs=d,Bh=g,Bp=n,+r,+w",
                "9,h,123,-r",
                KWL_ACTIVE,
            ),
        )

        assertEquals(3, capture.historyLineCount)
        assertTrue("no event fragment may become a tag", capture.unsupportedTags.isEmpty())
        assertEquals(1, capture.kernelWakelockCount)
    }

    /**
     * A three-field history line must not be mistaken for a truncated record.
     *
     * `9,h,0:RESET:TIME:...` has three fields, one short of what an aggregate record needs.
     * An earlier version checked the length first and rejected thousands of these, both
     * undercounting the history and flooding the warning list.
     */
    @Test
    fun `a short history line is history, not a truncated record`() {
        val capture = success(decode(VERS_A16, "9,h,0:RESET:TIME:1788344548223", KWL_ACTIVE))

        assertEquals(1, capture.historyLineCount)
        assertTrue(
            "no truncation warning belongs here",
            capture.warnings.none { it.kind == DecodeWarning.Kind.TRUNCATED_RECORD },
        )
    }

    // ------------------------------------------------------- backend independence

    @Test
    fun `the same payload decodes identically from either backend`() {
        val bytes = listOf(VERS_A16, KWL_ACTIVE, WL_ACTIVE, UID_MAPPING).joinToString("\n").toByteArray()

        val shell = decoder.decode(bytes, metadata(bytes.size, BackendIdentity.Kind.SHELL))
        val app = decoder.decode(bytes, metadata(bytes.size, BackendIdentity.Kind.APP_UID))

        assertEquals(shell.captureOrNull!!.kernelWakelocks, app.captureOrNull!!.kernelWakelocks)
        assertEquals(shell.captureOrNull!!.partialWakelocks, app.captureOrNull!!.partialWakelocks)
        assertEquals(shell.captureOrNull!!.uidPackages, app.captureOrNull!!.uidPackages)
        assertEquals(
            "only the recorded provenance differs",
            BackendIdentity.Kind.SHELL,
            shell.captureOrNull!!.metadata.backendKind,
        )
    }

    @Test
    fun `decoding is deterministic`() {
        val bytes = listOf(VERS_A16, KWL_ACTIVE, WL_ACTIVE).joinToString("\n").toByteArray()
        assertEquals(
            decoder.decode(bytes, metadata(bytes.size)).captureOrNull,
            decoder.decode(bytes, metadata(bytes.size)).captureOrNull,
        )
    }

    // ------------------------------------------------------------- robustness

    /**
     * Random rubbish must not crash the process.
     *
     * Bounded and seeded rather than a fuzzing rig: the requirement is that no input reaches
     * an uncaught exception, and a few thousand deterministic cases demonstrate that as well
     * as a fuzzer would while staying reproducible when one fails.
     */
    @Test
    fun `random malformed lines never throw`() {
        val random = Random(SEED)
        val alphabet = ",\"9hilukwvers-0123456789abcnp \n\t"

        repeat(3_000) {
            val line = buildString {
                repeat(random.nextInt(0, 60)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val bytes = "$VERS_A16\n$line".toByteArray()
            // The contract is a typed result, never an exception.
            val result = decoder.decode(bytes, metadata(bytes.size))
            assertTrue("seed $SEED produced $result", result.outcome in DecodeOutcome.entries)
        }
    }

    @Test
    fun `every truncated prefix of a valid capture is refused or decoded, never crashes`() {
        val full = listOf(VERS_A16, KWL_ACTIVE, WL_ACTIVE, UID_MAPPING).joinToString("\n").toByteArray()

        for (cut in 1..full.size) {
            val prefix = full.copyOf(cut)
            val result = decoder.decode(prefix, metadata(prefix.size).copy(truncated = true))
            assertTrue(
                "cut at $cut produced a success from a truncated payload",
                result.captureOrNull == null,
            )
        }
    }

    // ------------------------------------------------------------------------ helpers

    private fun decode(vararg lines: String): DecodeResult {
        val bytes = lines.joinToString("\n").toByteArray()
        return decoder.decode(bytes, metadata(bytes.size))
    }

    private fun success(result: DecodeResult): BatteryStatsCapture {
        assertTrue("expected success, was ${result.outcome}: $result", result.succeeded)
        return (result as DecodeResult.Success).capture
    }

    private fun metadata(
        size: Int,
        backend: BackendIdentity.Kind = BackendIdentity.Kind.SHELL,
    ) = CaptureMetadata(
        sourceFormat = SourceFormat.CHECKIN,
        sourceFormatVersion = null,
        captureElapsedRealtimeMillis = 1_000L,
        captureWallClockMillis = 1_700_000_000_000L,
        backendKind = backend,
        platformVersion = "16",
        payloadByteCount = size,
        payloadHash = null,
        truncated = false,
    )

    private companion object {
        const val SEED = 20260904L

        const val VERS_A16 = "9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1"
        const val KWL_ACTIVE = "9,0,l,kwl,\"bt_read_wake_lock\",681038,678,-1,-1"
        const val WL_ACTIVE =
            "9,1000,l,wl,WindowManager,7713,f,3,-1,-1,-1,120,p,2,0,0,0,bp,0,0,0,0,0,w,0,0,0,0"
        const val UID_MAPPING = "9,0,i,uid,1000,com.android.settings"
    }
}

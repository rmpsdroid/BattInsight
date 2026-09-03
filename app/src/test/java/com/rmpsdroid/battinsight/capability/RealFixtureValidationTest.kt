package com.rmpsdroid.battinsight.capability

import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.CollectionOutcome
import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.permissions.RequiredPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Validates the probe against **real** captured output rather than hand-written strings.
 *
 * Hand-written fixtures test the code against the author's belief about the format. These
 * cases run against bytes `dumpsys` actually produced during the capability discovery
 * phases: Android 10 on a physical Samsung and Android 16 on an emulator, at every
 * permission combination that was measured.
 *
 * ## Why the captures are not in this repository
 *
 * They are ~15 MB of device state -- installed package names, wakelock names, per-UID
 * activity. That is the user's data, not test material, and publishing it would leak
 * exactly the kind of information this application exists to keep local. So the captures
 * stay outside the repository and these cases are skipped unless the archive is present.
 *
 * Point `battinsight.fixtures` (system property) or `BATTINSIGHT_FIXTURES` (environment)
 * at the directory holding `PHASE1A_CAPABILITY_DISCOVERY` and `PHASE1B_APP_SHIZUKU_PROBE`:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest -Dbattinsight.fixtures=/path/to/archive
 * ```
 *
 * Skipping is the correct behaviour for CI, and it is visible: JUnit reports these as
 * skipped, not passed.
 */
class RealFixtureValidationTest {

    // -------------------------------------------------------------- successful captures

    @Test
    fun `real protobuf captures are recognised on both measured platforms`() {
        listOf(
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/dev-samsung-m305f-a10/shell/bs_proto.stdout",
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/emu-pixel8-a16/shell/bs_proto.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/all_four/dumpsys_proto.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/dump_pus_iau/dumpsys_proto.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/shizuku_grant/shizuku_proto.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/adb_ref_3perm/adb_proto.stdout",
        ).forEach { path ->
            val bytes = fixture(path) ?: return skip()
            val shape = BatteryStatsProbe.looksLikeProto(bytes)
            assertTrue(
                "$path should be valid protobuf framing but was $shape",
                shape is BatteryStatsProbe.ProtoShape.Valid,
            )
            assertEquals(
                "$path should yield Available",
                CapabilityState.Available,
                evaluateProto(bytes, BackendIdentity.Kind.SHELL),
            )
        }
    }

    /**
     * The framing difference that a decoder must not paper over.
     *
     * Plain `--proto` is length-delimited (field 1, wire type 2). `--proto --history` emits
     * the bare message instead, so it must *not* satisfy the plain-proto check -- otherwise
     * a future decoder would happily read the wrong shape.
     */
    @Test
    fun `history protobuf uses different framing and is not mistaken for the plain form`() {
        listOf(
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/dev-samsung-m305f-a10/shell/bs_proto_history.stdout",
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/emu-pixel8-a16/shell/bs_proto_history.stdout",
        ).forEach { path ->
            val bytes = fixture(path) ?: return skip()
            val shape = BatteryStatsProbe.looksLikeProto(bytes)
            assertTrue(
                "$path must not pass the plain --proto check, but was $shape",
                shape !is BatteryStatsProbe.ProtoShape.Valid,
            )
        }
    }

    // ----------------------------------------------------------------- checkin captures

    @Test
    fun `real checkin captures yield kernel wakelock records`() {
        listOf(
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/dev-samsung-m305f-a10/shell/bs_c_lowercase.stdout",
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/emu-pixel8-a16/shell/bs_c_lowercase.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/all_four/dumpsys_checkin_c.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/shizuku_grant/shizuku_checkin_c.stdout",
        ).forEach { path ->
            val bytes = fixture(path) ?: return skip()
            val reading = BatteryStatsProbe.scanKernelWakelocks(String(bytes, Charsets.UTF_8))
            assertTrue(
                "$path should contain kwl records but read $reading",
                reading is SourceReading.Records && reading.total > 0,
            )
        }
    }

    /**
     * Where the `kwl` block actually sits, which is the reason a prefix scan will not do.
     *
     * Measured: 672 KB into an 803 KB Android 16 capture, 764 KB into an 872 KB Android 10
     * capture -- 84% and 88% of the way in. A 512 KB scan window, which is what this code
     * originally used, reaches none of it, and the honest reading of that is "we did not
     * look far enough", never "this device has no kernel wakelocks".
     */
    @Test
    fun `kernel wakelocks live deep in the payload and a prefix scan misses them`() {
        listOf(
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/emu-pixel8-a16/shell/bs_c_lowercase.stdout",
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/dev-samsung-m305f-a10/shell/bs_c_lowercase.stdout",
        ).forEach { path ->
            val bytes = fixture(path) ?: return skip()
            val text = String(bytes, Charsets.UTF_8)
            val offset = text.indexOf(",kwl,")
            assertTrue("$path should contain kwl records", offset > 0)
            assertTrue(
                "$path: kwl at $offset of ${text.length} -- expected it beyond a 512 KB prefix",
                offset > OLD_PREFIX_LIMIT,
            )

            // The prefix that used to be scanned genuinely contains nothing.
            assertEquals(
                "$path: a 512 KB prefix contains no kwl record",
                SourceReading.SectionAbsent,
                BatteryStatsProbe.scanKernelWakelocks(text.take(OLD_PREFIX_LIMIT)),
            )
            // Scanning the whole capture, over bytes, finds them.
            val whole = BatteryStatsProbe.scanKernelWakelocks(bytes, truncated = false)
            assertTrue("$path should yield records when fully scanned, was $whole", whole is SourceReading.Records)
            assertTrue((whole as SourceReading.Records).total > 0)
        }
    }

    /**
     * The measured Android 16 case that the whole `AvailableNoEvents` state exists for: a
     * healthy source reporting named kernel wakelocks whose counters are all zero, because
     * the emulator never suspends. Treating that as a failure would be wrong.
     */
    @Test
    fun `android 16 kernel wakelocks are present with zero activity`() {
        val bytes = fixture(
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/emu-pixel8-a16/shell/bs_c_lowercase.stdout",
        ) ?: return skip()
        val reading = BatteryStatsProbe.scanKernelWakelocks(String(bytes, Charsets.UTF_8))
        assertTrue(reading is SourceReading.Records)
        val records = reading as SourceReading.Records
        assertEquals("no wakelock should carry activity on a device that never suspends", 0, records.withValues)
        assertTrue("expected named records", records.total > 0)
        assertTrue(
            "the interpreter must call this healthy-but-idle",
            CapabilityInterpreter.interpret(CollectionOutcome.Data(bytes.size), reading)
                is CapabilityState.AvailableNoEvents,
        )
    }

    @Test
    fun `checkin version records parse on both platforms without magnitude assumptions`() {
        val a10 = fixture(
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/dev-samsung-m305f-a10/shell/bs_c_lowercase.stdout",
        ) ?: return skip()
        val a16 = fixture(
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/emu-pixel8-a16/shell/bs_c_lowercase.stdout",
        ) ?: return skip()

        val v10 = BatteryStatsProbe.readCheckinVersion(String(a10, Charsets.UTF_8).take(VERS_WINDOW))
        val v16 = BatteryStatsProbe.readCheckinVersion(String(a16, Charsets.UTF_8).take(VERS_WINDOW))
        assertTrue("Android 10 vers record must parse", v10 != null)
        assertTrue("Android 16 vers record must parse", v16 != null)
        // The reason nothing may compare these as ranges: the parcel version went *down*
        // between the two platforms. Any `>=` gate written against it would be wrong.
        assertTrue(
            "parcel versions are not ordered by platform age (${v10!!.parcelVersion} vs ${v16!!.parcelVersion})",
            v10.parcelVersion != v16.parcelVersion,
        )
    }

    // -------------------------------------------------------------------- real denials

    @Test
    fun `the measured denials classify to the permission we can actually grant`() {
        val cases = listOf(
            Triple(
                "PHASE1B_APP_SHIZUKU_PROBE/fixtures/baseline/dumpsys_proto.stdout",
                RequiredPermission.DUMP.manifestName,
                emptyList<String>(),
            ),
            Triple(
                "PHASE1B_APP_SHIZUKU_PROBE/fixtures/dump_only/dumpsys_proto.stdout",
                RequiredPermission.PACKAGE_USAGE_STATS.manifestName,
                emptyList(),
            ),
            Triple(
                "PHASE1B_APP_SHIZUKU_PROBE/fixtures/dump_pus/dumpsys_proto.stdout",
                RequiredPermission.INTERACT_ACROSS_USERS.manifestName,
                listOf("android.permission.INTERACT_ACROSS_USERS_FULL"),
            ),
        )
        cases.forEach { (path, expected, alternatives) ->
            val bytes = fixture(path) ?: return skip()
            val outcome = outcomeOf(bytes, SourceFormat.PROTO, BackendIdentity.Kind.APP_UID)
            assertTrue("$path should be a denial but was $outcome", outcome is CollectionOutcome.PermissionDenied)
            val denial = outcome as CollectionOutcome.PermissionDenied
            assertEquals(path, expected, denial.permission)
            assertEquals("$path alternatives", alternatives, denial.alternatives)
        }
    }

    /**
     * Every measured denial arrived with **exit status 0**. This is the observation the
     * whole classification order is built on, so it is asserted against the real bytes:
     * a zero exit with denial text must never be read as success.
     */
    @Test
    fun `a denial with exit zero is never reported as data`() {
        listOf(
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/baseline/dumpsys_proto.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/baseline/dumpsys_checkin_c.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/dump_only/dumpsys_checkin_c.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/dump_pus/dumpsys_checkin_c.stdout",
            "PHASE1B_APP_SHIZUKU_PROBE/fixtures/shizuku_grant/dumpsys_proto.stdout",
        ).forEach { path ->
            val bytes = fixture(path) ?: return skip()
            val format = if (path.contains("checkin")) SourceFormat.CHECKIN else SourceFormat.PROTO
            val outcome = outcomeOf(bytes, format, BackendIdentity.Kind.APP_UID)
            assertTrue(
                "$path must not classify as Data (exit 0 is not success)",
                outcome !is CollectionOutcome.Data,
            )
            assertTrue("$path should be a denial but was $outcome", outcome is CollectionOutcome.PermissionDenied)
        }
    }

    /**
     * Shizuku authorisation alone changes nothing for the application UID. The measured
     * pair: through Shizuku the same device returned a full capture, while the app's own
     * process still hit the DUMP denial.
     */
    @Test
    fun `shizuku authorisation does not by itself grant the app uid anything`() {
        val viaApp = fixture("PHASE1B_APP_SHIZUKU_PROBE/fixtures/shizuku_grant/dumpsys_proto.stdout")
            ?: return skip()
        val viaShizuku = fixture("PHASE1B_APP_SHIZUKU_PROBE/fixtures/shizuku_grant/shizuku_proto.stdout")
            ?: return skip()

        assertTrue(
            "the app's own process must still be denied",
            outcomeOf(viaApp, SourceFormat.PROTO, BackendIdentity.Kind.APP_UID)
                is CollectionOutcome.PermissionDenied,
        )
        assertEquals(
            "the same probe through Shizuku must succeed",
            CapabilityState.Available,
            evaluateProto(viaShizuku, BackendIdentity.Kind.SHELL),
        )
    }

    // -------------------------------------------------------------- truncation on real data

    /**
     * A real capture cut at the ceiling, checked against the same capture read whole.
     *
     * This is the case the memory ceiling actually produces in the field: the Android 10
     * checkin payload is ~850 KB against a 1 MB cap, so a slightly larger device output
     * lands mid-payload. Cutting before the `kwl` block must read as inconclusive, never as
     * "this device has no kernel wakelocks".
     */
    @Test
    fun `a real capture cut before the kwl block is inconclusive not absent`() {
        val bytes = fixture(
            "PHASE1A_CAPABILITY_DISCOVERY/fixtures/dev-samsung-m305f-a10/shell/bs_c_lowercase.stdout",
        ) ?: return skip()
        val whole = String(bytes, Charsets.UTF_8)

        val firstKwl = whole.indexOf(",kwl,")
        assertTrue("fixture must contain a kwl block to cut before", firstKwl > 0)
        val cutShort = whole.substring(0, firstKwl / 2)

        assertEquals(
            "cutting short must not be silently reported as absence",
            SourceReading.SectionAbsent,
            BatteryStatsProbe.scanKernelWakelocks(cutShort, truncated = false),
        )
        val truncatedReading = BatteryStatsProbe.scanKernelWakelocks(cutShort, truncated = true)
        assertTrue(
            "expected Incomplete but was $truncatedReading",
            truncatedReading is SourceReading.Incomplete,
        )
        assertEquals(
            CapabilityState.Unknown,
            CapabilityInterpreter.interpret(CollectionOutcome.Data(cutShort.length), truncatedReading),
        )
        // And the complete capture still reads correctly, so the flag is what changed the
        // answer -- not the parsing.
        assertTrue(BatteryStatsProbe.scanKernelWakelocks(whole) is SourceReading.Records)
    }

    // ------------------------------------------------------------------------- helpers

    private fun evaluateProto(bytes: ByteArray, backend: BackendIdentity.Kind): CapabilityState {
        val out = ExecutionOutput(ProbeCommand.BatteryStatsProto, 0, bytes, ByteArray(0), 1)
        val result = BatteryStatsProbe.toCollectionResult(out, backend, SourceFormat.PROTO, 0L)
        return BatteryStatsProbe.evaluateProtoAcquisition(result, bytes, out.truncated)
    }

    private fun outcomeOf(
        bytes: ByteArray,
        format: SourceFormat,
        backend: BackendIdentity.Kind,
    ): CollectionOutcome {
        // Exit 0, empty stderr: exactly how every measured denial arrived.
        val command =
            if (format == SourceFormat.PROTO) ProbeCommand.BatteryStatsProto
            else ProbeCommand.BatteryStatsCheckinCurrent
        val out = ExecutionOutput(command, 0, bytes, ByteArray(0), 1)
        return BatteryStatsProbe.toCollectionResult(out, backend, format, 0L).outcome()
    }

    /** Reads a capture, or null when the archive is not available on this machine. */
    private fun fixture(relativePath: String): ByteArray? {
        val root = fixtureRoot ?: return null
        val file = File(root, relativePath)
        assumeTrue("fixture archive is present but $relativePath is missing", file.isFile)
        return file.readBytes()
    }

    private fun skip() = assumeTrue("no fixture archive configured; set battinsight.fixtures", false)

    private companion object {
        /** Enough of a checkin payload to reach the `vers` record, which comes early. */
        const val VERS_WINDOW = 64 * 1024

        /** The prefix this code used to scan, kept only so the regression stays pinned. */
        const val OLD_PREFIX_LIMIT = 512 * 1024

        val fixtureRoot: File? = (
            System.getProperty("battinsight.fixtures")
                ?: System.getenv("BATTINSIGHT_FIXTURES")
            )?.let { File(it) }?.takeIf { it.isDirectory }
    }
}

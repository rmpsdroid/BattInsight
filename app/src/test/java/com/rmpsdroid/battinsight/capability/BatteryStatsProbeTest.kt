package com.rmpsdroid.battinsight.capability

import com.rmpsdroid.battinsight.collection.ProbeCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural validation and the command whitelist that protects the device. */
class BatteryStatsProbeTest {

    // ---- protobuf framing ----

    @Test
    fun `well framed protobuf is recognised`() {
        val shape = BatteryStatsProbe.looksLikeProto(fakeProtoPayload(4096))
        assertTrue("expected Valid, was $shape", shape is BatteryStatsProbe.ProtoShape.Valid)
    }

    @Test
    fun `a text denial is not accepted as protobuf`() {
        // The trap this exists for: a denial arrives with exit 0, so only the shape of the
        // payload distinguishes it from real output.
        val shape = BatteryStatsProbe.looksLikeProto(MeasuredDenials.DUMP.toByteArray())
        assertTrue("expected NotProtobuf, was $shape", shape is BatteryStatsProbe.ProtoShape.NotProtobuf)
    }

    @Test
    fun `empty output is not protobuf`() {
        assertEquals(BatteryStatsProbe.ProtoShape.Empty, BatteryStatsProbe.looksLikeProto(ByteArray(0)))
    }

    @Test
    fun `truncated protobuf whose declared length does not match is rejected`() {
        val good = fakeProtoPayload(4096)
        val truncated = good.copyOf(good.size - 100)
        assertTrue(BatteryStatsProbe.looksLikeProto(truncated) is BatteryStatsProbe.ProtoShape.NotProtobuf)
    }

    @Test
    fun `tiny binary output is rejected as implausible`() {
        assertTrue(
            BatteryStatsProbe.looksLikeProto(byteArrayOf(0x0A, 0x02, 0x01, 0x02))
                is BatteryStatsProbe.ProtoShape.TooSmall,
        )
    }

    // ---- checkin scanning ----

    @Test
    fun `kernel wakelock records with values are counted`() {
        val reading = BatteryStatsProbe.scanKernelWakelocks(fakeCheckinWithKwl(111, 43))
        assertTrue(reading is SourceReading.Records)
        reading as SourceReading.Records
        assertEquals(111, reading.total)
        assertEquals(43, reading.withValues)
    }

    @Test
    fun `kernel wakelock records with no values are still records`() {
        val reading = BatteryStatsProbe.scanKernelWakelocks(fakeCheckinWithKwl(68, 0))
        reading as SourceReading.Records
        assertEquals(68, reading.total)
        assertEquals(0, reading.withValues)
        // The interpreter, not the scanner, decides this means AvailableNoEvents.
        assertTrue(
            CapabilityInterpreter.interpret(
                com.rmpsdroid.battinsight.collection.CollectionOutcome.Data(100), reading,
            ) is CapabilityState.AvailableNoEvents,
        )
    }

    @Test
    fun `checkin without kwl records reports the section absent`() {
        val text = "9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1\n9,0,l,wl,\"x\",1,1"
        assertEquals(SourceReading.SectionAbsent, BatteryStatsProbe.scanKernelWakelocks(text))
    }

    @Test
    fun `quoted wakelock names containing commas are parsed correctly`() {
        // A naive split on commas mis-counts these -- part of why protobuf is the better
        // routine format and checkin is used only where text scanning is needed.
        val line = """9,0,l,kwl,"a,b,c",500,7,-1,-1"""
        val fields = BatteryStatsProbe.splitCheckinLine(line)
        assertEquals("kwl", fields[3])
        assertEquals("a,b,c", fields[4])
        assertEquals("500", fields[5])

        val reading = BatteryStatsProbe.scanKernelWakelocks(line) as SourceReading.Records
        assertEquals(1, reading.total)
        assertEquals(1, reading.withValues)
    }

    // ---- version block ----

    @Test
    fun `checkin version block is extracted`() {
        val v = BatteryStatsProbe.readCheckinVersion(fakeCheckinWithKwl(1, 1))!!
        assertEquals(36, v.checkinVersion)
        assertEquals(215L, v.parcelVersion)
        assertEquals("BE2A.250530.026.D1", v.startPlatformVersion)
    }

    @Test
    fun `absent version block returns null rather than a default`() {
        assertNull(BatteryStatsProbe.readCheckinVersion("9,0,l,kwl,\"x\",0,0,-1,-1"))
    }

    // ---- command whitelist: the security boundary ----

    @Test
    fun `no probe command contains a state changing argument`() {
        ProbeCommand.all.forEach { command ->
            ProbeCommand.forbiddenArguments.forEach { forbidden ->
                assertFalse(
                    "${command.id} must not use $forbidden",
                    command.argv.any { it == forbidden },
                )
            }
        }
    }

    @Test
    fun `checkin uses the non-clearing argument`() {
        // --checkin writes and clears the last old completed stats, per the platform's own
        // help text. -c only writes current stats.
        assertEquals(
            listOf("dumpsys", "batterystats", "-c"),
            ProbeCommand.BatteryStatsCheckinCurrent.argv,
        )
        assertFalse(ProbeCommand.all.any { it.argv.contains("--checkin") })
    }

    @Test
    fun `the whitelist is small and every entry is read only`() {
        assertEquals(4, ProbeCommand.all.size)
        val allowedHeads = setOf("id", "dumpsys")
        ProbeCommand.all.forEach { assertTrue(it.argv.first() in allowedHeads) }
    }

    @Test
    fun `execution output never puts payload bytes in its string form`() {
        // toString is logged; payloads must not be.
        val out = com.rmpsdroid.battinsight.collection.ExecutionOutput(
            ProbeCommand.BatteryStatsProto, 0, "SECRETPAYLOAD".toByteArray(), ByteArray(0), 5,
        )
        assertFalse(out.toString().contains("SECRETPAYLOAD"))
        assertTrue(out.toString().contains("13B"))
    }

    @Test
    fun `stdout head is bounded so large payloads are never fully decoded`() {
        val big = ByteArray(900_000) { 'x'.code.toByte() }
        val out = com.rmpsdroid.battinsight.collection.ExecutionOutput(
            ProbeCommand.BatteryStatsCheckinCurrent, 0, big, ByteArray(0), 5,
        )
        assertEquals(4096, out.stdoutHead().length)
        assertEquals(900_000, out.stdoutBytes)
    }
}

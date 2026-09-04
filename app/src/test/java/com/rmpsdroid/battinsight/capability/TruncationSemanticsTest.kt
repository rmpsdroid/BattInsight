package com.rmpsdroid.battinsight.capability

import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.CollectionOutcome
import com.rmpsdroid.battinsight.collection.ExecutionOutput
import com.rmpsdroid.battinsight.collection.ProbeCommand
import com.rmpsdroid.battinsight.collection.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a capture cut short at the memory ceiling is allowed to conclude.
 *
 * The rule: absence of evidence inside a truncated payload is not evidence of absence. The
 * ceiling exists so a ~800 KB checkin payload cannot be held whole and a misbehaving
 * process cannot exhaust us -- but it is our limit, not the device's, and it must never be
 * reported as a property of the device.
 *
 * So the answer in that case is [CapabilityState.Unknown], and specifically **not**
 * [CapabilityState.SourceUnavailable]: the latter is a claim about the platform.
 */
class TruncationSemanticsTest {

    // ------------------------------------------------------- kernel wakelock scanning

    @Test
    fun `no kwl records in a complete capture means the section is absent`() {
        val reading = BatteryStatsProbe.scanKernelWakelocks(CHECKIN_WITHOUT_KWL, truncated = false)
        assertEquals(SourceReading.SectionAbsent, reading)
    }

    @Test
    fun `no kwl records in a truncated capture is inconclusive`() {
        val reading = BatteryStatsProbe.scanKernelWakelocks(CHECKIN_WITHOUT_KWL, truncated = true)
        assertTrue("expected Incomplete but was $reading", reading is SourceReading.Incomplete)
    }

    @Test
    fun `finding records still counts even when the capture was cut short`() {
        // Truncation only invalidates a negative answer. Records were observed, so the
        // source demonstrably works, whatever lies beyond the ceiling.
        val reading = BatteryStatsProbe.scanKernelWakelocks(CHECKIN_WITH_KWL, truncated = true)
        assertTrue("expected Records but was $reading", reading is SourceReading.Records)
        assertEquals(2, (reading as SourceReading.Records).total)
    }

    // ------------------------------------------------------------------ interpretation

    @Test
    fun `an incomplete reading resolves to Unknown not SourceUnavailable`() {
        val state = CapabilityInterpreter.interpret(
            CollectionOutcome.Data(1024),
            SourceReading.Incomplete("cut short"),
        )
        assertEquals(CapabilityState.Unknown, state)
    }

    @Test
    fun `an incomplete reading on an empty collection is also Unknown`() {
        val state = CapabilityInterpreter.interpret(
            CollectionOutcome.Empty,
            SourceReading.Incomplete("cut short"),
        )
        assertEquals(CapabilityState.Unknown, state)
    }

    @Test
    fun `a complete absent section still reports the source as unavailable`() {
        // The contrast that gives the rule its meaning: this one *is* a claim about the
        // device, and it is allowed because the capture was complete.
        val state = CapabilityInterpreter.interpret(
            CollectionOutcome.Data(1024),
            SourceReading.SectionAbsent,
        )
        assertTrue(state is CapabilityState.SourceUnavailable)
    }

    @Test
    fun `truncation never overrides a permission denial`() {
        // A denial is established by the platform's own message; it does not depend on
        // having read the whole payload.
        val state = CapabilityInterpreter.interpret(
            CollectionOutcome.PermissionDenied("android.permission.DUMP", emptyList(), "denied"),
            SourceReading.Incomplete("cut short"),
        )
        assertTrue(state is CapabilityState.PermissionMissing)
    }

    // ------------------------------------------------------------- protobuf acquisition

    @Test
    fun `a truncated protobuf with unaccounted framing is Unknown not a failure`() {
        // Without the truncation flag this shape reads as malformed output. It is not:
        // the declared length simply describes bytes we stopped reading.
        val bytes = lengthDelimited(declared = 900_000, present = 4_096)
        val out = ExecutionOutput(
            ProbeCommand.BatteryStatsProto, 0, bytes, ByteArray(0), 5, truncated = true,
        )
        val result = BatteryStatsProbe.toCollectionResult(
            out, BackendIdentity.Kind.SHELL, SourceFormat.PROTO, 0L,
        )
        assertEquals(
            CapabilityState.Unknown,
            BatteryStatsProbe.evaluateProtoAcquisition(result, bytes, truncated = true),
        )
    }

    @Test
    fun `the same bytes without truncation are correctly reported as malformed`() {
        val bytes = lengthDelimited(declared = 900_000, present = 4_096)
        val out = ExecutionOutput(ProbeCommand.BatteryStatsProto, 0, bytes, ByteArray(0), 5)
        val result = BatteryStatsProbe.toCollectionResult(
            out, BackendIdentity.Kind.SHELL, SourceFormat.PROTO, 0L,
        )
        val state = BatteryStatsProbe.evaluateProtoAcquisition(result, bytes, truncated = false)
        assertTrue("expected ExecutionFailed but was $state", state is CapabilityState.ExecutionFailed)
    }

    @Test
    fun `a protobuf whose declared length is fully present is complete despite the flag`() {
        // Hitting the ceiling exactly as the stream ended sets the flag, but the framing
        // accounts for every byte, so the payload is whole and the verdict stands.
        val bytes = lengthDelimited(declared = 4_096, present = 4_096)
        val out = ExecutionOutput(
            ProbeCommand.BatteryStatsProto, 0, bytes, ByteArray(0), 5, truncated = true,
        )
        val result = BatteryStatsProbe.toCollectionResult(
            out, BackendIdentity.Kind.SHELL, SourceFormat.PROTO, 0L,
        )
        assertEquals(
            CapabilityState.Available,
            BatteryStatsProbe.evaluateProtoAcquisition(result, bytes, truncated = true),
        )
    }

    @Test
    fun `truncation is carried on the execution output itself`() {
        val out = ExecutionOutput(
            ProbeCommand.Identity, 0, ByteArray(8), ByteArray(0), 1, truncated = true,
        )
        assertTrue(out.truncated)
        // Metadata only, and it names the flag so a log makes the situation legible.
        assertTrue(out.toString().contains("truncated=true"))
    }

    /**
     * Builds plain `--proto` framing: field 1, wire type 2, a length varint, then [present]
     * bytes of body. Sized above the classifier's plausibility floor so these cases test
     * truncation semantics rather than the size gate.
     */
    private fun lengthDelimited(declared: Int, present: Int): ByteArray {
        val header = mutableListOf<Byte>(0x0A)
        var v = declared
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) {
                header.add(b.toByte())
                break
            }
            header.add((b or 0x80).toByte())
        }
        val body = ByteArray(present) { (it * 37 % 200 + 1).toByte() }
        return header.toByteArray() + body
    }

    private companion object {
        val CHECKIN_WITH_KWL = """
            9,0,i,vers,36,215,BP3A.250905.014,BP3A.250905.014
            9,0,l,kwl,"bt_read_wake_lock",681038,678,-1,-1
            9,0,l,kwl,"PowerManagerService.WakeLocks",0,0,-1,-1
        """.trimIndent()

        val CHECKIN_WITHOUT_KWL = """
            9,0,i,vers,36,215,BP3A.250905.014,BP3A.250905.014
            9,0,l,wl,"*alarm*",0,0,0,0,0,0
        """.trimIndent()
    }
}

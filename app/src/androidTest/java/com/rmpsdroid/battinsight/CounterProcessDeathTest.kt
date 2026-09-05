package com.rmpsdroid.battinsight

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.CaptureMetadata
import com.rmpsdroid.battinsight.batterystats.CheckinDecoder
import com.rmpsdroid.battinsight.batterystats.CounterDeltaEngine
import com.rmpsdroid.battinsight.batterystats.CounterDeltaResult
import com.rmpsdroid.battinsight.batterystats.DecodeResult
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.persistence.BattInsightDatabase
import com.rmpsdroid.battinsight.persistence.CounterPersistResult
import com.rmpsdroid.battinsight.persistence.Mappers
import com.rmpsdroid.battinsight.persistence.RoomCounterStore
import com.rmpsdroid.battinsight.session.BatteryHealth
import com.rmpsdroid.battinsight.session.BatteryObservation
import com.rmpsdroid.battinsight.session.BatterySession
import com.rmpsdroid.battinsight.session.BatterySnapshot
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CaptureTime
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.CounterSource
import com.rmpsdroid.battinsight.session.ElapsedRealtime
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session's counter baseline surviving the death of the process that established it.
 *
 * This is the claim Phase 7B has to make, and it cannot be made on the JVM: an in-memory
 * database that a test closes and reopens has not survived anything.
 *
 * So it runs as **three separate instrumentation invocations** with `am force-stop` between
 * them, driven by `tools/counter-process-death-proof.sh`:
 *
 *   1. [captureA] establishes a session and stores the first capture as baseline and latest.
 *   2. The harness kills the process.
 *   3. [captureB] runs in a new process, stores a second capture, and asserts the baseline is
 *      the same one it never saw created.
 *   4. The harness kills the process again.
 *   5. [verifyAfterSecondDeath] reads the state cold and computes a delta from it.
 *
 * The counters come from a real `dumpsys batterystats -c` capture the harness pushed to this
 * application's own external files directory, decoded by the production decoder. The second
 * capture is that payload with its wakelock totals advanced, because an emulator that never
 * suspends will not advance them on its own -- and Step 41 is explicit that activity must not
 * be fabricated to force real ones. What is proved here is durability and arithmetic, and the
 * report says exactly that.
 */
@RunWith(AndroidJUnit4::class)
class CounterProcessDeathTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ------------------------------------------------------------------------- step 1

    @Test
    fun captureA(): Unit = runBlocking {
        val db = BattInsightDatabase.get(context)
        val store = RoomCounterStore(db.counterDao())

        // Start from nothing so the assertions are about this run.
        store.clear()
        db.sessionDao().clearAll()

        val sessionId = UUID.randomUUID()
        val snapshot = seedSession(db, sessionId)

        val capture = decodePushedCapture()
        val result = store.store(
            capture = capture,
            batterySessionId = sessionId.toString(),
            batterySnapshotId = snapshot.toString(),
            counterGeneration = GENERATION,
            bootIdentity = BOOT,
        )

        assertTrue("the first capture must be stored, was $result", result.succeeded)
        assertEquals(
            CounterPersistResult.Role.BASELINE,
            (result as CounterPersistResult.Stored).role,
        )

        val state = store.state(sessionId.toString())!!
        assertTrue("baseline and latest share one row on the first capture", state.baselineIsLatest)

        Log.i(TAG, "$M sessionId=$sessionId")
        Log.i(TAG, "$M baselineCaptureId=${state.baseline.captureId}")
        Log.i(TAG, "$M latestCaptureId=${state.latest.captureId}")
        Log.i(TAG, "$M kwl=${state.baseline.kernelWakelocks.size}")
        Log.i(TAG, "$M pwl=${state.baseline.partialWakelocks.size}")
        Log.i(TAG, "$M captures=${store.captureCount()}")
        Log.i(TAG, "$M pid=${android.os.Process.myPid()}")
    }

    // ------------------------------------------------------------------------- step 3

    @Test
    fun captureB(): Unit = runBlocking {
        val expectedSession = arg("expectedSessionId")
        val expectedBaseline = arg("expectedBaselineCaptureId")

        val db = BattInsightDatabase.get(context)
        val store = RoomCounterStore(db.counterDao())

        // Read the state cold, before writing anything, so this is the database speaking.
        val restored = store.state(expectedSession)
        assertNotNull("the session's counter state must survive process death", restored)
        assertEquals(
            "the baseline this process never created must be the one it finds",
            expectedBaseline,
            restored!!.baseline.captureId,
        )
        assertTrue("still only one capture", store.captureCountFor(expectedSession) == 1)

        // A second capture, with the counters advanced.
        val advanced = advance(decodePushedCapture(), byMillis = 5_000L, byCount = 5L)
        val result = store.store(
            capture = advanced,
            batterySessionId = expectedSession,
            batterySnapshotId = null,
            counterGeneration = GENERATION,
            bootIdentity = BOOT,
        )
        assertEquals(
            CounterPersistResult.Role.LATEST,
            (result as CounterPersistResult.Stored).role,
        )

        val state = store.state(expectedSession)!!
        assertEquals("the baseline is immutable", expectedBaseline, state.baseline.captureId)
        assertNotEquals("the latest moved", expectedBaseline, state.latest.captureId)
        assertEquals("bounded at two captures", 2, store.captureCountFor(expectedSession))

        Log.i(TAG, "$M baselineAfterB=${state.baseline.captureId}")
        Log.i(TAG, "$M latestAfterB=${state.latest.captureId}")
        Log.i(TAG, "$M captures=${store.captureCount()}")
        Log.i(TAG, "$M pid=${android.os.Process.myPid()}")
    }

    // ------------------------------------------------------------------------- step 5

    @Test
    fun verifyAfterSecondDeath(): Unit = runBlocking {
        val expectedSession = arg("expectedSessionId")
        val expectedBaseline = arg("expectedBaselineCaptureId")
        val expectedLatest = arg("expectedLatestCaptureId")

        val store = RoomCounterStore(BattInsightDatabase.get(context).counterDao())
        val state = store.state(expectedSession)!!

        assertEquals(expectedBaseline, state.baseline.captureId)
        assertEquals(expectedLatest, state.latest.captureId)
        assertEquals(2, store.captureCountFor(expectedSession))

        // The delta engine over state neither of the two writing processes is alive to see.
        val deltas = CounterDeltaEngine.kernelWakelockDeltas(state)
        assertTrue("the pair must be comparable, was $deltas", deltas.succeeded)
        val values = (deltas as CounterDeltaResult.Success).value
        assertTrue("every delta is non-negative", values.all { it.durationDeltaMillis >= 0L })

        val advanced = values.filter { it.durationDeltaMillis > 0L }
        Log.i(TAG, "$M comparable=true")
        Log.i(TAG, "$M kwlDeltas=${values.size}")
        Log.i(TAG, "$M kwlAdvanced=${advanced.size}")
        Log.i(TAG, "$M sampleDeltaMillis=${advanced.firstOrNull()?.durationDeltaMillis ?: 0}")
        Log.i(TAG, "$M pid=${android.os.Process.myPid()}")

        val partial = CounterDeltaEngine.partialWakelockDeltas(state)
        assertTrue(partial.succeeded)
        Log.i(TAG, "$M pwlDeltas=${(partial as CounterDeltaResult.Success).value.size}")
    }

    // ------------------------------------------------------------------------ helpers

    private fun arg(name: String): String =
        InstrumentationRegistry.getArguments().getString(name)
            ?: error("$name must be supplied; run tools/counter-process-death-proof.sh")

    /** Decodes the capture the harness pushed, using the production decoder. */
    private fun decodePushedCapture(): com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture {
        val file = File(context.getExternalFilesDir(null), CAPTURE_FILE)
        assumeTrue("no capture pushed; run tools/counter-process-death-proof.sh", file.isFile)
        val payload = file.readBytes()
        val result = CheckinDecoder().decode(
            payload,
            CaptureMetadata(
                sourceFormat = SourceFormat.CHECKIN,
                sourceFormatVersion = null,
                captureElapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime(),
                captureWallClockMillis = System.currentTimeMillis(),
                backendKind = BackendIdentity.Kind.SHELL,
                platformVersion = android.os.Build.VERSION.RELEASE,
                payloadByteCount = payload.size,
                payloadHash = null,
                truncated = false,
            ),
        )
        assertTrue("the pushed capture must decode, was ${result.outcome}", result.succeeded)
        return (result as DecodeResult.Success).capture
    }

    /**
     * Advances every counter, standing in for time the emulator will not pass.
     *
     * An emulator never truly suspends, so its kernel wakelocks stay at zero however long the
     * test waits. Advancing them here proves durability and arithmetic; it does not claim to
     * be a measurement of the device, and the report says so.
     */
    private fun advance(
        capture: com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture,
        byMillis: Long,
        byCount: Long,
    ) = capture.copy(
        metadata = capture.metadata.copy(
            captureElapsedRealtimeMillis = capture.metadata.captureElapsedRealtimeMillis + 60_000L,
        ),
        kernelWakelocks = capture.kernelWakelocks.map {
            it.copy(totalTimeMillis = it.totalTimeMillis + byMillis, count = it.count + byCount)
        },
        partialWakelocks = capture.partialWakelocks.map {
            it.copy(totalTimeMillis = it.totalTimeMillis + byMillis, count = it.count + byCount)
        },
    )

    /** A battery session and snapshot for the counters to belong to. */
    private suspend fun seedSession(db: BattInsightDatabase, sessionId: UUID): UUID {
        val snapshotId = UUID.randomUUID()
        val time = CaptureTime(ElapsedRealtime(android.os.SystemClock.elapsedRealtime()), System.currentTimeMillis(), 0)
        val observation = BatteryObservation(
            time = time,
            bootIdentity = BOOT,
            status = BatteryStatus.DISCHARGING,
            plug = PlugSource.NONE,
            level = 100,
            scale = 100,
            health = BatteryHealth.GOOD,
            trigger = SessionTrigger.APP_START,
        )
        val snapshot = BatterySnapshot(
            id = snapshotId,
            sessionId = sessionId,
            bootIdentity = BOOT,
            time = time,
            trigger = SessionTrigger.APP_START,
            battery = observation,
            counterGeneration = GENERATION,
            counterSource = CounterSource.NONE,
        )
        db.sessionDao().upsertSnapshots(listOf(Mappers.toEntity(snapshot)))
        db.sessionDao().upsertSessions(
            listOf(
                Mappers.toEntity(
                    BatterySession(
                        id = sessionId,
                        type = SessionType.DISCHARGE,
                        start = snapshot,
                        latest = snapshot,
                        end = null,
                        endReason = SessionBoundaryReason.NONE,
                        counterGeneration = GENERATION,
                    ),
                ),
            ),
        )
        return snapshotId
    }

    private companion object {
        const val TAG = "BattInsightCounters"
        const val M = "COUNTER_PROOF"
        const val CAPTURE_FILE = "counter-capture.checkin"
        val GENERATION = CounterGeneration(1)
        val BOOT = BootIdentity.Kernel("counter-proof-boot")
        val WINDOW = AggregationWindow.SINCE_CHARGED
    }
}

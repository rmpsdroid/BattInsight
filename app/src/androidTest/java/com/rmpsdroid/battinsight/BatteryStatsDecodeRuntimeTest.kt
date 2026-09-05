package com.rmpsdroid.battinsight

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rmpsdroid.battinsight.batterystats.CaptureMetadata
import com.rmpsdroid.battinsight.batterystats.CheckinDecoder
import com.rmpsdroid.battinsight.batterystats.DecodeResult
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production decoder running on the platform it decodes.
 *
 * The JVM tests prove the parser is correct against captures taken earlier. This proves the
 * same code produces the same answers when it runs on Android 16 itself -- real ART, real
 * UTF-8 decoding, real memory limits, a real 900 KB payload.
 *
 * The capture is taken by the harness with a read-only `dumpsys batterystats -c` over ADB and
 * pushed into this application's own external files directory, which an app may read without
 * any permission. It is not committed: it is a live dump of the emulator's package list and
 * wakelocks, and it is deleted after the run.
 *
 * Nothing here changes device state. No battery simulation, no reset, no root, no app-op
 * change, and no permission grant.
 */
@RunWith(AndroidJUnit4::class)
class BatteryStatsDecodeRuntimeTest {

    @Test
    fun theProductionDecoderReadsALiveAndroid16Capture() {
        val file = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            CAPTURE,
        )
        assumeTrue(
            "no capture pushed; run tools/android16-decode-check.sh",
            file.isFile && file.length() > 0,
        )
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

        assertTrue("expected a decode, was ${result.outcome}", result.succeeded)
        val capture = (result as DecodeResult.Success).capture

        // Reported for the harness to compare against its own independent count. Counts and
        // versions only -- never names, never packages, never payload content.
        Log.i(TAG, "$MARKER bytes=${payload.size}")
        Log.i(TAG, "$MARKER checkinVersion=${capture.version.checkinVersion}")
        Log.i(TAG, "$MARKER parcelVersion=${capture.version.parcelVersion}")
        Log.i(TAG, "$MARKER recordFormat=${capture.version.recordFormatVersion}")
        Log.i(TAG, "$MARKER kwl=${capture.kernelWakelockCount}")
        Log.i(TAG, "$MARKER kwlActive=${capture.activeKernelWakelocks.size}")
        Log.i(TAG, "$MARKER wl=${capture.partialWakelockCount}")
        Log.i(TAG, "$MARKER uidMappings=${capture.uidPackages.size}")
        Log.i(TAG, "$MARKER history=${capture.historyLineCount}")
        Log.i(TAG, "$MARKER unsupportedTagTypes=${capture.unsupportedTags.size}")
        Log.i(TAG, "$MARKER warnings=${capture.warnings.size}")

        assertEquals("Android 16 reports checkin version 36", 36, capture.version.checkinVersion)
        assertEquals(9, capture.version.recordFormatVersion)
        assertTrue("a real capture carries history", capture.historyLineCount > 0)
        assertTrue("and kernel wakelocks", capture.kernelWakelockCount > 0)
    }

    private companion object {
        const val TAG = "BattInsightDecode"
        const val MARKER = "DECODE_CHECK"
        const val CAPTURE = "runtime-capture.checkin"
    }
}

package com.rmpsdroid.battinsight.persistence

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.BatteryStatsCapture
import com.rmpsdroid.battinsight.batterystats.CaptureMetadata
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.batterystats.PartialWakelockStat
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How much disk a session's counters actually cost.
 *
 * Measured against a file-backed database with realistic row counts -- 68 kernel wakelocks and
 * 315 application wakelocks, the numbers taken from the real Android 16 capture -- rather than
 * multiplying a guessed row width. The point is not to optimise anything; it is to know the
 * order of magnitude before deciding that a bounded model is sufficient, and to notice if a
 * later change makes it grow by a factor nobody intended.
 *
 * The assertions are deliberately loose ceilings. A test that pinned an exact byte count would
 * fail on any SQLite page-size change and teach nobody anything; these fail only if storage
 * grows by an order of magnitude, which is the thing worth being told about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CounterStorageSizeTest {

    @Test
    fun `a session's counters cost kilobytes, not megabytes`() = runTest {
        val file = File.createTempFile("battinsight-size", ".db").also { it.delete() }
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BattInsightDatabase::class.java,
            file.absolutePath,
        ).addMigrations(*ALL_MIGRATIONS).build()
        val store = RoomCounterStore(db.counterDao())

        // Force the file into existence before measuring the empty case.
        db.counterDao().captureCount()
        val empty = file.length()

        val sessionId = seedSession(db, 1)
        store.store(realisticCapture(), sessionId, null, GEN, BOOT, "cap-baseline")
        val withBaseline = file.length()

        store.store(realisticCapture(elapsed = 61_000L), sessionId, null, GEN, BOOT, "cap-latest")
        val withLatest = file.length()

        // Nine more sessions, each with a baseline and a latest.
        repeat(9) { i ->
            val s = seedSession(db, i + 2)
            store.store(realisticCapture(), s, null, GEN, BOOT, "b-$i")
            store.store(realisticCapture(elapsed = 61_000L), s, null, GEN, BOOT, "l-$i")
        }
        val tenSessions = file.length()

        println(
            "STORAGE empty=${empty / 1024}KB baseline=${withBaseline / 1024}KB " +
                "baseline+latest=${withLatest / 1024}KB tenSessions=${tenSessions / 1024}KB " +
                "perSession=${(tenSessions - empty) / 10 / 1024}KB",
        )

        // 383 counter rows per capture, two captures per session. Well under a megabyte each
        // would still be far more than expected; this catches a change of magnitude.
        val perSession = (tenSessions - empty) / 10
        assertTrue("a session cost $perSession bytes", perSession < 1_000_000)
        assertTrue("and it is not free either, so the measurement is real", perSession > 1_000)

        db.close()
        file.delete()
    }

    /**
     * A hundred refreshes cost about what two captures cost.
     *
     * Logical rows are what the bounded model controls. The *file* may not shrink, because
     * SQLite keeps freed pages for reuse rather than returning them to the filesystem, and
     * nothing here runs VACUUM automatically -- a vacuum rewrites the whole database, which is
     * not something to do behind a user's back on a phone.
     */
    @Test
    fun `repeated refresh does not grow the file without bound`() = runTest {
        val file = File.createTempFile("battinsight-churn", ".db").also { it.delete() }
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BattInsightDatabase::class.java,
            file.absolutePath,
        ).addMigrations(*ALL_MIGRATIONS).build()
        val store = RoomCounterStore(db.counterDao())

        val sessionId = seedSession(db, 1)
        store.store(realisticCapture(), sessionId, null, GEN, BOOT, "cap-0")
        store.store(realisticCapture(elapsed = 2_000L), sessionId, null, GEN, BOOT, "cap-1")
        val afterTwo = file.length()

        repeat(98) { i ->
            store.store(
                realisticCapture(elapsed = 3_000L + i * 1_000L),
                sessionId, null, GEN, BOOT, "cap-${i + 2}",
            )
        }
        val afterHundred = file.length()

        println(
            "CHURN afterTwoCaptures=${afterTwo / 1024}KB afterHundredRefreshes=" +
                "${afterHundred / 1024}KB captures=${store.captureCount()} " +
                "rows=${store.counterRowCounts()}",
        )

        assertTrue("logical captures stay bounded", store.captureCount() == 2)
        assertTrue(
            "the file must not grow with refreshes: $afterTwo -> $afterHundred",
            afterHundred < afterTwo * 3,
        )

        db.close()
        file.delete()
    }

    // ------------------------------------------------------------------------ helpers

    private suspend fun seedSession(db: BattInsightDatabase, n: Int): String {
        val sessionId = UUID(0L, n.toLong())
        val snapshotId = UUID(1L, n.toLong())
        db.sessionDao().upsertSnapshots(
            listOf(Mappers.toEntity(fullSnapshot(id = snapshotId, sessionId = sessionId))),
        )
        db.sessionDao().upsertSessions(
            listOf(
                Mappers.toEntity(
                    activeSession(
                        id = sessionId,
                        start = fullSnapshot(id = snapshotId, sessionId = sessionId),
                    ),
                ),
            ),
        )
        return sessionId.toString()
    }

    /** Row counts taken from the real Android 16 capture: 68 kernel, 315 application. */
    private fun realisticCapture(elapsed: Long = 1_000L) = BatteryStatsCapture(
        metadata = CaptureMetadata(
            sourceFormat = SourceFormat.CHECKIN,
            sourceFormatVersion = 9,
            captureElapsedRealtimeMillis = elapsed,
            captureWallClockMillis = 1_700_000_000_000L + elapsed,
            backendKind = BackendIdentity.Kind.SHELL,
            platformVersion = "16",
            payloadByteCount = 957_358,
            payloadHash = null,
            truncated = false,
        ),
        version = CheckinVersionBlock(9, 36, 215L, "BE2A.250530.026.D1", "BE2A.250530.026.D1"),
        kernelWakelocks = (1..68).map {
            KernelWakelockStat("kernel_wakelock_name_$it", it * 100L, it.toLong(), WINDOW)
        },
        partialWakelocks = (1..315).map {
            PartialWakelockStat(
                10_000 + it,
                "*job*/com.example.package$it/com.example.SomeJobServiceName",
                it * 50L,
                it.toLong(),
                WINDOW,
            )
        },
        uidPackages = emptyList(),
        unsupportedTags = emptyMap(),
        historyLineCount = 45_262,
        warnings = emptyList(),
    )

    private companion object {
        val WINDOW = AggregationWindow.SINCE_CHARGED
        val GEN = CounterGeneration(1)
        val BOOT = BootIdentity.Kernel("boot-under-test")
    }
}

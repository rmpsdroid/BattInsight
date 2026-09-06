package com.rmpsdroid.battinsight

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import com.rmpsdroid.battinsight.app.BattInsightTheme
import com.rmpsdroid.battinsight.app.DetailUiState
import com.rmpsdroid.battinsight.app.SessionDetailScreen
import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.batterystats.CheckinVersionBlock
import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.batterystats.KernelWakelockStat
import com.rmpsdroid.battinsight.batterystats.StoredCounterCapture
import com.rmpsdroid.battinsight.chart.BatteryChartMapper
import com.rmpsdroid.battinsight.chart.CounterChartMapper
import com.rmpsdroid.battinsight.chart.CounterFamily
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.history.CaptureSummary
import com.rmpsdroid.battinsight.history.CounterAvailability
import com.rmpsdroid.battinsight.history.HistoryPresentation
import com.rmpsdroid.battinsight.history.SessionDetail
import com.rmpsdroid.battinsight.history.SessionHistoryRow
import com.rmpsdroid.battinsight.history.SessionProvenance
import com.rmpsdroid.battinsight.series.BatterySeriesBuilder
import com.rmpsdroid.battinsight.series.BatterySeriesPoint
import com.rmpsdroid.battinsight.series.CounterSeriesBuilder
import com.rmpsdroid.battinsight.history.BatteryLevel
import com.rmpsdroid.battinsight.session.BatteryStatus
import com.rmpsdroid.battinsight.session.BootIdentity
import com.rmpsdroid.battinsight.session.CounterGeneration
import com.rmpsdroid.battinsight.session.PlugSource
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The chart sections on a real device.
 *
 * The interesting correctness — which points connect, what a refusal renders as — is asserted
 * on the JVM against render primitives, where it can be checked exactly. These tests cover what
 * only a real composition can show: that the sections appear, that their explanations reach the
 * screen, and that they survive a dark theme and a 1.5× font scale.
 *
 * **All series here are synthetic fixtures.** They exercise renderer behaviour and make no
 * claim about real battery drain on any device.
 */
class ChartUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val cadence = 5L * 60_000L

    // ------------------------------------------------------------------- normal rendering

    @Test
    fun aConnectedSeriesShowsTheTrendSection() {
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(loaded(points(80, 79, 78, 77, 76)), onBack = {})
            }
        }

        compose.onNodeWithText("Battery trend").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Latest").performScrollTo().assertIsDisplayed()
        // "80%" legitimately appears twice -- the session summary reports the starting level
        // and the chart legend repeats it. Asserting "exactly one" would be asserting that the
        // summary does not exist.
        compose.onAllNodesWithText("80%").onFirst().assertExists()
        compose.onAllNodesWithText("76%").onFirst().assertExists()
        compose.onNodeWithText("A break means no reading was taken", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aBreakIsExplainedInWordsRatherThanOnlyDrawn() {
        // A Canvas is invisible to a screen reader, so a gap that exists only as absent pixels
        // does not exist for everyone.
        val far = listOf(
            point(0, 80),
            point(cadence, 79),
            point(cadence * 12, 60),
            point(cadence * 13, 59),
        )
        compose.setContent { MaterialTheme { SessionDetailScreen(loaded(far), onBack = {}) } }

        compose.onNodeWithText("Not observed", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No readings were taken", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aProcessRestartSaysSoRatherThanShowingAPlainGap() {
        val restarted = listOf(
            point(0, 80),
            point(cadence, 79),
            point(cadence * 12, 60, trigger = SessionTrigger.APP_START),
            point(cadence * 13, 59),
        )
        compose.setContent { MaterialTheme { SessionDetailScreen(loaded(restarted), onBack = {}) } }

        compose.onNodeWithText("BattInsight stopped running", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRetentionBreakSaysTheReadingsWereTakenButNotKept() {
        val model = BatteryChartMapper.map(
            BatterySeriesBuilder.build(
                SESSION,
                listOf(point(cadence * 20, 60), point(cadence * 21, 59)),
                cadence,
                evictedThroughElapsedMillis = cadence * 19,
            ),
        )
        compose.setContent {
            MaterialTheme { SessionDetailScreen(loadedWith(model), onBack = {}) }
        }

        compose.onNodeWithText("no longer kept", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    // --------------------------------------------------------------------- sparse states

    @Test
    fun aSingleObservationSaysThereIsNoTrendRatherThanDrawingOne() {
        compose.setContent { MaterialTheme { SessionDetailScreen(loaded(points(80)), onBack = {}) } }

        compose.onNodeWithText("Not enough continuous observations", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anEmptySeriesSaysNothingWasSampledRatherThanShowingAnEmptyFrame() {
        compose.setContent {
            MaterialTheme { SessionDetailScreen(loaded(emptyList()), onBack = {}) }
        }

        compose.onNodeWithText("No sampled battery history yet", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun samplesWithoutALevelAreDistinguishedFromNoSamplesAtAll() {
        // "Nothing was sampled" and "samples exist but carried no level" are different facts,
        // and neither of them is 0%.
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(loaded(listOf(point(0, null), point(cadence, null))), onBack = {})
            }
        }

        compose.onNodeWithText("Battery level was unavailable", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aReadingWithNoLevelIsExplainedAndNotDrawnThrough() {
        // Phase 9C.1. All three readings are one temporally continuous segment, and before the
        // fix the chart drew 80 -> 78 straight through the reading with no level. The screen
        // must now say what happened, and must not claim the level stayed the same or was zero.
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(
                    loaded(listOf(point(0, 80), point(cadence, null), point(cadence * 2, 78))),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Battery trend").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Level unavailable", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("did not report a battery level", substring = true)
            .performScrollTo().assertIsDisplayed()
        // Not a gap, and not zero.
        compose.onNodeWithText("No readings were taken", substring = true).assertDoesNotExistSafely()
        compose.onNodeWithText("0%").assertDoesNotExistSafely()
    }

    // -------------------------------------------------------------------------- counters

    @Test
    fun aComparableCounterIntervalIsDescribedAsAccumulation() {
        // The wording must not imply the wakelock ran continuously; the evidence is a
        // difference between two cumulative readings.
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(loadedWithCounters(kernelDurations = listOf(0L, 120_000L)), onBack = {})
            }
        }

        compose.onNodeWithText("Wakelock activity over time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("accumulated across that whole period", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aMeasuredZeroSaysItIsAMeasurementNotMissingData() {
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(loadedWithCounters(kernelDurations = listOf(500L, 500L)), onBack = {})
            }
        }

        compose.onNodeWithText("that is a measurement, not missing data", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRefusedIntervalExplainsItselfInsteadOfShowingZero() {
        // 100 -> 50 is a counter decrease: the interval must be refused, and must not appear
        // as a zero-height bar that reads like "nothing happened".
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(loadedWithCounters(kernelDurations = listOf(100_000L, 50_000L)), onBack = {})
            }
        }

        compose.onNodeWithText("restarted", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("that is a measurement, not missing data", substring = true)
            .assertDoesNotExistSafely()
    }

    @Test
    fun aSingleCaptureSaysThereIsNoIntervalToCompare() {
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(loadedWithCounters(kernelDurations = listOf(500L)), onBack = {})
            }
        }

        compose.onNodeWithText("no interval to compare", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------------ theme and scaling

    @Test
    fun theChartRendersInTheApplicationThemeIncludingDark() {
        // BattInsightTheme resolves dark mode from the device. What matters here is that the
        // renderer takes its colours from MaterialTheme rather than hard-coding a palette that
        // would fight the theme -- so it must compose without an explicit colour anywhere.
        compose.setContent {
            BattInsightTheme { SessionDetailScreen(loaded(points(80, 79, 78)), onBack = {}) }
        }

        compose.onNodeWithText("Battery trend").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theChartSurvivesALargeFontScale() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 1.5f),
            ) {
                MaterialTheme { SessionDetailScreen(loaded(points(80, 79, 78)), onBack = {}) }
            }
        }

        compose.onNodeWithText("Battery trend").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Breaks").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun threeHundredObservationsRenderWithoutFailing() {
        // The battery cap is 300, so this is the worst case the renderer must survive. Chart
        // height is fixed, so a long session must not produce a ten-screen graph.
        val many = (0 until 300).map { point(it * cadence, 100 - it / 3) }
        compose.setContent { MaterialTheme { SessionDetailScreen(loaded(many), onBack = {}) } }

        // The legend reports endpoints rather than a raw count, so the count is asserted
        // through the model that produced the drawing instead of hunting for it on screen.
        compose.onNodeWithText("Battery trend").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Breaks").performScrollTo().assertIsDisplayed()
        val model = BatteryChartMapper.map(BatterySeriesBuilder.build(SESSION, many, cadence))
        assertEquals(300, model.summary.observationCount)
        assertEquals(1, model.segments.size)
    }

    @Test
    fun existingDetailSectionsSurviveWhenThereIsNoSeriesAtAll() {
        // A session recorded before Phase 9B has no series. The rest of the screen must be
        // exactly as useful as it was.
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(
                    DetailUiState.Loaded(
                        detail = detail(),
                        formatWallClock = { "12:00" },
                        resolvePackage = { null },
                        batteryChart = null,
                        kernelChart = null,
                        applicationChart = null,
                        formatDuration = HistoryPresentation::duration,
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Session").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("How this period began and ended").performScrollTo().assertIsDisplayed()
    }

    // --------------------------------------------------------------------------- helpers

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistSafely() =
        runCatching { assertDoesNotExist() }.getOrElse { }

    private fun points(vararg percents: Int) =
        percents.mapIndexed { i, p -> point(i * cadence, p) }

    private fun point(
        elapsed: Long,
        percent: Int?,
        trigger: SessionTrigger = SessionTrigger.PERIODIC,
        boot: BootIdentity = BootIdentity.Kernel("boot-a"),
    ) = BatterySeriesPoint(
        elapsedRealtimeMillis = elapsed,
        wallClockMillis = 1_700_000_000_000L + elapsed,
        utcOffsetMinutes = 330,
        bootIdentity = boot,
        level = percent,
        scale = 100,
        status = BatteryStatus.DISCHARGING,
        plug = PlugSource.NONE,
        temperatureDeciCelsius = 251,
        voltageMilliVolts = 4123,
        chargeCounterMicroAmpHours = 3_210_000L,
        trigger = trigger,
    )

    private fun loaded(points: List<BatterySeriesPoint>) = loadedWith(
        BatteryChartMapper.map(BatterySeriesBuilder.build(SESSION, points, cadence)),
    )

    private fun loadedWith(model: com.rmpsdroid.battinsight.chart.BatteryChartModel) =
        DetailUiState.Loaded(
            detail = detail(),
            formatWallClock = { "12:00" },
            resolvePackage = { null },
            batteryChart = model,
            kernelChart = null,
            applicationChart = null,
            formatDuration = HistoryPresentation::duration,
        )

    /** Builds a counter series from cumulative kernel durations, one capture each. */
    private fun loadedWithCounters(kernelDurations: List<Long>): DetailUiState.Loaded {
        val captures = kernelDurations.mapIndexed { i, millis ->
            StoredCounterCapture(
                captureId = "c$i",
                batterySessionId = SESSION,
                batterySnapshotId = null,
                sourceFormat = SourceFormat.CHECKIN,
                backendKind = BackendIdentity.Kind.SHELL,
                version = CheckinVersionBlock(9, 36, 215L, "BUILD.A", "BUILD.A"),
                platformChanged = false,
                checkinVersionVerified = true,
                captureElapsedRealtimeMillis = i * 600_000L,
                captureWallClockMillis = 1_700_000_000_000L + i * 600_000L,
                counterGeneration = CounterGeneration(3),
                bootIdentity = BootIdentity.Kernel("boot-a"),
                payloadByteCount = 900_000,
                warningCount = 0,
                kernelWakelocks = listOf(
                    KernelWakelockStat("bt_read", millis, 1L, AggregationWindow.SINCE_CHARGED),
                ),
                partialWakelocks = emptyList(),
            )
        }
        val series = CounterSeriesBuilder.build(SESSION, captures)
        return DetailUiState.Loaded(
            detail = detail(),
            formatWallClock = { "12:00" },
            resolvePackage = { null },
            batteryChart = null,
            kernelChart = CounterChartMapper.map(series, CounterFamily.KERNEL) {
                HistoryPresentation.unavailableReason(it)
            },
            applicationChart = null,
            formatDuration = HistoryPresentation::duration,
        )
    }

    private fun detail() = SessionDetail(
        row = SessionHistoryRow(
            sessionId = SESSION,
            type = SessionType.DISCHARGE,
            isActive = false,
            startWallClockMillis = 1_700_000_000_000L,
            endWallClockMillis = 1_700_003_600_000L,
            durationMillis = 3_600_000L,
            startBattery = BatteryLevel(80, 100),
            endBattery = BatteryLevel(76, 100),
            startTrigger = SessionTrigger.POWER_DISCONNECTED,
            endReason = SessionBoundaryReason.POWER_TRANSITION,
            counters = CounterAvailability.NoCapture,
        ),
        provenance = SessionProvenance(
            startTrigger = SessionTrigger.POWER_DISCONNECTED,
            endReason = SessionBoundaryReason.POWER_TRANSITION,
            startObserved = true,
            counterGeneration = 1L,
            snapshotSchemaVersion = 1,
            bootIdentityLabel = "11111111",
        ),
        captures = null,
        kernelDeltas = emptyList(),
        partialDeltas = emptyList(),
        unavailableReason = null,
        continuityDetail = null,
    )

    private companion object {
        const val SESSION = "00000000-0000-0000-0000-0000000000aa"
    }
}

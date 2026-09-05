package com.rmpsdroid.battinsight

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.rmpsdroid.battinsight.app.DetailUiState
import com.rmpsdroid.battinsight.app.HistoryUiState
import com.rmpsdroid.battinsight.app.SessionDetailScreen
import com.rmpsdroid.battinsight.app.SessionHistoryScreen
import com.rmpsdroid.battinsight.batterystats.CounterDeltaReason
import com.rmpsdroid.battinsight.batterystats.KernelWakelockDelta
import com.rmpsdroid.battinsight.batterystats.PartialWakelockDelta
import com.rmpsdroid.battinsight.batterystats.AggregationWindow
import com.rmpsdroid.battinsight.collection.BackendIdentity
import com.rmpsdroid.battinsight.collection.SourceFormat
import com.rmpsdroid.battinsight.history.BatteryLevel
import com.rmpsdroid.battinsight.history.CaptureSummary
import com.rmpsdroid.battinsight.history.CounterAvailability
import com.rmpsdroid.battinsight.history.SessionDetail
import com.rmpsdroid.battinsight.history.SessionHistoryRow
import com.rmpsdroid.battinsight.history.SessionProvenance
import com.rmpsdroid.battinsight.session.SessionBoundaryReason
import com.rmpsdroid.battinsight.session.SessionTrigger
import com.rmpsdroid.battinsight.session.SessionType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The history screens, composed and looked at.
 *
 * Driven by hand-built states rather than by a database. What is under test here is what the
 * screen *says*, and feeding it a repository would make every case require a fixture to be
 * persisted first -- slower, and it would test the repository twice.
 *
 * The cases that matter most are the ones where saying the wrong thing would mislead someone
 * about their own device: a refused comparison must show no figures at all, and an
 * unavailable value must never read as zero.
 */
class HistoryUiTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------- history

    @Test
    fun emptyHistoryExplainsItselfRatherThanShowingNothing() {
        compose.setContent {
            MaterialTheme {
                SessionHistoryScreen(HistoryUiState.Empty(null), {}, {}, {})
            }
        }

        compose.onNodeWithText("No battery periods recorded yet").assertIsDisplayed()
    }

    /**
     * An unreadable store is not an empty one.
     *
     * The two look identical if both render as "nothing here", and the second one would be
     * telling a user their history is gone when it is not.
     */
    @Test
    fun anUnreadableStoreSaysSoAndPromisesNothingWasDeleted() {
        compose.setContent {
            MaterialTheme {
                SessionHistoryScreen(HistoryUiState.Empty("SQLiteException"), {}, {}, {})
            }
        }

        compose.onNodeWithText("History could not be read").assertIsDisplayed()
        compose.onNode(hasText("Nothing has been deleted.", substring = true)).assertIsDisplayed()
    }

    @Test
    fun populatedHistoryShowsRowsAndOpensDetail() {
        var opened: String? = null
        compose.setContent {
            MaterialTheme {
                SessionHistoryScreen(
                    state = HistoryUiState.Loaded(
                        rows = listOf(
                            row("s1", SessionType.DISCHARGE, active = true),
                            row("s2", SessionType.CHARGE, active = false),
                        ),
                        totalCount = 2,
                        canLoadMore = false,
                        formatWallClock = { "5 Sep 2026, 09:00" },
                    ),
                    onOpenSession = { opened = it },
                    onLoadMore = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("On battery — now").assertIsDisplayed()
        compose.onNodeWithText("Charging").assertIsDisplayed()
        compose.onNodeWithText("On battery — now").performClick()
        assertEquals("s1", opened)
    }

    @Test
    fun loadMoreAppearsOnlyWhenThereIsMore() {
        var loadedMore = false
        compose.setContent {
            MaterialTheme {
                SessionHistoryScreen(
                    HistoryUiState.Loaded(listOf(row("s1")), 90, true) { "t" },
                    {}, { loadedMore = true }, {},
                )
            }
        }

        compose.onNodeWithText("Show older periods").performScrollTo().performClick()
        assertEquals(true, loadedMore)
    }

    /** No identifiers on ordinary rows. */
    @Test
    fun aHistoryRowDoesNotShowTheSessionIdentifier() {
        val id = "0f8b1c2d-3e4f-5061-7283-94a5b6c7d8e9"
        compose.setContent {
            MaterialTheme {
                SessionHistoryScreen(
                    HistoryUiState.Loaded(listOf(row(id)), 1, false) { "t" }, {}, {}, {},
                )
            }
        }

        compose.onAllNodesWithText(id, substring = true).assertCountEquals(0)
    }

    // -------------------------------------------------------------------- detail

    @Test
    fun aComparableSessionShowsKernelAndAppDeltaRows() {
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(
                    DetailUiState.Loaded(
                        detail = detail(
                            kernel = listOf(
                                KernelWakelockDelta("bt_read_wake_lock", WINDOW, 120_000L, 12L),
                            ),
                            partial = listOf(
                                PartialWakelockDelta(10234, "SyncLock", WINDOW, 45_000L, 3L),
                            ),
                            availability = CounterAvailability.DeltaAvailable(1, 1, false),
                            baselineIsLatest = false,
                        ),
                        formatWallClock = { "5 Sep 2026, 09:00" },
                        resolvePackage = { null },
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNode(hasText("bt_read_wake_lock", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("+2 min 0 s over +12", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("UID 10234", substring = true)).performScrollTo().assertIsDisplayed()
    }

    /**
     * The Phase 7B.1 rule, on screen.
     *
     * A refused pair shows an explanation and **no** figures — not even the counters that
     * would look normal. Showing a partial list would be showing numbers already established
     * as untrustworthy.
     */
    @Test
    fun aDecreasedCounterShowsNoDeltaListAtAll() {
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(
                    DetailUiState.Loaded(
                        detail = detail(
                            kernel = emptyList(),
                            partial = emptyList(),
                            availability = CounterAvailability.DeltaUnavailable(
                                CounterDeltaReason.COUNTER_DECREASED,
                            ),
                            baselineIsLatest = false,
                            unavailable = CounterDeltaReason.COUNTER_DECREASED,
                        ),
                        formatWallClock = { "5 Sep 2026, 09:00" },
                        resolvePackage = { null },
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Unavailable").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("accounting restarted", substring = true)).assertIsDisplayed()
        // Neither delta section may be rendered.
        compose.onAllNodesWithText("Kernel wakelocks — what changed").assertCountEquals(0)
        compose.onAllNodesWithText("App wakelocks — what changed").assertCountEquals(0)
        // And nothing may leak the enum name.
        compose.onAllNodesWithText("COUNTER_DECREASED", substring = true).assertCountEquals(0)
    }

    @Test
    fun aZeroDeltaIsPresentedAsAMeasurementNotAsMissingData() {
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(
                    DetailUiState.Loaded(
                        detail = detail(
                            kernel = emptyList(),
                            partial = emptyList(),
                            availability = CounterAvailability.DeltaAvailable(0, 0, true),
                            baselineIsLatest = false,
                        ),
                        formatWallClock = { "5 Sep 2026, 09:00" },
                        resolvePackage = { null },
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNode(hasText("No increase was recorded", substring = true))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("That is a measurement, not", substring = true)).assertIsDisplayed()
    }

    @Test
    fun aSessionWithOnlyABaselineInvitesAnotherCapture() {
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(
                    DetailUiState.Loaded(
                        detail = detail(
                            kernel = emptyList(),
                            partial = emptyList(),
                            availability = CounterAvailability.BaselineOnly,
                            baselineIsLatest = true,
                        ),
                        formatWallClock = { "5 Sep 2026, 09:00" },
                        resolvePackage = { null },
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNode(hasText("Capture again to see what changed", substring = true))
            .performScrollTo().assertIsDisplayed()
    }

    /** A resolved name never replaces the UID. */
    @Test
    fun aResolvedPackageNameAppearsAlongsideTheUid() {
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(
                    DetailUiState.Loaded(
                        detail = detail(
                            kernel = emptyList(),
                            partial = listOf(
                                PartialWakelockDelta(10234, "SyncLock", WINDOW, 45_000L, 3L),
                            ),
                            availability = CounterAvailability.DeltaAvailable(0, 1, false),
                            baselineIsLatest = false,
                        ),
                        formatWallClock = { "5 Sep 2026, 09:00" },
                        resolvePackage = { "com.example.app" },
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNode(hasText("com.example.app (UID 10234)", substring = true))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("may not be what ran under it", substring = true))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backFromDetailInvokesTheCallback() {
        var back = false
        compose.setContent {
            MaterialTheme { SessionDetailScreen(DetailUiState.Missing, onBack = { back = true }) }
        }
        // Waits for the composition to attach before querying it. Every other case here
        // happens to scroll first, which waits implicitly; this one renders a single card and
        // queried immediately, and in a full-suite run -- behind other classes that launch
        // their own activities -- it intermittently found no hierarchy at all. It passed
        // alone and failed in sequence, which is the signature of a missing wait rather than
        // a broken screen.
        compose.waitForIdle()

        compose.onNodeWithText("Back").performClick()
        assertEquals(true, back)
    }

    /**
     * A long wakelock name wraps rather than widening the layout.
     *
     * One 200-character tag must not make the whole screen scroll sideways, so the row is
     * asserted to still be found and the surrounding fields still displayed.
     */
    @Test
    fun aVeryLongWakelockNameDoesNotBreakTheLayout() {
        val long = "*job*/com.example.verylongpackagename/" + "x".repeat(180)
        compose.setContent {
            MaterialTheme {
                SessionDetailScreen(
                    DetailUiState.Loaded(
                        detail = detail(
                            kernel = listOf(KernelWakelockDelta(long, WINDOW, 1_000L, 1L)),
                            partial = emptyList(),
                            availability = CounterAvailability.DeltaAvailable(1, 0, false),
                            baselineIsLatest = false,
                        ),
                        formatWallClock = { "5 Sep 2026, 09:00" },
                        resolvePackage = { null },
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Diagnostics").performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------------------ helpers

    private fun row(
        id: String,
        type: SessionType = SessionType.DISCHARGE,
        active: Boolean = true,
    ) = SessionHistoryRow(
        sessionId = id,
        type = type,
        isActive = active,
        startWallClockMillis = 1_700_000_000_000L,
        endWallClockMillis = if (active) null else 1_700_000_600_000L,
        durationMillis = 600_000L,
        startBattery = BatteryLevel(80, 100),
        endBattery = BatteryLevel(62, 100),
        startTrigger = SessionTrigger.POWER_DISCONNECTED,
        endReason = if (active) SessionBoundaryReason.NONE else SessionBoundaryReason.POWER_TRANSITION,
        counters = CounterAvailability.NoCapture,
    )

    private fun detail(
        kernel: List<KernelWakelockDelta>,
        partial: List<PartialWakelockDelta>,
        availability: CounterAvailability,
        baselineIsLatest: Boolean,
        unavailable: CounterDeltaReason? = null,
    ) = SessionDetail(
        row = row("s1").copy(counters = availability),
        provenance = SessionProvenance(
            startTrigger = SessionTrigger.POWER_DISCONNECTED,
            endReason = SessionBoundaryReason.NONE,
            startObserved = true,
            counterGeneration = 1L,
            snapshotSchemaVersion = 1,
            bootIdentityLabel = "11111111",
        ),
        captures = CaptureSummary(
            baselineWallClockMillis = 1_700_000_000_000L,
            latestWallClockMillis = 1_700_000_600_000L,
            baselineIsLatest = baselineIsLatest,
            sourceFormat = SourceFormat.CHECKIN,
            backendKind = BackendIdentity.Kind.SHELL,
            checkinVersion = 36,
            recordFormatVersion = 9,
            checkinVersionVerified = true,
            platformChanged = false,
            warningCount = 1,
            payloadByteCount = 957_358,
        ),
        kernelDeltas = kernel,
        partialDeltas = partial,
        unavailableReason = unavailable,
        continuityDetail = if (unavailable == CounterDeltaReason.COUNTER_DECREASED) {
            "A cumulative counter went down (kernel wakelock bt_read_wake_lock)"
        } else {
            null
        },
    )

    private companion object {
        val WINDOW = AggregationWindow.SINCE_CHARGED
    }
}

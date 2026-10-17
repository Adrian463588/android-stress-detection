package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.domain.engine.AudioTimelineReviewMarker
import com.stressdetector.mobile.domain.engine.LivePitchObservation
import com.stressdetector.mobile.domain.engine.LivePitchStatus
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.StressDetectorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Numeric presentation fixtures only; these tests do not establish audio/model/device accuracy. */
@RunWith(AndroidJUnit4::class)
class VerbatimPitchPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactPanelWrapsAt200PercentFontAndExposesHzAndWindow() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                StressDetectorTheme {
                    Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                        VerbatimPitchPanel(
                            listOf(observation(waveform = listOf(0.25f, 0.75f))),
                            false,
                            Modifier.testTag("pitch-panel")
                        )
                    }
                }
            }
        }
        val panelBounds = compose.onNodeWithTag("pitch-panel").fetchSemanticsNode().boundsInRoot
        listOf(
            "Amplitudo suara",
            "Envelope peak PCM terukur · skala 0–1 dicerminkan ± · bukan F0",
            "Pitch suara",
            "220,0 Hz",
            "Rerata geometrik 1 detik · openSMILE F0"
        ).forEach { label ->
            val node = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("Text must remain inside the panel", bounds.left >= panelBounds.left && bounds.right <= panelBounds.right)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Expected measurable text", layouts.isNotEmpty())
            assertTrue("Text must wrap without clipping", layouts.none { it.hasVisualOverflow })
        }
        compose.onNodeWithContentDescription("Grafik pitch suara; 1 observasi bernada", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Grafik envelope amplitudo PCM", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pausedPanelHidesCurrentHzAndLabelsTheChartAsHistory() {
        compose.setContent {
            ScrollablePanel { VerbatimPitchPanel(listOf(observation()), isPaused = true) }
        }
        compose.onNodeWithText("Pengukuran dijeda").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("220,0 Hz").assertDoesNotExist()
        compose.onNodeWithContentDescription("Grafik pitch suara", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Riwayat sebelum jeda"))
    }

    @Test
    fun collectingAndUnavailableStatesNeverCarryThePreviousPitchValue() {
        var history by mutableStateOf(emptyList<LivePitchObservation>())
        compose.setContent { ScrollablePanel { VerbatimPitchPanel(history, isPaused = false) } }
        compose.onNodeWithText("Mengumpulkan audio").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Envelope amplitudo belum tersedia.").assertExists()
        compose.onNodeWithContentDescription("Grafik pitch suara", substring = true).assertDoesNotExist()
        val states = mapOf(
            LivePitchStatus.UNVOICED to "Tanpa suara bernada",
            LivePitchStatus.QUALITY_REJECTED to "Kualitas audio tidak memadai",
            LivePitchStatus.NATIVE_UNAVAILABLE to "Pitch tidak tersedia",
            LivePitchStatus.PROCESSING_GAP to "Data pitch terputus"
        )
        states.forEach { (status, label) ->
            compose.runOnIdle { history = listOf(observation(), observation(start = 1_000, frequency = null, status = status)) }
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("220,0 Hz").assertDoesNotExist()
            compose.onNodeWithContentDescription("Status: $label", substring = true).assertExists()
        }
    }

    @Test
    fun tailIsBoundedAndUsesActualAudioOffsets() {
        val history = List(65) { index -> observation(start = index * 1_000L) }
        compose.setContent { ScrollablePanel { VerbatimPitchPanel(history, isPaused = false) } }
        compose.onNodeWithText("Observasi terakhir 1:04–1:05").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Grafik pitch suara; 60 observasi bernada", substring = true).assertExists()
        compose.onNodeWithContentDescription("Grafik pitch suara;", substring = true)
            .assert(
                SemanticsMatcher("Actual elapsed range") { node ->
                    node.config[SemanticsProperties.ContentDescription].any { it.contains("Waktu rekaman 0:05 sampai 1:05") }
                }
            )
    }

    @Test
    fun envelopeUsesMeasuredPeakHeightsAndMirrorsThemIndependentlyOfPitch() {
        compose.setContent {
            ScrollablePanel { VerbatimPitchPanel(listOf(observation(waveform = listOf(0.25f, 0.75f))), false) }
        }
        val bitmap = compose.onNodeWithContentDescription("Grafik envelope amplitudo PCM", substring = true)
            .performScrollTo().captureToImage()
        val pixels = bitmap.toPixelMap()
        val left = bitmap.width / 4
        val right = bitmap.width * 3 / 4
        val top = bitmap.height / 4
        val bottom = bitmap.height * 3 / 4
        assertNotEquals(AppPalette.Primary.toArgb(), pixels[left, top].toArgb())
        assertNotEquals(AppPalette.Primary.toArgb(), pixels[left, bottom].toArgb())
        assertEquals(AppPalette.Primary.toArgb(), pixels[right, top].toArgb())
        assertEquals(AppPalette.Primary.toArgb(), pixels[right, bottom].toArgb())
        compose.onNodeWithText("220,0 Hz").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun measuredSilenceAndMissingEnvelopeRemainDistinctFromUnvoicedF0() {
        var history by mutableStateOf(
            listOf(observation(frequency = null, status = LivePitchStatus.UNVOICED, waveform = listOf(0f, 0f)))
        )
        compose.setContent { ScrollablePanel { VerbatimPitchPanel(history, false) } }
        compose.onNodeWithContentDescription("1 jendela PCM terukur · peak maksimum 0,0", substring = true).assertExists()
        compose.onNodeWithContentDescription("Grafik pitch suara; 0 observasi bernada", substring = true).assertExists()
        compose.onNodeWithText("Tanpa suara bernada").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { history = listOf(history.single().copy(waveform = emptyList())) }
        compose.onNodeWithContentDescription("Grafik envelope amplitudo PCM; Envelope amplitudo belum tersedia.", substring = true)
            .assertExists()
        compose.onNodeWithContentDescription("Tanpa envelope: 1 jendela", substring = true).assertExists()
        compose.onNodeWithText("220,0 Hz").assertDoesNotExist()
    }

    @Test
    fun missingSecondsAreNotFilledWithEnvelopeOrPitch() {
        compose.setContent {
            ScrollablePanel {
                VerbatimPitchPanel(
                    listOf(observation(waveform = listOf(0.75f)), observation(start = 2_000, waveform = listOf(0.75f))),
                    false
                )
            }
        }
        val bitmap = compose.onNodeWithContentDescription("Grafik envelope amplitudo PCM", substring = true)
            .performScrollTo().captureToImage()
        val pixels = bitmap.toPixelMap()
        assertNotEquals(AppPalette.Primary.toArgb(), pixels[bitmap.width / 2, bitmap.height / 4].toArgb())
        compose.onNodeWithContentDescription("Grafik pitch suara", substring = true)
            .assert(
                SemanticsMatcher("Observed time gap remains explicit") { node ->
                    node.config[SemanticsProperties.ContentDescription].any { it.contains("jeda waktu: 1") }
                }
            )
    }

    @Test
    fun viewportKeepsTheWholeArchivePageAndBoundsLiveByElapsedTimeRatherThanRowCount() {
        val densePage = List(120) { index -> observation(start = 120_000L + index * 500L, duration = 500L) }
        val archive = requireNotNull(verbatimSignalViewport(densePage, isArchive = true))
        assertEquals(120, archive.observations.size)
        assertEquals(120_000L, archive.startOffsetMs)
        assertEquals(180_000L, archive.endOffsetMs)
        assertEquals(0.5f, archive.offsetFraction(150_000L), 0f)
        val sparse = listOf(observation(), observation(start = 119_000L))
        val live = requireNotNull(verbatimSignalViewport(sparse, isArchive = false))
        assertEquals(60_000L, live.startOffsetMs)
        assertEquals(120_000L, live.endOffsetMs)
        assertEquals(listOf(sparse.last()), live.observations)
        assertEquals(1, live.timeGapCount)
        assertEquals(0.25f, live.offsetFraction(75_000L), 0f)
        assertEquals("0:00.250", verbatimSignalTime(250L))
        assertNull(verbatimSignalViewport(emptyList(), isArchive = true))
    }

    @Test
    fun explicitArchiveBoundsKeepLongProcessingGapsAndEmptyPagesOnTheRequestedMinute() {
        val gap = observation(start = 10_000L, duration = 110_000L, frequency = null, status = LivePitchStatus.PROCESSING_GAP)
        val history = listOf(observation(), gap, observation(start = 120_000L))
        val page = requireNotNull(verbatimSignalViewport(history, isArchive = true, archiveStartOffsetMs = 60_000L))
        assertEquals(60_000L, page.startOffsetMs)
        assertEquals(120_000L, page.endOffsetMs)
        assertEquals(listOf(gap), page.observations)
        assertSame(gap, page.observations.single())
        assertEquals(10_000L, page.observations.single().startOffsetMs)
        assertEquals(1, page.timeGapCount)
        assertEquals(0f, page.offsetFraction(60_000L), 0f)
        assertEquals(1f, page.offsetFraction(120_000L), 0f)
        val empty = requireNotNull(verbatimSignalViewport(emptyList(), isArchive = true, archiveStartOffsetMs = 60_000L))
        assertEquals(60_000L, empty.startOffsetMs)
        assertEquals(120_000L, empty.endOffsetMs)
        assertTrue(empty.observations.isEmpty())
        assertEquals(1, empty.timeGapCount)
        val live = requireNotNull(verbatimSignalViewport(listOf(observation()), isArchive = false, archiveStartOffsetMs = 60_000L))
        assertEquals(0L, live.startOffsetMs)
        assertEquals(1_000L, live.endOffsetMs)
    }

    @Test
    fun changingArchivePageRecomputesBothAxesForTheSameUnchangedLongGap() {
        var pageStart by mutableStateOf(0L)
        val history = listOf(
            observation(start = 10_000L, duration = 110_000L, frequency = null, status = LivePitchStatus.PROCESSING_GAP)
        )
        compose.setContent {
            ScrollablePanel {
                VerbatimPitchPanel(history, false, isArchive = true, archiveStartOffsetMs = pageStart)
            }
        }
        compose.onAllNodesWithContentDescription("Waktu rekaman 0:00 sampai 1:00", substring = true).assertCountEquals(2)
        compose.runOnIdle { pageStart = 60_000L }
        compose.onAllNodesWithContentDescription("Waktu rekaman 1:00 sampai 2:00", substring = true).assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Waktu rekaman 0:10 sampai 1:10", substring = true).assertCountEquals(0)
    }

    @Test
    fun emptyArchivePageKeepsItsAxesAndOnlyReviewsMarkersInsideItsExplicitBounds() {
        val inside = marker().copy(
            id = "inside-page",
            startOffsetMs = 90_000L,
            endOffsetMs = 91_000L,
            label = "Marker dalam halaman",
            explanation = "Bukti marker pada halaman tanpa observasi.",
            playbackOffsetMs = 90_000L
        )
        val outside = marker().copy(id = "outside-page", label = "Marker sebelum halaman")
        val nextPage = inside.copy(id = "next-page", startOffsetMs = 120_000L, endOffsetMs = 121_000L)
        var callback: AudioTimelineReviewMarker? = null
        compose.setContent {
            ScrollablePanel {
                VerbatimPitchPanel(
                    emptyList(),
                    false,
                    markers = listOf(outside, inside, nextPage),
                    isArchive = true,
                    onSelectMarker = { callback = it },
                    archiveStartOffsetMs = 60_000L
                )
            }
        }
        compose.onAllNodesWithContentDescription("Waktu rekaman 1:00 sampai 2:00", substring = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("Tanpa envelope: 0 jendela · jeda waktu: 1.", substring = true).assertExists()
        compose.onNodeWithContentDescription("Grafik pitch suara; 0 observasi bernada", substring = true).assertExists()
        compose.onNodeWithText("Mengumpulkan audio").assertDoesNotExist()
        compose.onNodeWithText("Tinjau marker 1/1: Marker dalam halaman · 1:30–1:31")
            .performScrollTo().performClick()
        compose.onNodeWithText(inside.explanation).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertNull(callback) }
        compose.onNodeWithText("Dengarkan marker", substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertSame(inside, callback) }
    }

    @Test
    fun liveMarkersDiscloseActualEvidenceWithoutCallingPlayback() {
        val marker = marker()
        var callback: AudioTimelineReviewMarker? = null
        compose.setContent {
            ScrollablePanel {
                VerbatimPitchPanel(
                    listOf(observation(waveform = listOf(0.25f))),
                    false,
                    markers = listOf(marker),
                    onSelectMarker = { callback = it }
                )
            }
        }
        compose.onNodeWithText(marker.explanation).assertDoesNotExist()
        compose.onNodeWithText("Tinjau marker 1/1:", substring = true)
            .performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(marker.explanation).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sumber: ${marker.source}", substring = true).assertExists()
        compose.onNodeWithText("Dengarkan marker", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Grafik envelope amplitudo PCM", substring = true)
            .assert(
                SemanticsMatcher("Amplitude exposes selected marker") { node ->
                    node.config[SemanticsProperties.ContentDescription].any { it.contains("Marker terpilih: ${marker.label}") }
                }
            )
        compose.onNodeWithContentDescription("Grafik pitch suara", substring = true)
            .assert(
                SemanticsMatcher("F0 exposes the same selected marker") { node ->
                    node.config[SemanticsProperties.ContentDescription].any { it.contains("Marker terpilih: ${marker.label}") }
                }
            )
        compose.runOnIdle { assertNull(callback) }
    }

    @Test
    fun archiveOnlyCallsSelectionOnExplicitListenWithTheUnchangedPlaybackMarker() {
        val marker = marker().copy(playbackOffsetMs = 12_345L)
        var callback: AudioTimelineReviewMarker? = null
        compose.setContent {
            ScrollablePanel {
                VerbatimPitchPanel(
                    listOf(observation()),
                    false,
                    markers = listOf(marker),
                    isArchive = true,
                    playbackOffsetMs = 500L,
                    onSelectMarker = { callback = it }
                )
            }
        }
        compose.onNodeWithText("Tinjau marker 1/1:", substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertNull(callback) }
        compose.onNodeWithText("Posisi putar 0:00.500").assertExists()
        compose.onNodeWithText("Dengarkan marker · 0:12.345").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dengarkan marker", substring = true)
            .performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertSame(marker, callback) }
    }

    @Test
    fun archiveMarkerWithoutAudioMappingOffersEvidenceWithoutListen() {
        compose.setContent {
            ScrollablePanel {
                VerbatimPitchPanel(listOf(observation()), false, markers = listOf(marker().copy(playbackOffsetMs = null)), isArchive = true)
            }
        }
        compose.onNodeWithText("Tinjau marker 1/1:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Audio marker tidak tersedia.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dengarkan marker", substring = true).assertDoesNotExist()
    }

    @Test
    fun compactLargeFontMarkerActionsWrapAndReviewStaysBounded() {
        val markers = List(120) { index ->
            marker().copy(id = "marker-$index", label = "Catatan manual untuk ditinjau bersama konteks percakapan $index")
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                StressDetectorTheme {
                    Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                        VerbatimPitchPanel(listOf(observation()), false, Modifier.testTag("pitch-panel"), markers, isArchive = true)
                    }
                }
            }
        }
        val action = compose.onNodeWithText("Tinjau marker 1/120:", substring = true)
            .performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        action.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
        val panel = compose.onNodeWithTag("pitch-panel").fetchSemanticsNode().boundsInRoot
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= panel.left && bounds.right <= panel.right)
        compose.onNodeWithText("Tinjau marker 2/120:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Marker berikutnya").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Tinjau marker 2/120:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(markers[1].explanation).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun chartConnectionsRequireAvailableContiguousWindowsFromOneSession() {
        val first = observation()
        val next = observation(start = 1_000)
        assertTrue(pitchObservationsAreContiguous(first, next))
        assertFalse(pitchObservationsAreContiguous(first, observation(start = 2_000)))
        assertFalse(pitchObservationsAreContiguous(first, observation(start = 500)))
        assertFalse(pitchObservationsAreContiguous(first, next.copy(sessionId = "another-ui-fixture")))
        val gap = observation(start = 1_000, frequency = null, status = LivePitchStatus.PROCESSING_GAP)
        assertFalse(pitchObservationsAreContiguous(first, gap))
        assertFalse(pitchObservationsAreContiguous(gap, observation(start = 2_000)))
        val unvoiced = gap.copy(status = LivePitchStatus.UNVOICED)
        assertFalse(pitchObservationsAreContiguous(first, unvoiced))
        assertFalse(pitchObservationsAreContiguous(unvoiced, observation(start = 2_000)))
    }

    @Composable
    private fun ScrollablePanel(content: @Composable () -> Unit) {
        StressDetectorTheme { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { content() } }
    }

    private fun marker() = AudioTimelineReviewMarker(
        id = "manual-ui-fixture",
        startOffsetMs = 250L,
        endOffsetMs = 750L,
        label = "Catatan manual",
        explanation = "Catatan yang diberikan pengguna untuk ditinjau.",
        source = "Manual",
        playbackOffsetMs = 250L
    )

    private fun observation(
        start: Long = 0L,
        frequency: Double? = 220.0,
        status: LivePitchStatus = LivePitchStatus.AVAILABLE,
        duration: Long = 1_000L,
        waveform: List<Float> = emptyList()
    ) = LivePitchObservation(
        sessionId = "pitch-ui-fixture",
        startOffsetMs = start,
        endOffsetMs = start + duration,
        frequencyHz = frequency,
        status = status,
        voicedFrameCount = if (frequency == null) 0 else 50,
        frameCount = 100,
        waveform = waveform
    )
}

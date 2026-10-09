package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.domain.engine.LivePitchObservation
import com.stressdetector.mobile.domain.engine.LivePitchStatus
import com.stressdetector.mobile.ui.StressDetectorTheme
import org.junit.Assert.assertFalse
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
                        VerbatimPitchPanel(listOf(observation()), false, Modifier.testTag("pitch-panel"))
                    }
                }
            }
        }
        val panelBounds = compose.onNodeWithTag("pitch-panel").fetchSemanticsNode().boundsInRoot
        listOf("Pitch suara", "220,0 Hz", "Rerata geometrik 1 detik · openSMILE F0").forEach { label ->
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
    }

    @Test
    fun pausedPanelHidesCurrentHzAndLabelsTheChartAsHistory() {
        compose.setContent {
            StressDetectorTheme { VerbatimPitchPanel(listOf(observation()), isPaused = true) }
        }
        compose.onNodeWithText("Pengukuran dijeda").assertIsDisplayed()
        compose.onNodeWithText("220,0 Hz").assertDoesNotExist()
        compose.onNodeWithContentDescription("Grafik pitch suara", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Riwayat sebelum jeda"))
    }

    @Test
    fun collectingAndUnavailableStatesNeverCarryThePreviousPitchValue() {
        var history by mutableStateOf(emptyList<LivePitchObservation>())
        compose.setContent { StressDetectorTheme { VerbatimPitchPanel(history, isPaused = false) } }
        compose.onNodeWithText("Mengumpulkan audio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Grafik pitch suara", substring = true).assertDoesNotExist()
        val states = mapOf(
            LivePitchStatus.UNVOICED to "Tanpa suara bernada",
            LivePitchStatus.QUALITY_REJECTED to "Kualitas audio tidak memadai",
            LivePitchStatus.NATIVE_UNAVAILABLE to "Pitch tidak tersedia",
            LivePitchStatus.PROCESSING_GAP to "Data pitch terputus"
        )
        states.forEach { (status, label) ->
            compose.runOnIdle { history = listOf(observation(), observation(start = 1_000, frequency = null, status = status)) }
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText("220,0 Hz").assertDoesNotExist()
            compose.onNodeWithContentDescription("Status: $label", substring = true).assertExists()
        }
    }

    @Test
    fun tailIsBoundedAndUsesActualAudioOffsets() {
        val history = List(65) { index -> observation(start = index * 1_000L) }
        compose.setContent { StressDetectorTheme { VerbatimPitchPanel(history, isPaused = false) } }
        compose.onNodeWithText("Observasi terakhir 1:04–1:05").assertIsDisplayed()
        compose.onNodeWithContentDescription("Grafik pitch suara; 60 observasi bernada", substring = true).assertExists()
        compose.onNodeWithContentDescription("Waktu rekaman 0:05 sampai 1:05", substring = true).assertExists()
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

    private fun observation(start: Long = 0L, frequency: Double? = 220.0, status: LivePitchStatus = LivePitchStatus.AVAILABLE) =
        LivePitchObservation(
            sessionId = "pitch-ui-fixture",
            startOffsetMs = start,
            endOffsetMs = start + 1_000L,
            frequencyHz = frequency,
            status = status,
            voicedFrameCount = if (frequency == null) 0 else 50,
            frameCount = 100
        )
}

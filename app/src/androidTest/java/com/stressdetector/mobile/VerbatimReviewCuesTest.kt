package com.stressdetector.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.VerbatimCueAnalyzer
import com.stressdetector.mobile.ui.screens.VerbatimReviewText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VerbatimReviewCuesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cueHasAccessibleLabelAndSeeksUtteranceAtLargeFontScale() {
        val segment = SpeechSegment("test", "Speaker_0", 1_200, 4_000, 1_200, 4_000, "mungkin besok", 0.9f)
        val analysis = VerbatimCueAnalyzer().analyze(segment)
        val cue = analysis.cues.single()
        var position: Long? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                        VerbatimReviewText(analysis, onPlayCue = { position = it })
                    }
                }
            }
        }
        compose.onNodeWithText("1 cue leksikal").assertIsDisplayed().performClick()
        compose.onNodeWithText("${cue.type.label} · «${cue.matchedText}»").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Putar ujaran untuk ${cue.type.label}: ${cue.matchedText}")
            .performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1_200L, position)
    }

    @Test fun liveCueExplanationCanBeReadWhilePlaybackIsUnavailable() {
        val analysis = VerbatimCueAnalyzer().analyze(SpeechSegment("live", "Unknown", 0, 1_000, 0, 1_000, "maybe tomorrow", 0.8f, "en"))
        val cue = analysis.cues.single()
        compose.setContent {
            MaterialTheme { VerbatimReviewText(analysis, onPlayCue = { error("Live playback must remain disabled") }, canPlay = false) }
        }
        compose.onNodeWithText("1 cue leksikal").performClick()
        compose.onNodeWithText("${cue.type.label} · «${cue.matchedText}»").performClick()
        compose.onNodeWithText(cue.explanation).assertIsDisplayed()
        compose.onNodeWithContentDescription("Putar ujaran untuk ${cue.type.label}: ${cue.matchedText}").assertIsNotEnabled()
    }
}

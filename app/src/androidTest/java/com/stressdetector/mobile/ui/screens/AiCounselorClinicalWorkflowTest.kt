package com.stressdetector.mobile.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.stressdetector.mobile.domain.engine.CompletionReason
import com.stressdetector.mobile.domain.engine.CounselingInteractionMode
import com.stressdetector.mobile.domain.engine.PlanReviewStatus
import com.stressdetector.mobile.ui.viewmodels.UiChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AiCounselorClinicalWorkflowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatComposerAcceptsInputAndDeliversSendWithoutExtraChipClutter() {
        var text by mutableStateOf("")
        var sentCount = 0

        composeRule.setContent {
            MaterialTheme {
                ChatComposer(
                    state = ChatComposerState(
                        inputText = text,
                        onInputChanged = { text = it },
                        isGenerating = false,
                        voiceBusy = false,
                        onCancelGeneration = {},
                        onSend = { sentCount++ },
                        onVoice = {}
                    )
                )
            }
        }

        composeRule.onNodeWithText("Konseling").assertDoesNotExist()
        composeRule.onNodeWithText("Asesmen").assertDoesNotExist()
        composeRule.onNodeWithText("Rencana").assertDoesNotExist()
        composeRule.onNodeWithText("Panjang: Standar").assertDoesNotExist()
        composeRule.onNodeWithText("Pengingat").assertDoesNotExist()

        composeRule.onNodeWithText("Pertanyaan").performTextInput("Bahas hasil TIU-5")
        composeRule.onNodeWithContentDescription("Kirim pertanyaan").performClick()
        assertEquals(1, sentCount)
    }

    @Test
    fun truncatedPlanningBubbleExposesContinuationAndPlanReviewWithoutReminderButton() {
        var continuedId: String? = null
        var reviewedStatus: PlanReviewStatus? = null
        var revisionTargetId: String? = null
        val msg = UiChatMessage(
            id = "plan-1",
            sender = "counselor",
            content = "1. Ringkasan TIU-5\n2. Intervensi REBT",
            timestampMs = 1L,
            interactionMode = CounselingInteractionMode.COUNSELOR_PLANNING,
            isTruncated = true,
            completionReason = CompletionReason.OUTPUT_LIMIT_REACHED,
            planReviewStatus = PlanReviewStatus.PENDING
        )

        composeRule.setContent {
            MaterialTheme {
                ChatMessageBubble(
                    message = msg,
                    actions = ChatMessageActions(
                        isSpeaking = false,
                        isVoiceStopping = false,
                        onSpeak = { _, _ -> },
                        onStopSpeaking = {},
                        onOpenCitation = {},
                        onOpenCrisisSupport = {},
                        onContinueResponse = { continuedId = it },
                        onReviewPlan = { _, status -> reviewedStatus = status },
                        onRequestPlanRevision = { revisionTargetId = it }
                    )
                )
            }
        }

        composeRule.onNodeWithText("Buat Pengingat").assertDoesNotExist()
        composeRule.onNodeWithText("Reminder").assertDoesNotExist()

        composeRule.onNodeWithText("Lanjutkan jawaban").assertIsDisplayed().performClick()
        assertEquals("plan-1", continuedId)

        composeRule.onNodeWithText("Setujui").performClick()
        assertEquals(PlanReviewStatus.ACCEPTED, reviewedStatus)

        composeRule.onNodeWithText("Revisi").performClick()
        assertEquals("plan-1", revisionTargetId)
        assertTrue(msg.interactionMode == CounselingInteractionMode.COUNSELOR_PLANNING)
    }
}

package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounselorPromptBudgetTest {
    private val asset = PromptAsset(
        id = "test",
        version = "1",
        systemPrompt = "AI Counselor",
        userPromptTemplate = "Pertanyaan: {user_message}"
    )

    @Test
    fun newestHistoryIsPreferredWhenOlderTurnDoesNotFit() {
        val request = CounselorGenerationRequest(
            promptAsset = asset,
            userMessage = "Saya masih membutuhkan bantuan.",
            ragMode = RagMode.GENERAL_SAFE,
            retrievedCitations = emptyList(),
            stressHistoryContext = "",
            recentHistory = listOf(
                ConversationTurn("user", "old ".repeat(200)),
                ConversationTurn("assistant", "jawaban terbaru")
            )
        )

        val prompt = CounselorPromptBuilder(
            PromptBudget(maxUserPromptTokens = 180, maxHistoryTokens = 90)
        ).userPrompt(request)

        assertTrue(prompt.contains("jawaban terbaru"))
        assertFalse(prompt.contains("old old old"))
        assertTrue(prompt.contains("Saya masih membutuhkan bantuan."))
    }

    @Test
    fun currentMessageTailIsNeverDroppedByHistoryBudget() {
        val request = CounselorGenerationRequest(
            promptAsset = asset,
            userMessage = "awal ".repeat(100) + "TAIL-PRESERVED",
            ragMode = RagMode.GENERAL_SAFE,
            retrievedCitations = emptyList(),
            stressHistoryContext = "",
            recentHistory = listOf(ConversationTurn("assistant", "history"))
        )

        val prompt = CounselorPromptBuilder(
            PromptBudget(maxUserPromptTokens = 160, maxUserMessageTokens = 70, maxHistoryTokens = 60)
        ).userPrompt(request)

        assertTrue(prompt.contains("TAIL-PRESERVED"))
        assertTrue(ConservativeGemmaTokenCounter.count(prompt) <= 160)
    }
}

package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
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

    @Test
    fun combinedPromptLeavesRuntimeResponseReserveAndOnlyRegistersVisibleEvidence() {
        val citations = (1..8).map { index ->
            KnowledgeCitation("chunk-$index", "Panduan $index", "Bagian", "Coping aman. ".repeat(100), 0.9f)
        }
        val request = CounselorGenerationRequest(
            asset.copy(systemPrompt = "Panduan keselamatan. ".repeat(100)),
            "Tolong bantu saya ".repeat(100),
            RagMode.GROUNDED,
            citations,
            "Riwayat lokal. ".repeat(100),
            recentHistory = listOf(ConversationTurn("user", "Pesan lama. ".repeat(100)))
        )
        val prepared = CounselorPromptBuilder().prepare(request)
        assertTrue(prepared.estimatedInputTokens <= 1_600)
        assertTrue(prepared.systemInstruction.contains("PRIORITAS MUTLAK"))
        assertTrue(prepared.citationRegistry.bindings.isNotEmpty())
        assertTrue(prepared.citationRegistry.bindings.size < citations.size)
        citations.forEach { citation ->
            val binding = prepared.citationRegistry.bindings.firstOrNull { it.evidenceId == citation.citationId }
            assertEquals(binding != null, prepared.systemInstruction.contains("|${citation.citationId}]"))
        }
    }

    @Test
    fun truncationMarkerIsIncludedInsideTheBudget() {
        (1..60).forEach { limit ->
            assertTrue(ConservativeGemmaTokenCounter.count(ConservativeGemmaTokenCounter.take("kata ".repeat(100), limit)) <= limit)
        }
    }

    @Test
    fun recentUserContextSurvivesLongAssistantTurnAndCurrentQuestionIsLast() {
        val request = CounselorGenerationRequest(
            asset,
            "Tadi saya merasa apa?",
            RagMode.GENERAL_SAFE,
            emptyList(),
            "",
            recentHistory = listOf(
                ConversationTurn("user", "Saya merasa lelah hari ini."),
                ConversationTurn("assistant", "Saya siap mendengarkan. ".repeat(50))
            )
        )
        val prepared = CounselorPromptBuilder().prepare(request)
        assertTrue(prepared.userPrompt.contains("Saya merasa lelah hari ini."))
        assertTrue(prepared.userPrompt.indexOf("RIWAYAT TERBATAS") < prepared.userPrompt.indexOf("PERTANYAAN SAAT INI"))
        assertTrue(prepared.userPrompt.endsWith("Tadi saya merasa apa?"))
    }

    @Test
    fun explicitUserContextSurvivesFollowupAndLongUserTemplate() {
        val request = CounselorGenerationRequest(
            asset.copy(
                userPromptTemplate = "Pesan: {user_message}\nJawab langsung. Ikuti jumlah langkah yang diminta. " +
                    "Berikan langkah praktis non-klinis opsional, tanpa mengarang diagnosis, sumber, atau pengalaman."
            ),
            "Ingat apa yang membuat saya lelah tadi? Berikan hanya satu langkah journaling singkat.",
            RagMode.GENERAL_SAFE,
            emptyList(),
            "",
            recentHistory = listOf(
                ConversationTurn("user", "Saya lelah setelah revisi skripsi."),
                ConversationTurn("assistant", "Saran panjang. ".repeat(50)),
                ConversationTurn("user", "Tadi saya lelah karena apa? Sesuaikan satu langkah journaling dengan cerita saya."),
                ConversationTurn("assistant", "Jawaban sebelumnya. ".repeat(50))
            )
        )
        val prepared = CounselorPromptBuilder().prepare(request)
        assertTrue(prepared.userPrompt.contains("Saya lelah setelah revisi skripsi."))
        assertTrue(prepared.userPrompt.contains(request.userMessage))
        assertTrue(prepared.estimatedInputTokens <= 1_600)
    }

    @Test
    fun optionalContextAdmissionReportsOnlyTextActuallyIncluded() {
        val stressText = "Riwayat lokal. ".repeat(100)
        val request = CounselorGenerationRequest(asset, "Saya ingin bercerita", RagMode.GENERAL_SAFE, emptyList(), stressText)
        val prepared = CounselorPromptBuilder().prepare(request)
        if (prepared.admittedStressContext.isNotBlank()) {
            assertTrue(prepared.systemInstruction.contains(prepared.admittedStressContext))
            assertFalse(prepared.admittedStressContext == stressText)
        } else {
            assertFalse(prepared.systemInstruction.contains("KONTEKS STRES LOKAL"))
        }
        assertEquals(prepared.personaIncluded, prepared.systemInstruction.contains("PERSONA"))
    }
}

package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationTextAssemblerTest {
    @Test
    fun runtimeDeltasPreserveWhitespaceAndParagraphBoundaries() {
        val assembler = GenerationTextAssembler()
        listOf("Latihan:", "\n\n", "Tulis", " ", "satu hal.", "\n", "Pertanyaan:").forEach { text ->
            assembler.append(requireNotNull(counselorContentDelta(text)))
        }
        assertEquals("Latihan:\n\nTulis satu hal.\nPertanyaan:", assembler.currentText())
        assertNull(counselorContentDelta(""))
    }

    @Test
    fun deltaFragmentsAreAppendedBeforeApplyingOutputLimit() {
        val assembler = GenerationTextAssembler(maxChars = 10)

        assertEquals("Halo ", assembler.append(LlmGenerationState.Content("Halo ")))
        assertEquals("Halo selam", assembler.append(LlmGenerationState.Content("selamat pagi")))
        assertEquals("Halo selam", assembler.currentText())
    }

    @Test
    fun snapshotFragmentReplacesAccumulatedText() {
        val assembler = GenerationTextAssembler()

        assembler.append(LlmGenerationState.Content("partial"))

        assertEquals(
            "complete response",
            assembler.append(
                LlmGenerationState.Content(
                    text = "complete response",
                    mode = GenerationContentMode.SNAPSHOT
                )
            )
        )
    }

    @Test
    fun completeResponseSurvivesTheOldCharacterCutoff() {
        val assembler = GenerationTextAssembler()
        val response = "Kalimat lengkap untuk penjelasan asesmen. ".repeat(36).trim()

        assembler.append(LlmGenerationState.Content(response))

        assertEquals(AssembledAnswer(response, incomplete = false), assembler.finish())
    }

    @Test
    fun unfinishedMarkdownAfterCompleteSentenceIsRemoved() {
        val assembler = GenerationTextAssembler()
        assembler.append(LlmGenerationState.Content("Bahas hasil dengan konselor. [S1|ref-1]\n\n**Hal yang perlu diperh"))

        assertEquals(AssembledAnswer("Bahas hasil dengan konselor. [S1|ref-1]", incomplete = true), assembler.finish())
    }

    @Test
    fun overflowingResponseReturnsOnlyCompleteSentences() {
        val assembler = GenerationTextAssembler(maxChars = 35)
        assembler.append(LlmGenerationState.Content("Bahas skor. Lalu tanyakan kendala saat mengerjakan tes."))

        assertEquals(AssembledAnswer("Bahas skor.", incomplete = true), assembler.finish())
    }

    @Test
    fun finishPreservingPartialRetainsGeneratedContentAndReportsCompletionReason() {
        val assembler = GenerationTextAssembler(maxChars = 48)
        assembler.append(LlmGenerationState.Content("1. Ringkasan TIU-5. 2. Langkah latihan REBT harian"))

        val preserved = assembler.finishPreservingPartial()

        assertEquals(CompletionReason.OUTPUT_LIMIT_REACHED, preserved.reason)
        assertEquals(true, preserved.incomplete)
        assertEquals("1. Ringkasan TIU-5. 2. Langkah latihan REBT hari", preserved.text)
    }

    @Test
    fun mergeContinuationStitchesPartialTailWithoutDuplicatingOverlap() {
        val merged = GenerationTextAssembler.mergeContinuation(
            existing = "Teknik REBT membantu mengevaluasi keyakinan irasional yang memicu",
            continuation = "keyakinan irasional yang memicu kecemasan berlebih pada klien."
        )

        assertEquals(
            "Teknik REBT membantu mengevaluasi keyakinan irasional yang memicu kecemasan berlebih pada klien.",
            merged
        )
    }
}

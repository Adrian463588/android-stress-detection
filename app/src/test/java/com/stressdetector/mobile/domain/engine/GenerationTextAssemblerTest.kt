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
}

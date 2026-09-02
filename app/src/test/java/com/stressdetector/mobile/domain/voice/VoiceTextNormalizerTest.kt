package com.stressdetector.mobile.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextNormalizerTest {
    @Test
    fun `removes display markup citations urls and disclaimer`() {
        val result = VoiceTextNormalizer.normalize(
            "## Langkah\n\n- **Tarik napas** perlahan. [S1|panduan]\n" +
                "Lihat [sumber](https://example.test/a).\n\n" +
                "Catatan: respons ini adalah dukungan umum dan bukan diagnosis medis."
        )

        assertEquals("Langkah\nTarik napas perlahan.\nLihat sumber.", result)
    }

    @Test
    fun `bounds long speech at a sentence boundary when available`() {
        val result = VoiceTextNormalizer.normalize("Kalimat. ".repeat(500))

        assertTrue(result.length <= 1_200)
        assertTrue(result.endsWith('.'))
    }
}

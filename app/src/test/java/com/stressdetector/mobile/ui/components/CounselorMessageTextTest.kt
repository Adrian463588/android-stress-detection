package com.stressdetector.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CounselorMessageTextTest {
    @Test
    fun exactCitationBeforeNextNumberedItemKeepsSeparateReadableItems() {
        val blocks = parseCounselorMarkdown("1.Ambil jeda. [W1|web-1]2.Prioritaskan istirahat. [W2|web-2]3.Atur batasan.")
        assertEquals(
            listOf(
                CounselorTextBlock.ListItem("1.", "Ambil jeda. [W1|web-1]"),
                CounselorTextBlock.ListItem("2.", "Prioritaskan istirahat. [W2|web-2]"),
                CounselorTextBlock.ListItem("3.", "Atur batasan.")
            ),
            blocks
        )
    }

    @Test
    fun citationBeforeDecimalDoesNotInventAList() {
        assertEquals(
            listOf(CounselorTextBlock.Paragraph("Nilai [W1|web-1]1.25 tetap desimal.")),
            parseCounselorMarkdown("Nilai [W1|web-1]1.25 tetap desimal.")
        )
    }

    @Test
    fun numberedAdviceAfterColonDoesNotMergeWithItsIntroduction() {
        val blocks = parseCounselorMarkdown("Coba langkah berikut:1.Tulis perasaan.2.Catat kebutuhan.")
        assertEquals(
            listOf(
                CounselorTextBlock.Paragraph("Coba langkah berikut:"),
                CounselorTextBlock.ListItem("1.", "Tulis perasaan."),
                CounselorTextBlock.ListItem("2.", "Catat kebutuhan.")
            ),
            blocks
        )
    }

    @Test
    fun decimalNumbersAreNotInventedListItems() {
        assertEquals(
            listOf(CounselorTextBlock.Paragraph("Versi:1.2.0; durasi 2.5 menit.")),
            parseCounselorMarkdown("Versi:1.2.0; durasi 2.5 menit.")
        )
    }

    @Test
    fun joinsReadableParagraphsAndSeparatesInlineNumberedLists() {
        val blocks = parseCounselorMarkdown(
            "Saya sulit tidur.Meskipun perlu jeda.1.**Ciptakan Rutinitas Relaksasi:** " +
                "Lakukan kegiatan tenang selama menenangkan30-60 menit.2.**Batasi Layar:** " +
                "Hindari layar sebelum tidur."
        )

        assertEquals(3, blocks.size)
        assertEquals(
            "Saya sulit tidur. Meskipun perlu jeda.",
            (blocks[0] as CounselorTextBlock.Paragraph).text
        )
        assertEquals(
            listOf("1.", "2."),
            blocks.drop(1).map { (it as CounselorTextBlock.ListItem).marker }
        )
        assertEquals(
            "**Ciptakan Rutinitas Relaksasi:** Lakukan kegiatan tenang selama menenangkan 30-60 menit.",
            (blocks[1] as CounselorTextBlock.ListItem).text
        )
    }

    @Test
    fun rendersSupportedInlineFormattingWithoutRawMarkdownMarkers() {
        val tokens = parseCounselorInlineText(
            "**Ringkas** *catatan* `kunci` [S1|doc-1] [Baca](https://example.com)"
        )

        assertEquals(
            "Ringkas catatan kunci [S1] Baca",
            tokens.joinToString(separator = "") { it.text }
        )
        assertTrue(tokens.any { it.style == CounselorInlineStyle.Strong && it.text == "Ringkas" })
        assertTrue(tokens.any { it.style == CounselorInlineStyle.Emphasis && it.text == "catatan" })
        assertTrue(tokens.any { it.style == CounselorInlineStyle.Code && it.text == "kunci" })
        assertTrue(tokens.any { it.style == CounselorInlineStyle.Citation && it.text == "[S1]" })
    }

    @Test
    fun preservesHeadingAndListContinuationBoundaries() {
        val blocks = parseCounselorMarkdown(
            "# Ringkasan\n\n- Poin pertama\n  lanjutan\n\n- Poin kedua"
        )

        assertEquals(
            listOf(
                CounselorTextBlock.Heading("Ringkasan"),
                CounselorTextBlock.ListItem("•", "Poin pertama lanjutan"),
                CounselorTextBlock.ListItem("•", "Poin kedua")
            ),
            blocks
        )
    }
}

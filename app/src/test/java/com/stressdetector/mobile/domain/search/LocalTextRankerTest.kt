package com.stressdetector.mobile.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTextRankerTest {
    @Test
    fun `ranker prefers Indonesian passage with query evidence`() {
        val relevant = "Teknik pernapasan dan coping dapat membantu mengelola stres."
        val unrelated = "Cuaca hari ini berawan dan kemungkinan hujan."

        assertTrue(
            LocalTextRanker.score("cara coping stres", relevant) >
                LocalTextRanker.score("cara coping stres", unrelated)
        )
    }

    @Test
    fun `best passage returns evidence excerpt rather than full page`() {
        val result = LocalTextRanker.bestPassage(
            query = "coping stres",
            text = "Informasi umum.\n\nCoping stres dapat mencakup istirahat dan dukungan sosial.",
            maxCharacters = 120
        )

        assertNotNull(result)
        assertEquals("Coping stres dapat mencakup istirahat dan dukungan sosial.", result?.text)
    }
}

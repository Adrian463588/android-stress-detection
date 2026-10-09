package com.stressdetector.mobile.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchContractsTest {
    @Test
    fun `automatic search contract bounds query and scraping`() {
        assertEquals(3, WebSearchLimits.MAX_SCRAPE_PAGES)
        assertEquals(2L * 1024L * 1024L, WebSearchLimits.MAX_RESPONSE_BYTES)
        assertEquals(240, WebSearchLimits.MAX_QUERY_CHARS)
        assertTrue(WebQuerySanitizer.sanitize("stress").isSuccess)
    }

    @Test
    fun `private context labels reject whole query including inline and JSON fields`() {
        listOf(
            "coping sessionId=private-session",
            "coping sourceSessionIds: [private-session]",
            "coping stressProbability=0.8 confidence=0.9",
            "coping raw PPG: samples",
            "coping waveform=values",
            "coping transcript: private words",
            "coping personaMarkdown=private profile",
            "coping stressHistoryContext: private history",
            "coping\nquality: private quality",
            "{\"query\":\"coping\",\"transcript\":\"private words\"}",
            "{\"query\":\"coping\", \"session_id\" : \"private-session\"}",
            "{\"query\":\"coping\",\"stress_probability\":0.8}",
            "coping " + "x".repeat(WebSearchLimits.MAX_QUERY_CHARS) + " transcript: private words"
        ).forEach { query ->
            assertTrue("Private context was admitted", WebQuerySanitizer.sanitize(query).isFailure)
        }
    }

    @Test
    fun `ordinary bounded questions retain query text`() {
        val question = "  How does sleep quality affect stress?  "
        assertEquals(question.trim(), WebQuerySanitizer.sanitize(question).getOrThrow())
        assertEquals("cara coping stres", WebQuerySanitizer.sanitize("cara\n coping\tstres").getOrThrow())
        assertEquals(WebSearchLimits.MAX_QUERY_CHARS, WebQuerySanitizer.sanitize("x".repeat(500)).getOrThrow().length)
        assertTrue(WebQuerySanitizer.sanitize(" \n\t ").isFailure)
    }
}

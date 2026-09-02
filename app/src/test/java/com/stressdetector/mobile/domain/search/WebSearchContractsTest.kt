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
}

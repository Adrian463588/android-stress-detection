package com.stressdetector.mobile.data.search

import com.stressdetector.mobile.domain.engine.CitationSource
import com.stressdetector.mobile.domain.search.ScrapedPage
import com.stressdetector.mobile.domain.search.WebPageScraper
import com.stressdetector.mobile.domain.search.WebSearchDiscovery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebGroundingGatewayImplTest {
    @Test
    fun `grounding preserves explicit page provenance and bounds citations`() = runTest {
        val scraper = RecordingScraper()
        val urls = (0 until 5).map { "https://example.com/$it" }
        val gateway = WebGroundingGatewayImpl(
            pageScraper = scraper,
            clock = { 99L },
            discovery = discoveryReturning(urls)
        )

        val result = gateway.retrieveAutomatically("apa itu coping")

        assertTrue(result.isSuccess)
        assertEquals(urls.take(3), scraper.urls)
        val grounding = result.getOrThrow()
        assertEquals("apa itu coping", grounding.query)
        assertEquals(99L, grounding.retrievedAtMillis)
        assertEquals(3, grounding.citations.size)
        grounding.citations.forEach { citation ->
            assertEquals(CitationSource.WEB, citation.sourceType)
            assertTrue(citation.sourceUrl!!.startsWith("https://"))
            assertTrue(citation.sourceDomain!!.isNotBlank())
            assertEquals(99L, citation.retrievedAtMillis)
            assertEquals(64, citation.contentHash!!.length)
        }
    }

    @Test
    fun `grounding fails when pages do not contain safe readable content`() = runTest {
        val scraper = object : WebPageScraper {
            override suspend fun scrapePages(urls: List<String>): Result<List<ScrapedPage>> =
                Result.success(listOf(ScrapedPage("http://example.com", "Unsafe", "text", 1L)))
        }

        val result = WebGroundingGatewayImpl(
            pageScraper = scraper,
            discovery = discoveryReturning(listOf("https://example.com"))
        ).retrieveAutomatically("query")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("valid"))
    }

    @Test
    fun `automatic grounding discovers sources without manual approval`() = runTest {
        val gateway = WebGroundingGatewayImpl(
            pageScraper = RecordingScraper(),
            clock = { 123L },
            discovery = discoveryReturning(listOf("https://example.com/coping"))
        )

        val result = gateway.retrieveAutomatically("apa itu coping")

        assertTrue(result.isSuccess)
        assertEquals("apa itu coping", result.getOrThrow().query)
        assertEquals(1, result.getOrThrow().citations.size)
    }

    private fun discoveryReturning(urls: List<String>) = object : WebSearchDiscovery {
        override suspend fun discover(query: String): Result<List<String>> = Result.success(urls)
    }

    private class RecordingScraper : WebPageScraper {
        var urls: List<String> = emptyList()

        override suspend fun scrapePages(urls: List<String>): Result<List<ScrapedPage>> {
            this.urls = urls
            return Result.success(
                urls.map { url ->
                    ScrapedPage(url, "Source", "Coping dapat membantu mengelola stres secara bertahap.", 99L)
                }
            )
        }
    }
}

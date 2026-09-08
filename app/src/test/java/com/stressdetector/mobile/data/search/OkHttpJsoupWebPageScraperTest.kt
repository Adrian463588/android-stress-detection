package com.stressdetector.mobile.data.search

import com.stressdetector.mobile.domain.search.WebSearchLimits
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpKsoupWebPageScraperTest {
    @Test
    fun `scraper parses only readable content and removes navigation elements`() = runTest {
        val client = scriptedClient(
            """
            <html><head><title>Article</title><style>.hidden{}</style></head>
            <body><nav>Menu</nav><article><h1>Title</h1><p>Useful content.</p><script>bad()</script></article></body>
            </html>
            """.trimIndent()
        )
        val scraper = OkHttpKsoupWebPageScraper(client, clock = { 7L })

        val result = scraper.scrapePages(
            listOf("https://example.com/article#tracking", "https://example.com/article")
        )

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        val page = result.getOrThrow().single()
        assertEquals("Article", page.title)
        assertEquals("Title Useful content.", page.text)
        assertEquals(7L, page.retrievedAtMillis)
        assertFalse(page.text.contains("bad"))
    }

    @Test
    fun `scraper rejects oversized response`() = runTest {
        val client = scriptedClient("x".repeat((WebSearchLimits.MAX_RESPONSE_BYTES + 1).toInt()))
        val result = OkHttpKsoupWebPageScraper(client).scrapePages(
            listOf("https://example.com/large")
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `unsafe URLs are rejected before request`() = runTest {
        val client = scriptedClient("<html><body>text</body></html>")
        val scraper = OkHttpKsoupWebPageScraper(client)

        val result = scraper.scrapePages(
            listOf(
                "http://example.com/article",
                "file:///data/data/app/audio.wav",
                "javascript:alert(1)",
                "https://localhost/private",
                "https://127.0.0.1/private",
                "https://user:password@example.com/"
            )
        )

        assertTrue(result.isFailure)
    }

    private fun scriptedClient(body: String, code: Int = 200): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        .build()
}

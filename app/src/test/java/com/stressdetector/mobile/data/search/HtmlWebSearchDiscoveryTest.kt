package com.stressdetector.mobile.data.search

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlWebSearchDiscoveryTest {
    @Test
    fun `only readable result anchors become bounded HTTPS candidates`() = runTest {
        val result = HtmlWebSearchDiscovery(OkHttpClient()) { url ->
            assertEquals("stress", url.queryParameter("q"))
            """
            <a href="https://support.google.com/websearch">navigation</a>
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fone">one</a>
            <a class="result__a" href="https://example.org/two">two</a>
            <a class="result__a" href="http://example.org/unsafe">unsafe</a>
            <a class="result__a" href="https://127.0.0.1/private">private</a>
            <a class="result__a" href="https://example.com/one">duplicate</a>
            """.trimIndent()
        }.discover("stress")
        assertEquals(listOf("https://example.com/one", "https://example.org/two"), result.getOrThrow())
    }

    @Test
    fun `javascript challenge and empty results fail rather than cite navigation`() = runTest {
        val result = HtmlWebSearchDiscovery(OkHttpClient()) { "<a href='https://support.google.com/websearch'>Enable JS</a>" }
            .discover("stress")
        assertTrue(result.isFailure)
        assertEquals("web_discovery_no_readable_results", result.exceptionOrNull()?.message)
    }
}

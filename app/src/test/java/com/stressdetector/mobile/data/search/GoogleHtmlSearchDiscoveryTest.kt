package com.stressdetector.mobile.data.search

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleHtmlSearchDiscoveryTest {
    @Test
    fun `automatic Google HTML discovery extracts bounded HTTPS results`() = runTest {
        val result = GoogleHtmlSearchDiscovery(OkHttpClient()) { _ ->
            """
            <html><body>
              <a href="/url?q=https%3A%2F%2Fexample.com%2Fone&sa=U">one</a>
              <a href="https://example.org/two">two</a>
              <a href="https://www.google.com/preferences">google</a>
            </body></html>
            """.trimIndent()
        }.discover("stress")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("https://example.com/one", "https://example.org/two"),
            result.getOrThrow()
        )
    }
}

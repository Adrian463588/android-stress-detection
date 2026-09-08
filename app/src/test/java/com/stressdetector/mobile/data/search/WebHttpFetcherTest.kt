package com.stressdetector.mobile.data.search

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test

class WebHttpFetcherTest {
    @Test
    fun `cross domain redirect is rejected after source approval`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("redirect")
                    .header("Location", "https://other.example/article")
                    .body("".toResponseBody())
                    .build()
            }
            .build()

        val result = runCatching {
            WebHttpFetcher(client, maxResponseBytes = 10_000, rateLimiter = DomainRateLimiter(0))
                .fetch("https://example.com/article".toHttpUrl(), approvedHost = "example.com")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("disetujui"))
    }
}

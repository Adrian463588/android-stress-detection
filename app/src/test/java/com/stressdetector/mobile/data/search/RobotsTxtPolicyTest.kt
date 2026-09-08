package com.stressdetector.mobile.data.search

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotsTxtPolicyTest {
    @Test
    fun `robots disallow blocks approved page`() = runTest {
        val client = scriptedClient("User-agent: *\nDisallow: /private")
        val result = runCatching {
            RobotsTxtPolicy(client).requirePermitted("https://example.com/private/article".toHttpUrl())
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("robots.txt"))
    }

    @Test
    fun `missing robots allows approved page`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("not found")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        RobotsTxtPolicy(client).requirePermitted("https://example.com/article".toHttpUrl())
    }

    private fun scriptedClient(body: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("ok")
                .body(body.toResponseBody("text/plain".toMediaType()))
                .build()
        }
        .build()
}

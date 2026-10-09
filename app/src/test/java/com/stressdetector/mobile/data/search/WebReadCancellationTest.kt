package com.stressdetector.mobile.data.search

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebReadCancellationTest {
    @Test
    fun `cancellation closes a call waiting for headers`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/robots.txt")).build())
            val reader = async(Dispatchers.IO) { call.readCancellable { it.code } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(5_000) { reader.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    @Test
    fun `cancellation remains attached while consuming a slow body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("a".repeat(1024)).throttleBody(1, 1, TimeUnit.SECONDS))
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/page")).build())
            val started = CompletableDeferred<Unit>()
            val finished = CountDownLatch(1)
            val reader = async(Dispatchers.IO) {
                call.readCancellable {
                    val body = requireNotNull(it.body)
                    body.source().require(1)
                    started.complete(Unit)
                    try {
                        readBoundedWebBody(body, 2048)
                    } finally {
                        finished.countDown()
                    }
                }
            }
            withTimeout(5_000) { started.await() }
            withTimeout(5_000) { reader.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue("Body read did not stop after cancellation", finished.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `shared bounded reader consumes and closes an ordinary response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("public page"))
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/page")).build())
            val text = withTimeout(5_000) {
                call.readCancellable { readBoundedWebBody(requireNotNull(it.body), 100) }
            }
            assertEquals("public page", text)
        }
    }
}

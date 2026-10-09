package com.stressdetector.mobile.data.search

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException

data class FetchedWebDocument(val finalUrl: HttpUrl, val html: String, val contentType: String?)

/** One bounded, redirect-checked HTTP boundary for all AI+ web reads. */
class WebHttpFetcher(
    private val client: OkHttpClient,
    private val maxResponseBytes: Long,
    private val rateLimiter: DomainRateLimiter = DomainRateLimiter()
) {
    // Redirects are validated one hop at a time against the user's approved host.
    private val redirectControlledClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetch(
        startUrl: HttpUrl,
        approvedHost: String = startUrl.host,
        allowXml: Boolean = false,
        crawlDelayMillis: Long? = null
    ): FetchedWebDocument = withContext(Dispatchers.IO) {
        var currentUrl = requireNotNull(SecureWebUrlPolicy.normalize(startUrl.toString())) {
            "URL web tidak aman."
        }
        require(currentUrl.host == approvedHost) { "URL tidak termasuk domain yang disetujui." }
        repeat(MAX_REDIRECTS + 1) { redirectAttempt ->
            coroutineContext.ensureActive()
            rateLimiter.awaitSlot(currentUrl.host, crawlDelayMillis)
            // Pin the address set checked as public for this hop, so OkHttp does
            // not perform a second DNS resolution vulnerable to rebinding.
            val approvedAddresses = SecureWebUrlPolicy.resolvePublicAddresses(currentUrl)
                ?: error("Host sumber web tidak menyelesaikan ke alamat publik.")
            coroutineContext.ensureActive()
            val fetched = newCall(currentUrl, allowXml, approvedAddresses).readCancellable {
                if (it.isRedirect) {
                    require(redirectAttempt < MAX_REDIRECTS) { "Terlalu banyak redirect web." }
                    val location = it.header("Location") ?: error("Redirect web tidak memiliki tujuan.")
                    currentUrl = requireNotNull(
                        SecureWebUrlPolicy.normalize(currentUrl.resolve(location)?.toString().orEmpty())
                    ) { "Redirect web tidak aman atau keluar dari domain yang disetujui pengguna." }
                    require(currentUrl.host == approvedHost) {
                        "Redirect keluar dari domain yang disetujui pengguna."
                    }
                    null
                } else {
                    require(it.isSuccessful) { "Sumber web menolak permintaan (${it.code})." }
                    val body = it.body ?: error("Respons web kosong.")
                    val contentType = body.contentType()?.let { type -> type.toString().lowercase() }
                    require(
                        contentType == null ||
                            contentType.contains("text/html") ||
                            contentType.contains("application/xhtml+xml") ||
                            (allowXml && (contentType.contains("xml") || contentType.contains("rss")))
                    ) {
                        "Sumber web bukan HTML, RSS, atau sitemap yang didukung."
                    }
                    FetchedWebDocument(
                        finalUrl = requireNotNull(SecureWebUrlPolicy.normalize(it.request.url.toString())) {
                            "Redirect halaman tidak aman."
                        },
                        html = readBoundedWebBody(body, maxResponseBytes),
                        contentType = contentType
                    )
                }
            }
            if (fetched != null) return@withContext fetched
        }
        error("Redirect web tidak dapat diselesaikan.")
    }

    private fun newCall(url: HttpUrl, allowXml: Boolean, approvedAddresses: List<InetAddress>): Call {
        val pinnedClient = redirectControlledClient.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostname.equals(url.host, ignoreCase = true)) {
                        throw UnknownHostException("Unexpected host during web request")
                    }
                    return approvedAddresses
                }
            })
            .build()
        return pinnedClient.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", if (allowXml) ACCEPT_XML_OR_HTML else ACCEPT_HTML)
                .header("User-Agent", USER_AGENT)
                .build()
        )
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        const val USER_AGENT = "StressDetector-AI-Counselor/1.0"
        const val ACCEPT_HTML = "text/html,application/xhtml+xml"
        const val ACCEPT_XML_OR_HTML = "text/html,application/xhtml+xml,application/rss+xml,application/atom+xml,application/xml,text/xml"
    }
}

/** Keep cancellation attached through both headers and body consumption. */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use {
                    check(!call.isCanceled()) { "web_request_cancelled" }
                    read(it)
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } catch (error: IllegalArgumentException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } catch (error: IllegalStateException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

internal fun readBoundedWebBody(body: okhttp3.ResponseBody, maxBytes: Long): String {
    require(body.contentLength() <= maxBytes) { "Respons web terlalu besar." }
    val output = ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(WEB_READ_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= maxBytes) { "Respons web terlalu besar." }
            output.write(buffer, 0, count)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

private const val WEB_READ_BUFFER_BYTES = 16 * 1024

/** Small per-domain limiter. It bounds crawler pressure without using an external service. */
class DomainRateLimiter(
    private val minimumIntervalMillis: Long = MINIMUM_INTERVAL_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val nextAllowedByHost = mutableMapOf<String, Long>()

    suspend fun awaitSlot(host: String, requiredIntervalMillis: Long? = null) {
        val intervalMillis = maxOf(minimumIntervalMillis, requiredIntervalMillis ?: 0L)
        while (true) {
            val delayMillis = synchronized(this) {
                val now = nowMillis()
                val allowedAt = nextAllowedByHost[host] ?: now
                if (allowedAt <= now) {
                    nextAllowedByHost[host] = now + intervalMillis
                    return
                }
                allowedAt - now
            }
            kotlinx.coroutines.delay(delayMillis)
        }
    }

    private companion object {
        const val MINIMUM_INTERVAL_MILLIS = 1_000L
    }
}

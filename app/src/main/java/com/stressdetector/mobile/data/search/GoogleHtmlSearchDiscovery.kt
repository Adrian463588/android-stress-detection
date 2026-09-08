package com.stressdetector.mobile.data.search

import com.fleeksoft.ksoup.Ksoup
import com.stressdetector.mobile.domain.search.WebQuerySanitizer
import com.stressdetector.mobile.domain.search.WebSearchDiscovery
import com.stressdetector.mobile.domain.search.WebSearchLimits
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Bounded Google HTML discovery. Search HTML only discovers source URLs;
 * page content is fetched and cited by WebGroundingGateway.
 */
class GoogleHtmlSearchDiscovery(client: OkHttpClient, private val searchHtmlLoader: (suspend (HttpUrl) -> String)? = null) :
    WebSearchDiscovery {
    private val fetcher = WebHttpFetcher(client, WebSearchLimits.MAX_RESPONSE_BYTES)

    override suspend fun discover(query: String): Result<List<String>> = try {
        val normalizedQuery = WebQuerySanitizer.sanitize(query).getOrThrow()
        val searchUrl = buildSearchUrl(normalizedQuery)
        val html = searchHtmlLoader?.invoke(searchUrl)
            ?: fetcher.fetch(searchUrl, approvedHost = GOOGLE_HOST).html
        val urls = extractResultUrls(html)
        require(urls.isNotEmpty()) { "Tidak ada hasil web HTTPS yang dapat digunakan." }
        Result.success(urls)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun buildSearchUrl(query: String): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(GOOGLE_HOST)
        .addPathSegment("search")
        .addQueryParameter("q", query.take(WebSearchLimits.MAX_QUERY_CHARS))
        .addQueryParameter("num", WebSearchLimits.MAX_SCRAPE_PAGES.toString())
        .addQueryParameter("hl", "id")
        .build()

    private fun extractResultUrls(html: String): List<String> {
        val document = Ksoup.parse(html)
        return document.select("a[href]")
            .asSequence()
            .mapNotNull { element -> normalizeResultHref(element.attr("href")) }
            .filterNot { it.host.equals(GOOGLE_HOST, ignoreCase = true) }
            .distinctBy { it.toString() }
            .take(WebSearchLimits.MAX_SCRAPE_PAGES)
            .map(HttpUrl::toString)
            .toList()
    }

    private fun normalizeResultHref(rawHref: String): HttpUrl? {
        val href = rawHref.trim()
        val candidate = when {
            href.startsWith("/url?", ignoreCase = true) -> {
                val redirect = "https://$GOOGLE_HOST$href".toHttpUrlOrNull() ?: return null
                redirect.queryParameter("q")
                    ?: redirect.queryParameter("url")
                    ?: return null
            }
            href.startsWith("https://", ignoreCase = true) -> href
            else -> return null
        }
        return SecureWebUrlPolicy.normalizeForApproval(candidate)
    }

    private companion object {
        const val GOOGLE_HOST = "www.google.com"
    }
}

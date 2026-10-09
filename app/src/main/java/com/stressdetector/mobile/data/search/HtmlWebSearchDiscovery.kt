package com.stressdetector.mobile.data.search

import com.fleeksoft.ksoup.Ksoup
import com.stressdetector.mobile.domain.search.WebQuerySanitizer
import com.stressdetector.mobile.domain.search.WebSearchDiscovery
import com.stressdetector.mobile.domain.search.WebSearchLimits
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Public HTML result discovery, not a browser/JavaScript or CAPTCHA bypass. */
class HtmlWebSearchDiscovery(client: OkHttpClient, private val searchHtmlLoader: (suspend (HttpUrl) -> String)? = null) :
    WebSearchDiscovery {
    private val fetcher = WebHttpFetcher(client, WebSearchLimits.MAX_RESPONSE_BYTES)

    override suspend fun discover(query: String): Result<List<String>> = try {
        val publicQuery = WebQuerySanitizer.sanitize(query).getOrThrow()
        val url = "https://html.duckduckgo.com/html/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", publicQuery).build()
        val html = searchHtmlLoader?.invoke(url) ?: fetcher.fetch(url).html
        val urls = Ksoup.parse(html, url.toString()).select("a.result__a[href]").asSequence()
            .mapNotNull { link -> resultUrl(link.attr("href"), url) }
            .distinct()
            .take(WebSearchLimits.MAX_SCRAPE_PAGES)
            .toList()
        check(urls.isNotEmpty()) { "web_discovery_no_readable_results" }
        Result.success(urls)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    } catch (error: IllegalStateException) {
        Result.failure(error)
    }

    private fun resultUrl(href: String, base: HttpUrl): String? {
        val candidate = base.resolve(href)?.let { parsed ->
            if (parsed.host == "duckduckgo.com" || parsed.host == "html.duckduckgo.com") {
                parsed.queryParameter("uddg")
            } else {
                parsed.toString()
            }
        }
        return candidate?.let(SecureWebUrlPolicy::normalizeForApproval)?.toString()
            ?.takeUnless { it.toHttpUrlOrNull()?.host?.endsWith("duckduckgo.com") == true }
    }
}

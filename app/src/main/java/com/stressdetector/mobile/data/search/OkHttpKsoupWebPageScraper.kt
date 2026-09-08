package com.stressdetector.mobile.data.search

import com.fleeksoft.ksoup.Ksoup
import com.stressdetector.mobile.domain.search.ScrapedPage
import com.stressdetector.mobile.domain.search.WebPageScraper
import com.stressdetector.mobile.domain.search.WebSearchLimits
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/** Fetches and bounds readable HTTPS pages. Search discovery is a separate boundary. */
class OkHttpKsoupWebPageScraper(client: OkHttpClient, private val clock: () -> Long = { System.currentTimeMillis() }) : WebPageScraper {
    private val fetcher = WebHttpFetcher(client, WebSearchLimits.MAX_RESPONSE_BYTES)
    private val robots = RobotsTxtPolicy(client)

    override suspend fun scrapePages(urls: List<String>): Result<List<ScrapedPage>> = try {
        val normalizedUrls = urls.asSequence()
            .mapNotNull(SecureWebUrlPolicy::normalize)
            .distinct()
            .take(WebSearchLimits.MAX_SCRAPE_PAGES)
            .toList()
        require(normalizedUrls.isNotEmpty()) { "Tidak ada URL HTTPS yang valid." }

        val pages = normalizedUrls.flatMap { url ->
            try {
                scrapeApprovedSource(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }.distinctBy { it.url }
            .take(WebSearchLimits.MAX_SCRAPE_PAGES)
        require(pages.isNotEmpty()) { "Tidak ada halaman web valid yang dapat dibaca." }
        Result.success(pages)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun scrapeApprovedSource(url: okhttp3.HttpUrl): List<ScrapedPage> {
        val sourceCrawlDelay = robots.requirePermitted(url)
        val fetched = fetcher.fetch(
            url,
            approvedHost = url.host,
            allowXml = true,
            crawlDelayMillis = sourceCrawlDelay
        )
        return if (fetched.hasHtmlContentType()) {
            listOf(toPage(fetched))
        } else {
            extractFeedOrSitemapLinks(fetched)
                .mapNotNull(SecureWebUrlPolicy::normalize)
                .filter { it.host == url.host }
                .distinct()
                .take(WebSearchLimits.MAX_SCRAPE_PAGES)
                .mapNotNull { discovered ->
                    runCatching {
                        val crawlDelay = robots.requirePermitted(discovered)
                        val page = fetcher.fetch(
                            discovered,
                            approvedHost = url.host,
                            crawlDelayMillis = crawlDelay
                        )
                        if (page.hasHtmlContentType()) toPage(page) else null
                    }.getOrNull()
                }
        }
    }

    private fun toPage(fetched: FetchedWebDocument): ScrapedPage {
        val document = Ksoup.parse(fetched.html, fetched.finalUrl.toString())
        document.select("script, style, iframe, form, nav, footer, header, aside, button, noscript, [role=navigation]").remove()
        val content = document.selectFirst("article, main, [role=main]") ?: document.body()
        val text = content?.text().orEmpty().replace(WHITESPACE, " ").trim()
            .take(WebSearchLimits.MAX_PAGE_TEXT)
        require(text.isNotBlank()) { "Halaman tidak memiliki teks yang dapat dibaca." }
        return ScrapedPage(
            url = fetched.finalUrl.toString(),
            title = document.title().trim().take(MAX_TITLE_LENGTH),
            text = text,
            retrievedAtMillis = clock()
        )
    }

    private fun extractFeedOrSitemapLinks(fetched: FetchedWebDocument): List<String> {
        val document = Ksoup.parse(fetched.html, fetched.finalUrl.toString())
        return document.select("loc, item link, entry link, channel link")
            .asSequence()
            .mapNotNull { element -> element.attr("href").ifBlank { element.text() }.ifBlank { null } }
            .mapNotNull { href -> fetched.finalUrl.resolve(href)?.toString() }
            .toList()
    }

    private fun FetchedWebDocument.hasHtmlContentType(): Boolean = contentType == null ||
        contentType.contains("text/html") ||
        contentType.contains("application/xhtml+xml")

    private companion object {
        const val MAX_TITLE_LENGTH = 240
        val WHITESPACE = Regex("\\s+")
    }
}

package com.stressdetector.mobile.data.search

import com.fleeksoft.ksoup.Ksoup
import com.stressdetector.mobile.domain.search.ScrapedPage
import com.stressdetector.mobile.domain.search.WebPageScraper
import com.stressdetector.mobile.domain.search.WebSearchLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

        val attempts = coroutineScope {
            normalizedUrls.map { url ->
                async {
                    try {
                        Result.success(scrapeApprovedSource(url))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }.awaitAll()
        }
        val pages = attempts.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.url }
            .take(WebSearchLimits.MAX_SCRAPE_PAGES)
        require(pages.isNotEmpty()) {
            "web_pages_unavailable: " + attempts.mapNotNull { it.exceptionOrNull()?.message }.distinct().joinToString("; ")
        }
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
                    try {
                        val crawlDelay = robots.requirePermitted(discovered)
                        val page = fetcher.fetch(
                            discovered,
                            approvedHost = url.host,
                            crawlDelayMillis = crawlDelay
                        )
                        if (page.hasHtmlContentType()) toPage(page) else null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
        }
    }

    private fun toPage(fetched: FetchedWebDocument): ScrapedPage {
        val document = Ksoup.parse(fetched.html, fetched.finalUrl.toString())
        document.select("script, style, iframe, form, nav, footer, header, aside, button, noscript, [role=navigation]").remove()
        document.select(TOC_SELECTORS).remove()
        val content = document.selectFirst("article, main, [role=main]") ?: document.body()
        // Preserve paragraph boundaries for passage ranking; headings/TOCs are not answers.
        val paragraphs = content.select("p, li")
            .filter { element -> element.select("p, li").none { nested -> nested !== element } }
            .map { it.text().replace(WHITESPACE, " ").trim() }
            .filter(String::isNotBlank)
        val text = paragraphs.joinToString("\n\n").ifBlank { content.text().replace(WHITESPACE, " ").trim() }
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
        const val TOC_SELECTORS =
            ".ez-toc-container, #ez-toc-container, #toc_container, .toc, .table-of-contents, " +
                ".gt-table-of-contents, .lwptoc, .rank-math-toc-block, .wp-block-table-of-contents"
    }
}

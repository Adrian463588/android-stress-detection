package com.stressdetector.mobile.domain.search

/** Bounded content fetched for one AI+ response. */
data class ScrapedPage(val url: String, val title: String, val text: String, val retrievedAtMillis: Long)

interface WebPageScraper {
    suspend fun scrapePages(urls: List<String>): Result<List<ScrapedPage>>

    data object Unavailable : WebPageScraper {
        override suspend fun scrapePages(urls: List<String>): Result<List<ScrapedPage>> =
            Result.failure(IllegalStateException("Scraping halaman web belum tersedia pada build ini."))
    }
}

interface WebSearchDiscovery {
    suspend fun discover(query: String): Result<List<String>>

    data object Unavailable : WebSearchDiscovery {
        override suspend fun discover(query: String): Result<List<String>> =
            Result.failure(IllegalStateException("Web discovery belum tersedia."))
    }
}

object WebSearchLimits {
    const val MAX_QUERY_CHARS = 240
    const val MAX_SCRAPE_PAGES = 3
    const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    const val MAX_PAGE_TEXT = 30_000
    const val MAX_CITATION_TEXT = 1_200
    const val MAX_SOURCE_URL_LENGTH = 2_048
}

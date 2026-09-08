package com.stressdetector.mobile.data.search

import com.stressdetector.mobile.domain.engine.CitationSource
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.search.LocalTextRanker
import com.stressdetector.mobile.domain.search.WebGroundingGateway
import com.stressdetector.mobile.domain.search.WebGroundingResult
import com.stressdetector.mobile.domain.search.WebPageScraper
import com.stressdetector.mobile.domain.search.WebQuerySanitizer
import com.stressdetector.mobile.domain.search.WebSearchDiscovery
import com.stressdetector.mobile.domain.search.WebSearchLimits
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

class WebGroundingGatewayImpl(
    private val pageScraper: WebPageScraper,
    private val evidenceStore: WebResearchEvidenceStore = WebResearchEvidenceStore.NoOp,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val discovery: WebSearchDiscovery = WebSearchDiscovery.Unavailable
) : WebGroundingGateway {
    override suspend fun retrieveAutomatically(query: String): Result<WebGroundingResult> = try {
        val normalizedQuery = WebQuerySanitizer.sanitize(query).getOrThrow()
        val discoveredUrls = discovery.discover(normalizedQuery).getOrThrow()
        retrieveDiscoveredPages(normalizedQuery, discoveredUrls)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun retrieveDiscoveredPages(query: String, urls: List<String>): Result<WebGroundingResult> = try {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotBlank()) { "Pertanyaan untuk sumber web tidak boleh kosong." }
        val selectedUrls = urls.asSequence()
            .mapNotNull(SecureWebUrlPolicy::normalize)
            .distinct()
            .take(WebSearchLimits.MAX_SCRAPE_PAGES)
            .map { it.toString() }
            .toList()
        require(selectedUrls.isNotEmpty()) { "Tidak ada halaman HTTPS valid dari discovery web." }
        val pages = pageScraper.scrapePages(selectedUrls).getOrThrow()
        evidenceStore.store(pages)
        val citations = pages.asSequence()
            .filter { it.text.isNotBlank() && SecureWebUrlPolicy.normalize(it.url) != null }
            .map { page -> toCitation(normalizedQuery, page) }
            .filter { citation -> citation.relevanceScore >= MIN_RELEVANCE_SCORE }
            .sortedByDescending(KnowledgeCitation::relevanceScore)
            .take(WebSearchLimits.MAX_SCRAPE_PAGES)
            .toList()
        require(citations.isNotEmpty()) { "Tidak ada halaman valid yang dapat dijadikan sumber." }
        Result.success(
            WebGroundingResult(
                query = normalizedQuery,
                citations = citations,
                retrievedAtMillis = clock()
            )
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun toCitation(query: String, page: com.stressdetector.mobile.domain.search.ScrapedPage): KnowledgeCitation {
        val url = requireNotNull(SecureWebUrlPolicy.normalize(page.url))
        val passage = LocalTextRanker.bestPassage(query, page.text, WebSearchLimits.MAX_CITATION_TEXT)
            ?: error("Halaman tidak memiliki passage yang dapat digunakan.")
        return KnowledgeCitation(
            citationId = "web-${sha256(page.url).take(CITATION_ID_LENGTH)}",
            sourceTitle = page.title.ifBlank { url.host },
            section = "Sumber web",
            text = passage.text,
            relevanceScore = passage.score,
            sourceType = CitationSource.WEB,
            sourceUrl = url.toString(),
            sourceDomain = url.host,
            retrievedAtMillis = page.retrievedAtMillis,
            contentHash = sha256(page.text),
            sourceTier = EvidenceSourceTier.WEB
        )
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val CITATION_ID_LENGTH = 16
        const val MIN_RELEVANCE_SCORE = 0.15f
    }
}

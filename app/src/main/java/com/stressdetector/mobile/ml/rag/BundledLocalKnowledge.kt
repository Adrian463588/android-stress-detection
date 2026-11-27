package com.stressdetector.mobile.ml.rag

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.stressdetector.mobile.data.db.DocumentChunkEntity
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever
import com.stressdetector.mobile.domain.engine.RetrievalMethod
import com.stressdetector.mobile.domain.search.LocalTextRanker
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A manifest entry for an approved, text-based knowledge asset shipped with the app. */
data class BundledLocalDocument(
    val documentId: String,
    val title: String,
    val assetPath: String,
    val format: String,
    val sourceSha256: String,
    val license: String,
    val provenance: String,
    val sourceLocator: String? = null,
    val collection: String = COLLECTION_COUNSELING_THEORY,
    val category: String = "",
    val intendedAudience: String = "counselor",
    val evidenceLevel: String = "reference",
    val usageRestrictions: List<String> = emptyList(),
    val reviewStatus: String = "reviewed",
    val documentVersion: String = "1.0"
) {
    companion object {
        const val COLLECTION_COUNSELING_THEORY = "counseling_theory"
        const val COLLECTION_CLINICAL_GUIDANCE = "clinical_guidance"
        const val COLLECTION_PSYCHOMETRIC_INTERPRETATION = "psychometric_interpretation_guide"
        const val COLLECTION_RESTRICTED_ADMIN = "psychometric_admin_restricted"

        val DEFAULT_COUNSELING_COLLECTIONS: Set<String> = setOf(
            COLLECTION_COUNSELING_THEORY,
            COLLECTION_CLINICAL_GUIDANCE,
            COLLECTION_PSYCHOMETRIC_INTERPRETATION
        )
    }
}

/** Boundary for a checksum-verified bundled corpus. */
interface BundledLocalCorpus {
    suspend fun loadDocuments(): Result<List<BundledLocalDocument>>

    suspend fun read(document: BundledLocalDocument): Result<ByteArray>
}

/** Reads only the explicitly declared assets; it never scans the APK for arbitrary files. */
class AssetBundledLocalCorpus(
    private val context: Context,
    private val manifestPath: String = MANIFEST_PATH,
    private val gson: Gson = Gson()
) : BundledLocalCorpus {
    override suspend fun loadDocuments(): Result<List<BundledLocalDocument>> = withContext(Dispatchers.IO) {
        runCatching {
            val rawManifest = context.assets.open(manifestPath).bufferedReader().use { reader -> reader.readText() }
            val manifestRoot = JsonParser.parseString(rawManifest).asJsonObject
            val manifestChecksum = manifestRoot.get("manifest_sha256")?.asString.orEmpty()
            require(manifestChecksum.matches(SHA256)) { "bundled_manifest_checksum_invalid" }
            val unsignedManifest = manifestRoot.deepCopy().also { it.remove("manifest_sha256") }
            val gsonStr = Gson().toJson(unsignedManifest)
            val manifestHash = sha256(gsonStr.toByteArray(Charsets.UTF_8))
            val lfManifestHash = sha256(gsonStr.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            val crlfManifestHash = sha256(gsonStr.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
            require(
                manifestChecksum.lowercase(Locale.ROOT) in listOf(manifestHash, lfManifestHash, crlfManifestHash)
            ) { "bundled_manifest_checksum_mismatch" }
            val manifest = gson.fromJson(manifestRoot, BundledLocalManifest::class.java)
            require(manifest.schemaVersion == MANIFEST_SCHEMA) { "bundled_manifest_schema_unsupported" }
            require(manifest.corpusVersion.isNotBlank()) { "bundled_manifest_version_missing" }
            if (manifest.corpusStatus == STATUS_EMPTY) return@runCatching emptyList()
            require(manifest.corpusStatus == STATUS_READY) { "bundled_manifest_status_invalid" }
            manifest.documents.map { it.toDocument() }.also { documents ->
                require(documents.distinctBy(BundledLocalDocument::documentId).size == documents.size) {
                    "bundled_manifest_duplicate_document"
                }
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun read(document: BundledLocalDocument): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            validateDocument(document)
            val bytes = context.assets.open(document.assetPath).use { input ->
                input.readBounded(MAX_DOCUMENT_BYTES)
            }
            val hash = sha256(bytes)
            val expectedHash = document.sourceSha256.lowercase(Locale.ROOT)
            val matches = if (hash == expectedHash) {
                true
            } else {
                val text = bytes.toString(Charsets.UTF_8)
                val lfHash = sha256(text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                val crlfHash = sha256(text.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
                expectedHash in listOf(lfHash, crlfHash)
            }
            require(matches) {
                "bundled_document_checksum_mismatch"
            }
            bytes
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun BundledLocalManifestDocument.toDocument(): BundledLocalDocument {
        val document = BundledLocalDocument(
            documentId = documentId,
            title = title,
            assetPath = assetPath,
            format = format,
            sourceSha256 = sourceSha256,
            license = license,
            provenance = provenance,
            sourceLocator = sourceLocator,
            collection = collection.ifBlank { BundledLocalDocument.COLLECTION_COUNSELING_THEORY },
            category = category,
            intendedAudience = intendedAudience.ifBlank { "counselor" },
            evidenceLevel = evidenceLevel.ifBlank { "reference" },
            usageRestrictions = usageRestrictions,
            reviewStatus = reviewStatus.ifBlank { "reviewed" },
            documentVersion = documentVersion.ifBlank { "1.0" }
        )
        validateDocument(document)
        return document
    }

    private fun validateDocument(document: BundledLocalDocument) {
        require(document.documentId.isNotBlank()) { "bundled_document_id_missing" }
        require(document.title.isNotBlank()) { "bundled_document_title_missing" }
        require(document.license.isNotBlank()) { "bundled_document_license_missing" }
        require(document.provenance.isNotBlank()) { "bundled_document_provenance_missing" }
        require(document.sourceSha256.matches(SHA256)) { "bundled_document_checksum_invalid" }
        val normalizedPath = document.assetPath.replace('\\', '/')
        require(normalizedPath == document.assetPath) { "bundled_document_path_not_normalized" }
        require(
            normalizedPath.isNotBlank() &&
                !normalizedPath.startsWith('/') &&
                !normalizedPath.contains("..")
        ) { "bundled_document_path_invalid" }
        require(document.format.lowercase(Locale.ROOT) in SUPPORTED_FORMATS) {
            "bundled_document_format_not_allowed"
        }
    }

    private companion object {
        const val MANIFEST_PATH = "ai/bundled_knowledge_manifest.json"
        const val MANIFEST_SCHEMA = "bundled_knowledge_manifest_v1"
        const val STATUS_EMPTY = "EMPTY"
        const val STATUS_READY = "READY"
        const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
        val SUPPORTED_FORMATS = setOf("txt", "text", "md", "markdown", "json")
        val SHA256 = Regex("^[a-fA-F0-9]{64}$")
    }
}

/**
 * Conservative lexical fallback for an approved bundled corpus. A source is admitted only after
 * the manifest checksum is verified and the injected benchmark policy accepts the ranked evidence.
 */
class BundledLocalRagRetriever(
    private val corpus: BundledLocalCorpus,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val policy: LocalRagRetrievalPolicy = LocalRagRetrievalPolicy(),
    private val ranker: HybridRetrievalRanker = HybridRetrievalRanker(),
    private val allowedCollections: Set<String> = BundledLocalDocument.DEFAULT_COUNSELING_COLLECTIONS
) : RagRetriever {
    @Suppress("UNUSED_PARAMETER")
    override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float): RagRetrievalResult =
        withContext(Dispatchers.IO) {
            val queryTerms = LocalTextRanker.tokens(query).filterNot(BUNDLED_QUERY_STOP_WORDS::contains).distinct()
            if (queryTerms.isEmpty()) {
                return@withContext RagRetrievalResult.Unavailable("Bundled local query has no searchable terms")
            }
            val documents = corpus.loadDocuments().getOrElse { error ->
                if (error is CancellationException) throw error
                return@withContext RagRetrievalResult.Unavailable(
                    "Bundled local corpus unavailable: ${error.message ?: "manifest invalid"}"
                )
            }
            val eligibleDocuments = documents.filter { it.collection in allowedCollections }
            if (eligibleDocuments.isEmpty()) {
                return@withContext RagRetrievalResult.Unavailable("No approved bundled local knowledge documents")
            }

            val normalizedQuery = queryTerms.joinToString(" ")
            val candidates = eligibleDocuments.flatMap { document ->
                currentCoroutineContext().ensureActive()
                if (!document.sourceSha256.matches(SHA256)) return@flatMap emptyList()
                val bytes = corpus.read(document).getOrElse { error ->
                    if (error is CancellationException) throw error
                    return@flatMap emptyList()
                }
                if (sha256(bytes) != document.sourceSha256.lowercase(Locale.ROOT)) return@flatMap emptyList()
                val content = bytes.toString(Charsets.UTF_8).take(MAX_DOCUMENT_TEXT_CHARS)
                extractCandidates(document, content, normalizedQuery)
            }.sortedWith(compareByDescending<BundledCandidate> { it.score }.thenBy { it.document.documentId })

            val lexicalCandidates = candidates.map(::toLexicalCandidate)
            val ranked = ranker.rank(
                query = normalizedQuery,
                queryVector = null,
                candidates = lexicalCandidates.map { it.first },
                lexicalLimit = policy.lexicalCandidateLimit,
                resultLimit = minOf(maxResults.coerceAtLeast(1), policy.maxEvidenceChunks),
                maxPerDocument = policy.maxPerDocument,
                maxPerPage = policy.maxPerPage,
                lexicalCandidates = lexicalCandidates.take(policy.lexicalCandidateLimit)
            )
            currentCoroutineContext().ensureActive()
            if (ranked.isEmpty() ||
                !policy.thresholdPolicy.accepts(
                    RetrievalThresholdContext(query, LocalRetrievalMode.LEXICAL_ONLY, ranked)
                )
            ) {
                return@withContext RagRetrievalResult.BelowThreshold(
                    bestScore = ranked.firstOrNull()?.fusedScore,
                    method = RetrievalMethod.LEXICAL_ONLY,
                    sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
                )
            }

            RagRetrievalResult.Retrieved(
                citations = buildCitations(ranked, documents),
                method = RetrievalMethod.LEXICAL_ONLY,
                sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
            )
        }

    private fun extractCandidates(document: BundledLocalDocument, content: String, normalizedQuery: String): List<BundledCandidate> {
        if (!document.format.equals("json", ignoreCase = true)) {
            val passage = LocalTextRanker.bestPassage(normalizedQuery, content, MAX_CITATION_TEXT)
                ?: return emptyList()
            val score = LocalTextRanker.score(normalizedQuery, passage.text)
            if (score <= 0f) return emptyList()
            return listOf(BundledCandidate(document = document, text = passage.text, score = score))
        }
        return runCatching {
            val root = JsonParser.parseString(content).asJsonObject
            val sections = root.getAsJsonArray("sections") ?: return@runCatching emptyList()
            sections.mapNotNull { element ->
                val sec = element.asJsonObject
                val text = sec.get("text")?.asString.orEmpty()
                if (text.isBlank()) return@mapNotNull null
                val heading = sec.get("heading")?.asString?.takeIf(String::isNotBlank)
                val pageRange = sec.get("page_range")?.asString?.takeIf(String::isNotBlank)
                val sectionId = sec.get("section_id")?.asString?.takeIf(String::isNotBlank)
                val passage = LocalTextRanker.bestPassage(normalizedQuery, text, MAX_CITATION_TEXT)
                    ?: return@mapNotNull null
                val headingBonus = heading?.let { LocalTextRanker.score(normalizedQuery, it) * HEADING_BONUS_WEIGHT } ?: 0f
                val totalScore = (LocalTextRanker.score(normalizedQuery, passage.text) + headingBonus).coerceAtMost(1f)
                if (totalScore <= 0f) return@mapNotNull null
                BundledCandidate(
                    document = document,
                    text = passage.text,
                    score = totalScore,
                    sectionHeading = heading,
                    pageRange = pageRange,
                    sectionId = sectionId
                )
            }.sortedByDescending { it.score }.take(MAX_SECTIONS_PER_BOOK)
        }.getOrElse { emptyList() }
    }

    private fun toLexicalCandidate(candidate: BundledCandidate): Pair<RetrievalCandidate, Float> {
        val passageHash = sha256(candidate.text.toByteArray(Charsets.UTF_8))
        val sectionLabel = buildSectionLabel(candidate.document, candidate.sectionHeading, candidate.pageRange)
        val locator = candidate.pageRange?.let { "${candidate.document.sourceLocator ?: candidate.document.assetPath} · Hal. $it" }
            ?: candidate.document.sourceLocator
        val chunkId = listOfNotNull(candidate.document.documentId, candidate.sectionId, passageHash.take(HASH_PREFIX_LENGTH))
            .joinToString(":")
        return RetrievalCandidate(
            DocumentChunkEntity(
                chunkId = chunkId,
                documentId = candidate.document.documentId,
                chunkIndex = 0,
                textContent = candidate.text,
                embeddingBlob = null,
                sourceTitle = candidate.document.title,
                sourcePage = parseStartPage(candidate.pageRange),
                sourceLocator = "$sectionLabel||$locator"
            ),
            vector = null
        ) to candidate.score
    }

    private fun buildCitations(ranked: List<RankedRetrievalCandidate>, documents: List<BundledLocalDocument>): List<KnowledgeCitation> {
        val retrievedAt = clock()
        val documentById = documents.associateBy(BundledLocalDocument::documentId)
        return ranked.map { scored ->
            val chunk = scored.candidate.chunk
            val document = documentById.getValue(chunk.documentId)
            val passageHash = sha256(chunk.textContent.toByteArray(Charsets.UTF_8))
            val parts = chunk.sourceLocator.orEmpty().split("||", limit = 2)
            val section = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: (document.sourceLocator ?: "Bundled local passage")
            val locator = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: document.sourceLocator
            KnowledgeCitation(
                citationId = "bundled-${document.documentId}-${passageHash.take(HASH_PREFIX_LENGTH)}",
                sourceTitle = document.title,
                section = section,
                text = chunk.textContent,
                relevanceScore = scored.fusedScore,
                sourcePage = chunk.sourcePage,
                sourceFileName = document.assetPath,
                sourceLocator = locator,
                retrievedAtMillis = retrievedAt,
                contentHash = passageHash,
                chunkId = chunk.chunkId,
                documentId = document.documentId,
                sourceSha256 = document.sourceSha256,
                sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
            )
        }
    }

    private fun buildSectionLabel(document: BundledLocalDocument, heading: String?, pageRange: String?): String = when {
        !heading.isNullOrBlank() && !pageRange.isNullOrBlank() -> "$heading (Hal. $pageRange)"
        !heading.isNullOrBlank() -> heading
        !pageRange.isNullOrBlank() -> "Hal. $pageRange"
        else -> document.sourceLocator ?: "Bundled local passage"
    }

    private fun parseStartPage(pageRange: String?): Int? = pageRange
        ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private data class BundledCandidate(
        val document: BundledLocalDocument,
        val text: String,
        val score: Float,
        val sectionHeading: String? = null,
        val pageRange: String? = null,
        val sectionId: String? = null
    )

    private companion object {
        const val HASH_PREFIX_LENGTH = 16
        const val MAX_DOCUMENT_TEXT_CHARS = 600_000
        const val MAX_CITATION_TEXT = 1_200
        const val MAX_SECTIONS_PER_BOOK = 2
        const val HEADING_BONUS_WEIGHT = 0.25f
        val SHA256 = Regex("^[a-fA-F0-9]{64}$")
        val BUNDLED_QUERY_STOP_WORDS = setOf(
            "apa",
            "apakah",
            "bagaimana",
            "bisa",
            "cara",
            "jelaskan",
            "tolong",
            "mohon"
        )
    }
}

private data class BundledLocalManifest(
    @SerializedName("schema_version") val schemaVersion: String = "",
    @SerializedName("corpus_version") val corpusVersion: String = "",
    @SerializedName("corpus_status") val corpusStatus: String = "EMPTY",
    @SerializedName("manifest_sha256") val manifestSha256: String = "",
    val documents: List<BundledLocalManifestDocument> = emptyList()
)

private data class BundledLocalManifestDocument(
    @SerializedName("document_id") val documentId: String = "",
    val title: String = "",
    @SerializedName("asset_path") val assetPath: String = "",
    val format: String = "",
    @SerializedName("source_sha256") val sourceSha256: String = "",
    val license: String = "",
    val provenance: String = "",
    @SerializedName("source_locator") val sourceLocator: String? = null,
    val collection: String = "",
    val category: String = "",
    @SerializedName("intended_audience") val intendedAudience: String = "",
    @SerializedName("evidence_level") val evidenceLevel: String = "",
    @SerializedName("usage_restrictions") val usageRestrictions: List<String> = emptyList(),
    @SerializedName("review_status") val reviewStatus: String = "",
    @SerializedName("document_version") val documentVersion: String = ""
)

internal fun InputStream.readBounded(maxBytes: Int, sizeLimitError: String = "bundled_document_size_limit_exceeded"): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { sizeLimitError }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** The assessment follow-up can cite a fixed, checksum-verified interview technique. */
internal suspend fun assessmentFollowUpReference(corpus: BundledLocalCorpus): RagRetrievalResult.Retrieved? {
    val document = corpus.loadDocuments().getOrNull()?.singleOrNull { it.documentId == "bk_05_keterampilan_mikro" }
    val bytes = document?.let { corpus.read(it).getOrNull() }
    val heading = "3. Keterampilan Bertanya Eksploratif"
    val text = bytes?.toString(Charsets.UTF_8)
        ?.substringAfter("## $heading", "")
        ?.substringBefore("- **Pertanyaan Tertutup")
        ?.substringBefore("\n## ")
        ?.trim()
    return if (document == null || text.isNullOrBlank()) {
        null
    } else {
        val hash = sha256(text.toByteArray(Charsets.UTF_8))
        val citation = KnowledgeCitation(
            citationId = "${document.documentId}:$hash",
            sourceTitle = document.title,
            section = heading,
            text = text,
            relevanceScore = 0f,
            sourceLocator = "${document.sourceLocator ?: document.assetPath} · $heading",
            contentHash = hash,
            documentId = document.documentId,
            sourceSha256 = document.sourceSha256,
            sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
        )
        RagRetrievalResult.Retrieved(listOf(citation), RetrievalMethod.CURATED_REFERENCE, EvidenceSourceTier.BUNDLED_LOCAL)
    }
}

private fun sha256(value: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte) }

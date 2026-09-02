package com.stressdetector.mobile.ml.rag

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
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
import kotlinx.coroutines.Dispatchers
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
    val sourceLocator: String? = null
)

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
            require(
                sha256(Gson().toJson(unsignedManifest).toByteArray(Charsets.UTF_8)) ==
                    manifestChecksum.lowercase(Locale.ROOT)
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
        }
    }

    override suspend fun read(document: BundledLocalDocument): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            validateDocument(document)
            val bytes = context.assets.open(document.assetPath).use { input ->
                input.readBounded(MAX_DOCUMENT_BYTES)
            }
            require(sha256(bytes) == document.sourceSha256.lowercase(Locale.ROOT)) {
                "bundled_document_checksum_mismatch"
            }
            bytes
        }
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
            sourceLocator = sourceLocator
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
 * the manifest checksum is verified and the query has a meaningful lexical match.
 */
class BundledLocalRagRetriever(private val corpus: BundledLocalCorpus, private val clock: () -> Long = { System.currentTimeMillis() }) :
    RagRetriever {
    override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float): RagRetrievalResult =
        withContext(Dispatchers.IO) {
            val queryTerms = LocalTextRanker.tokens(query)
                .filterNot(BUNDLED_QUERY_STOP_WORDS::contains)
                .distinct()
            if (queryTerms.isEmpty()) {
                return@withContext RagRetrievalResult.Unavailable("Bundled local query has no searchable terms")
            }
            val documents = corpus.loadDocuments().getOrElse { error ->
                return@withContext RagRetrievalResult.Unavailable(
                    "Bundled local corpus unavailable: ${error.message ?: "manifest invalid"}"
                )
            }
            if (documents.isEmpty()) {
                return@withContext RagRetrievalResult.Unavailable("No approved bundled local knowledge documents")
            }

            val normalizedQuery = queryTerms.joinToString(" ")
            val candidates = documents.mapNotNull { document ->
                if (!document.sourceSha256.matches(SHA256)) return@mapNotNull null
                val bytes = corpus.read(document).getOrNull() ?: return@mapNotNull null
                if (sha256(bytes) != document.sourceSha256.lowercase(Locale.ROOT)) return@mapNotNull null
                val content = bytes.toString(Charsets.UTF_8).take(MAX_DOCUMENT_TEXT_CHARS)
                val passage = LocalTextRanker.bestPassage(normalizedQuery, content, MAX_CITATION_TEXT)
                    ?: return@mapNotNull null
                val score = LocalTextRanker.score(normalizedQuery, passage.text)
                if (score < MIN_RELEVANCE_SCORE) return@mapNotNull null
                BundledCandidate(document, passage.text, score)
            }.sortedWith(compareByDescending<BundledCandidate> { it.score }.thenBy { it.document.documentId })

            if (candidates.isEmpty()) {
                return@withContext RagRetrievalResult.BelowThreshold(
                    bestScore = null,
                    method = RetrievalMethod.LEXICAL_ONLY,
                    sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
                )
            }

            val retrievedAt = clock()
            val citations = candidates.take(maxResults.coerceIn(1, MAX_RESULTS)).map { candidate ->
                val passageHash = sha256(candidate.text.toByteArray(Charsets.UTF_8))
                KnowledgeCitation(
                    citationId = "bundled-${candidate.document.documentId}-${passageHash.take(HASH_PREFIX_LENGTH)}",
                    sourceTitle = candidate.document.title,
                    section = candidate.document.sourceLocator ?: "Bundled local passage",
                    text = candidate.text,
                    relevanceScore = candidate.score,
                    sourceFileName = candidate.document.assetPath,
                    sourceLocator = candidate.document.sourceLocator,
                    retrievedAtMillis = retrievedAt,
                    contentHash = passageHash,
                    chunkId = "${candidate.document.documentId}:$passageHash",
                    documentId = candidate.document.documentId,
                    sourceSha256 = candidate.document.sourceSha256,
                    sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
                )
            }
            RagRetrievalResult.Retrieved(
                citations = citations,
                method = RetrievalMethod.LEXICAL_ONLY,
                sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
            )
        }

    private data class BundledCandidate(val document: BundledLocalDocument, val text: String, val score: Float)

    private companion object {
        const val MAX_RESULTS = 4
        const val HASH_PREFIX_LENGTH = 16
        const val MAX_DOCUMENT_TEXT_CHARS = 250_000
        const val MAX_CITATION_TEXT = 1_200
        const val MIN_RELEVANCE_SCORE = 0.45f
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
    @SerializedName("source_locator") val sourceLocator: String? = null
)

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "bundled_document_size_limit_exceeded" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun sha256(value: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte) }

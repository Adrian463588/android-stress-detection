package com.stressdetector.mobile.ml.artifacts

import android.content.Context
import com.google.gson.Gson
import java.net.URI

enum class LocalArtifactId(val wireValue: String, val catalogEntryRequired: Boolean = true) {
    GEMMA_4_E2B("gemma_4_e2b"),
    SPEECH_BUNDLE("speech_bundle", catalogEntryRequired = false),
    MULTILINGUAL_E5_SMALL("multilingual_e5_small")
}

enum class CatalogArtifactKind {
    GEMMA_LLM,
    MULTILINGUAL_EMBEDDING,
    SPEECH_COMPONENT
}

data class CatalogArtifactFile(
    val role: String,
    val path: String,
    val url: String? = null,
    val bundlePath: String? = null,
    val archiveEntrySuffix: String? = null,
    val sizeBytes: Long,
    val sha256: String
)

data class CatalogArtifactEntry(
    val id: String,
    val kind: CatalogArtifactKind,
    val version: String,
    val revision: String,
    val runtimeVersion: String,
    val sourcePage: String,
    val license: String,
    val allowedRedirectHosts: List<String>,
    val files: List<CatalogArtifactFile>,
    val runtimeContract: CatalogRuntimeContract? = null
)

data class CatalogRuntimeContract(
    val dimensions: Int? = null,
    val maxTokens: Int? = null,
    val prefixes: List<String> = emptyList(),
    val pooling: String? = null,
    val normalization: String? = null
)

data class SpeechBundleCatalogMetadata(
    val bundleVersion: String,
    val runtimeVersion: String,
    val sampleRateHz: Int,
    val languages: List<String>,
    val speakerCount: Int,
    val runtimeSource: String,
    val componentIds: List<String>
)

private data class ArtifactCatalogDocument(
    val schemaVersion: Int,
    val artifacts: List<CatalogArtifactEntry>,
    val speechBundle: SpeechBundleCatalogMetadata
)

/**
 * APK-owned catalog for every downloadable model binary.
 *
 * The APK signature protects this document while each payload is independently protected by its
 * catalogued SHA-256. Runtime code must not accept a URL or checksum from UI/user input.
 */
class LocalArtifactCatalog private constructor(
    val schemaVersion: Int,
    private val entries: Map<String, CatalogArtifactEntry>,
    val speechBundle: SpeechBundleCatalogMetadata
) {
    fun require(id: LocalArtifactId): CatalogArtifactEntry = entries[id.wireValue] ?: error("Artifact catalog is missing ${id.wireValue}.")

    fun requireById(id: String): CatalogArtifactEntry = entries[id] ?: error("Artifact catalog is missing $id.")

    fun speechComponents(): List<CatalogArtifactEntry> = speechBundle.componentIds.map(::requireById)

    fun all(): List<CatalogArtifactEntry> = entries.values.toList()

    companion object {
        const val ASSET_PATH = "ai/artifact_catalog.json"
        private const val SUPPORTED_SCHEMA = 1
        private val SHA256 = Regex("^[a-f0-9]{64}$")
        private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")

        fun load(context: Context): LocalArtifactCatalog = context.applicationContext.assets
            .open(ASSET_PATH)
            .bufferedReader(Charsets.UTF_8)
            .use { parse(it.readText()) }

        fun parse(json: String): LocalArtifactCatalog {
            val document = Gson().fromJson(json, ArtifactCatalogDocument::class.java)
                ?: error("Artifact catalog is empty.")
            require(document.schemaVersion == SUPPORTED_SCHEMA) {
                "Unsupported artifact catalog schema ${document.schemaVersion}."
            }
            require(document.artifacts.isNotEmpty()) { "Artifact catalog has no artifacts." }
            val entries = document.artifacts.associateBy(CatalogArtifactEntry::id)
            require(entries.size == document.artifacts.size) { "Artifact catalog contains duplicate IDs." }
            validateSpeechMetadata(document.speechBundle)
            document.artifacts.forEach(::validateEntry)
            LocalArtifactId.entries.filter(LocalArtifactId::catalogEntryRequired).forEach { id ->
                require(entries.containsKey(id.wireValue)) { "Artifact catalog is missing ${id.wireValue}." }
            }
            document.speechBundle.componentIds.forEach { id ->
                require(entries[id]?.kind == CatalogArtifactKind.SPEECH_COMPONENT) {
                    "Speech component $id is missing or has the wrong kind."
                }
            }
            return LocalArtifactCatalog(document.schemaVersion, entries, document.speechBundle)
        }

        private fun validateSpeechMetadata(metadata: SpeechBundleCatalogMetadata) {
            require(metadata.bundleVersion.isNotBlank() && metadata.runtimeVersion.isNotBlank())
            require(metadata.sampleRateHz > 0 && metadata.speakerCount == 2)
            require(metadata.languages.map(String::lowercase).containsAll(listOf("id", "en")))
            require(metadata.componentIds.isNotEmpty() && metadata.componentIds.distinct().size == metadata.componentIds.size)
            validateHttps(metadata.runtimeSource, "speech runtime source")
        }

        private fun validateEntry(entry: CatalogArtifactEntry) {
            require(SAFE_ID.matches(entry.id)) { "Invalid artifact ID ${entry.id}." }
            require(entry.version.isNotBlank() && entry.revision.isNotBlank() && entry.runtimeVersion.isNotBlank())
            require(entry.license.isNotBlank()) { "Artifact ${entry.id} has no license." }
            validateHttps(entry.sourcePage, "${entry.id} source page")
            require(entry.allowedRedirectHosts.isNotEmpty()) { "Artifact ${entry.id} has no redirect allowlist." }
            require(entry.files.isNotEmpty()) { "Artifact ${entry.id} has no files." }
            require(entry.files.map(CatalogArtifactFile::path).distinct().size == entry.files.size) {
                "Artifact ${entry.id} contains duplicate paths."
            }
            entry.files.forEach { file ->
                require(file.role.isNotBlank() && file.path.isNotBlank())
                require(file.sizeBytes > 0L) { "Artifact ${entry.id}/${file.path} has invalid size." }
                require(SHA256.matches(file.sha256.lowercase())) {
                    "Artifact ${entry.id}/${file.path} has invalid SHA-256."
                }
                requireSafeRelativePath(file.path)
                file.bundlePath?.let(::requireSafeRelativePath)
                file.url?.let { url ->
                    validateHttps(url, "${entry.id}/${file.path}")
                    val uri = URI(url)
                    require(entry.allowedRedirectHosts.any { permitsHost(uri.host, it) }) {
                        "Artifact ${entry.id} does not allow its source host ${uri.host}."
                    }
                    require("/resolve/main/" !in uri.path && "/latest/" !in uri.path) {
                        "Artifact ${entry.id} uses a moving source reference."
                    }
                }
            }
            if (entry.kind == CatalogArtifactKind.SPEECH_COMPONENT) {
                require(entry.files.count { it.role == "download" && it.url != null } == 1) {
                    "Speech component ${entry.id} must have exactly one download file."
                }
                require(entry.files.any { it.role == "runtime" && it.bundlePath != null }) {
                    "Speech component ${entry.id} has no runtime payload."
                }
            }
            if (entry.kind == CatalogArtifactKind.MULTILINGUAL_EMBEDDING) {
                val contract = requireNotNull(entry.runtimeContract) {
                    "Artifact ${entry.id} has no runtime contract."
                }
                require(contract.dimensions == 384 && contract.maxTokens == 512)
                require(contract.prefixes.toSet() == setOf("query: ", "passage: "))
                require(contract.pooling == "attention_mask_mean" && contract.normalization == "l2")
            }
        }

        private fun validateHttps(value: String, label: String) {
            val uri = URI(value)
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "$label must be an HTTPS URL."
            }
        }

        private fun permitsHost(hostValue: String?, ruleValue: String): Boolean {
            val host = hostValue?.lowercase()?.trimEnd('.') ?: return false
            val rule = ruleValue.lowercase().trimEnd('.')
            return if (rule.startsWith("*.")) {
                host.endsWith(rule.removePrefix("*")) && host != rule.removePrefix("*.")
            } else {
                host == rule
            }
        }

        private fun requireSafeRelativePath(path: String) {
            val normalized = path.replace('\\', '/')
            require(
                normalized.isNotBlank() &&
                    !normalized.startsWith('/') &&
                    normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
            ) { "Unsafe artifact path: $path" }
        }
    }
}

package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.domain.counselor.CounselorPersonaProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Provenance tier used by the local-first evidence resolver. */
enum class EvidenceSourceTier {
    APP_RAG,
    BUNDLED_LOCAL,
    WEB,
    NONE
}

/**
 * Domain model for cited knowledge passages retrieved from an approved evidence source.
 */
data class KnowledgeCitation(
    val citationId: String,
    val sourceTitle: String, // e.g. "PNPPK Depression", "PPDGJ-III", "ICD-11"
    val section: String,
    val text: String,
    val relevanceScore: Float,
    val sourceFileName: String? = null,
    val sourcePage: Int? = null,
    val sourceLocator: String? = null,
    /** Optional provenance for citations created by the web-grounding adapter. */
    val sourceType: CitationSource = CitationSource.LOCAL_DOCUMENT,
    val sourceUrl: String? = null,
    val sourceDomain: String? = null,
    val retrievedAtMillis: Long? = null,
    val contentHash: String? = null,
    /** Exact local provenance. Legacy records may only have [citationId]. */
    val chunkId: String? = null,
    val documentId: String? = null,
    /** Immutable encrypted-index generation that supplied this citation. */
    val generationId: String? = null,
    /** SHA-256 of the source document, when the local index can attest it. */
    val sourceSha256: String? = null,
    /** Explicit source tier; the default keeps legacy citations source-aware. */
    val sourceTier: EvidenceSourceTier = when (sourceType) {
        CitationSource.WEB -> EvidenceSourceTier.WEB
        CitationSource.LOCAL_DOCUMENT -> EvidenceSourceTier.APP_RAG
    }
)

enum class CitationSource {
    LOCAL_DOCUMENT,
    WEB
}

/**
 * Prompt configuration container to ensure prompts are versioned assets rather than ad hoc strings.
 */
data class PromptAsset(
    val id: String,
    val version: String,
    val systemPrompt: String,
    val userPromptTemplate: String,
    val generalSafeInstruction: String = "",
    val groundedInstruction: String = "",
    val disclaimer: String = "",
    val crisisInstruction: String = "",
    val scopeInstruction: String = ""
)

/** A persisted turn supplied to a fresh LiteRT-LM conversation. */
data class ConversationTurn(val role: String, val content: String)

enum class RagMode {
    GROUNDED,
    BUNDLED_GROUNDED,
    WEB_GROUNDED,
    GENERAL_SAFE,
    UNAVAILABLE
}

data class CounselorGenerationRequest(
    val promptAsset: PromptAsset,
    val userMessage: String,
    val ragMode: RagMode,
    val retrievedCitations: List<KnowledgeCitation>,
    val stressHistoryContext: String,
    val personaProfile: CounselorPersonaProfile = CounselorPersonaProfile().withIntegrityHash(),
    /** Exact registry for evidence that was actually admitted to this prompt. */
    val citationRegistry: CitationRegistry = CitationRegistry.Empty,
    /** Legacy compatibility only; prompt policy does not treat this as instructions. */
    val personaMarkdown: String = "",
    val personaVersion: Int = 0,
    val recentHistory: List<ConversationTurn> = emptyList(),
    val webCitations: List<KnowledgeCitation> = emptyList()
)

/** Runtime status is deliberately separate from the validated model-file status. */
sealed interface LlmRuntimeReadiness {
    data object Unknown : LlmRuntimeReadiness
    data object Checking : LlmRuntimeReadiness
    data class Ready(val runtimeVersion: String) : LlmRuntimeReadiness
    data class Unavailable(val userMessage: String, val technicalMessage: String? = null, val retryable: Boolean = true) :
        LlmRuntimeReadiness
}

interface PromptAssetProvider {
    fun getCounselorPrompt(): PromptAsset

    class Static(private val asset: PromptAsset) : PromptAssetProvider {
        override fun getCounselorPrompt(): PromptAsset = asset
    }
}

/**
 * Interface-First on-device LLM engine (e.g. MediaPipe / Gemma 4 E2B).
 */
interface OnDeviceLlmEngine {
    fun readiness(): LlmReadiness

    /** Emits runtime health independently from the model-file checksum state. */
    fun runtimeReadiness(): StateFlow<LlmRuntimeReadiness> = MutableStateFlow(LlmRuntimeReadiness.Unknown)

    suspend fun preflight(): LlmRuntimeReadiness = when (val current = readiness()) {
        is LlmReadiness.Ready -> LlmRuntimeReadiness.Ready(current.modelVersion)
        else -> LlmRuntimeReadiness.Unavailable(
            userMessage = "AI lokal belum siap digunakan.",
            technicalMessage = current.toString(),
            retryable = true
        )
    }

    suspend fun load(): LlmReadiness = readiness()

    suspend fun unload() = Unit

    fun cancelGeneration() = Unit

    fun shutdown() = Unit

    suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState>
}

sealed interface LlmReadiness {
    data class Ready(val modelVersion: String) : LlmReadiness
    data object ModelMissing : LlmReadiness
    data class ModelCorrupted(val reason: String) : LlmReadiness
    data class InitializationFailed(val reason: String) : LlmReadiness
}

enum class GenerationContentMode {
    /** The text is a new streaming fragment that must be appended. */
    DELTA,

    /** The text is the complete response accumulated by the producer. */
    SNAPSHOT
}

sealed interface LlmGenerationState {
    /**
     * A response fragment from the engine. Production LiteRT-LM emits DELTA
     * fragments; SNAPSHOT is available for engines that already aggregate text.
     */
    data class Content(val text: String, val mode: GenerationContentMode = GenerationContentMode.DELTA) : LlmGenerationState
    data class Unavailable(val readiness: LlmReadiness) : LlmGenerationState
    data class Failed(val reason: String) : LlmGenerationState
    data object Cancelled : LlmGenerationState
}

/**
 * Interface-First local RAG retriever.
 */
interface RagRetriever {
    suspend fun retrieveRelevantPassages(
        query: String,
        maxResults: Int = 5,
        /** Legacy call-site parameter; canonical implementations use an injected benchmark policy. */
        minRelevanceThreshold: Float = Float.NaN
    ): RagRetrievalResult
}

sealed interface RagRetrievalResult {
    data class Retrieved(
        val citations: List<KnowledgeCitation>,
        val method: RetrievalMethod = RetrievalMethod.SEMANTIC_HYBRID,
        val sourceTier: EvidenceSourceTier = EvidenceSourceTier.APP_RAG
    ) : RagRetrievalResult
    data class BelowThreshold(
        val bestScore: Float?,
        val method: RetrievalMethod? = null,
        val sourceTier: EvidenceSourceTier = EvidenceSourceTier.NONE
    ) : RagRetrievalResult
    data class Unavailable(val reason: String, val sourceTier: EvidenceSourceTier = EvidenceSourceTier.NONE) : RagRetrievalResult
}

enum class RetrievalMethod {
    SEMANTIC_HYBRID,
    LEXICAL_ONLY
}

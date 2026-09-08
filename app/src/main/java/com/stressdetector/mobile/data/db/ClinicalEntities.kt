package com.stressdetector.mobile.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─── Verbatim Domain Entities ───────────────────────────────────────────────

@Entity(tableName = "verbatim_sessions")
data class VerbatimSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val audioFilePath: String,
    val consentGranted: Boolean,
    val durationSeconds: Long,
    val status: String, // "RECORDING", "COMPLETED", "TRANSCRIBED", "ANALYZED"
    @ColumnInfo(defaultValue = "'Sesi Verbatim'") val sessionName: String = "Sesi Verbatim",
    @ColumnInfo(defaultValue = "''") val artifactFolderPath: String = "",
    @ColumnInfo(defaultValue = "'PENDING'") val finalizationStatus: String = "PENDING",
    @ColumnInfo(defaultValue = "'NOT_REQUESTED'") val summaryStatus: String = "NOT_REQUESTED",
    /** Session-to-audio gap provenance; WAV bytes remain pause-free and immutable. */
    @ColumnInfo(defaultValue = "'[]'") val audioGapsJson: String = "[]"
)

@Entity(tableName = "transcript_segments")
data class TranscriptSegmentEntity(
    @PrimaryKey val segmentId: String,
    val sessionId: String,
    val speakerId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val rawText: String,
    val cleanedText: String?,
    val confidence: Float,
    val language: String
)

@Entity(tableName = "psychology_codes")
data class PsychologyCodeEntity(
    @PrimaryKey val codeId: String,
    val segmentId: String,
    val category: String, // e.g. "Cognitive Distortion", "Arousal Indicator", "Coping Strategy"
    val codeLabel: String,
    val confidence: Float,
    val isAiSuggestion: Boolean,
    val citationId: String?
)

@Entity(tableName = "ai_analyses")
data class AiAnalysisEntity(
    @PrimaryKey val analysisId: String,
    val sessionId: String,
    val version: Int,
    val summaryText: String,
    val keyInsightsJson: String,
    val generatedAtMillis: Long
)

// ─── Live Monitor Domain Entities ───────────────────────────────────────────

@Entity(tableName = "live_sessions")
data class LiveSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val durationSeconds: Long,
    val consentGranted: Boolean,
    val averageStressProbability: Float
)

@Entity(tableName = "realtime_inference_records")
data class RealtimeInferenceRecordEntity(
    @PrimaryKey val recordId: String,
    val sessionId: String,
    val timestampMs: Long,
    val stressProbability: Float?,
    val label: String,
    val qualityScore: Float,
    val isValidWindow: Boolean,
    val channel: String = "GREEN"
)

@Entity(tableName = "divergence_events")
data class DivergenceEventEntity(
    @PrimaryKey val eventId: String,
    val sessionId: String,
    val timestampMs: Long,
    val delta: Float?,
    val currentProbability: Float?,
    val baselineProbability: Float?,
    val copyText: String,
    val channel: String = "GREEN",
    val markerElapsedMillis: Long = 0L,
    val episodeStartElapsedMillis: Long = 0L,
    val episodeEndElapsedMillis: Long = 0L,
    val contextWindowStartElapsedMillis: Long = 0L,
    val contextWindowEndElapsedMillis: Long = 0L,
    val confidence: Float = 0f,
    val signalQuality: Float = 0f,
    val sourceWindowId: String? = null,
    val transcriptSegmentId: String? = null,
    val markerType: String = "MODEL_AROUSAL",
    val bpm: Float? = null,
    val baselineBpm: Float? = null,
    val peakBpm: Float? = null,
    val bpmDelta: Float? = null,
    val bpmTrend: String? = null,
    val sourceChannels: String = "",
    val markerConfigVersion: String = "physiological-markers-v1",
    /** Audio evidence fields are populated only for acoustic marker types. */
    val audioRmsDb: Float? = null,
    val audioBaselineRmsDb: Float? = null,
    val audioBaselineDeviationDb: Float? = null,
    val audioZeroCrossingRate: Float? = null,
    val audioClippingRatio: Float? = null,
    val audioFiniteSampleRatio: Float? = null,
    val audioAnalysisVersion: String? = null
)

// ─── Shared Annotations Entity ────────────────────────────────────────────────

@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey val annotationId: String,
    val sessionId: String,
    val timestampMs: Long,
    val text: String,
    val author: String, // "user" or "ai_suggestion"
    val citationId: String?
)

// ─── AI Counselor Domain Entities ───────────────────────────────────────────

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val chatSessionId: String,
    val title: String,
    val startedAtMillis: Long,
    val lastMessageAtMillis: Long
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val messageId: String,
    val chatSessionId: String,
    val sender: String, // "user", "counselor", "crisis_system"
    val content: String,
    val timestampMs: Long,
    val citationsJson: String?, // JSON list of KnowledgeCitation
    val stressContextJson: String? = null,
    val promptVersion: String? = null,
    val retrievalMode: String? = null,
    val inputMode: String? = null
)

@Entity(tableName = "counselor_persona")
data class CounselorPersonaEntity(@PrimaryKey val profileId: String, val markdown: String, val version: Int, val updatedAtMillis: Long)

// ─── Consent & Privacy Entity ────────────────────────────────────────────────

@Entity(tableName = "user_consents")
data class UserConsentEntity(
    @PrimaryKey val consentType: String, // "AUDIO_RECORDING", "LIVE_MONITORING", "AI_COUNSELING"
    val grantedAtMillis: Long,
    val isGranted: Boolean
)

/**
 * Non-sensitive lifecycle metadata for one AI+ reminder occurrence.
 *
 * The generated message and display name deliberately do not live in Room;
 * they are stored in the Keystore-backed reminder secret store and are only
 * materialized at an explicit foreground display or notification delivery.
 */
@Entity(
    tableName = "ai_reminders",
    indices = [
        Index(value = ["occurrenceKey"], unique = true),
        Index(value = ["sourceSessionId"], unique = true)
    ]
)
data class AiReminderEntity(
    @PrimaryKey val reminderId: String,
    val occurrenceKey: String,
    val sourceSessionId: String,
    val scheduledAtMillis: Long,
    val zoneId: String,
    val status: String,
    val createdAtMillis: Long,
    val generatedAtMillis: Long? = null,
    val deliveredAtMillis: Long? = null,
    val modelVersion: String? = null,
    val policyVersion: String,
    val payloadKey: String? = null,
    val lastErrorCode: String? = null
)

// ─── Document Library Domain Entities ───────────────────────────────────────

@Entity(tableName = "clinical_documents")
data class ClinicalDocumentEntity(
    @PrimaryKey val documentId: String,
    val title: String,
    val filePath: String,
    val uploadedAtMillis: Long,
    val isProcessed: Boolean,
    val sourceFileName: String? = null,
    val sourceSha256: String? = null,
    val ingestionVersion: String = "pdf-page-chunk-v1",
    val embeddingModelVersion: String? = null,
    val embeddingStatus: String = "UNAVAILABLE",
    val processingState: String = "PENDING",
    val processingError: String? = null,
    /** Stable code for safe UI/worker handling; the message remains diagnostic only. */
    val processingErrorCode: String? = null,
    /** Timestamp of the current ingestion/reindex attempt, never the upload timestamp. */
    val processingStartedAtMillis: Long = 0L,
    /** Last state mutation for stale-work recovery. */
    val processingUpdatedAtMillis: Long = 0L,
    /** Increments for each ingestion/reindex attempt and scopes unique work. */
    val processingRevision: Int = 0,
    /** Per-page text/OCR audit summary; no source content is duplicated here. */
    val extractionStatus: String = "PENDING",
    /** OCR engine/version or explicit unavailable reason for scanned pages. */
    val ocrProvenance: String? = null,
    /** JSON page/status audit, for example TEXT, OCR, OCR_UNAVAILABLE. */
    val pageExtractionJson: String? = null,
    /** Persisted chunk count makes ingestion results inspectable without recomputing. */
    val indexedChunkCount: Int = 0
)

@Entity(tableName = "document_chunks")
data class DocumentChunkEntity(
    @PrimaryKey val chunkId: String,
    val documentId: String,
    val chunkIndex: Int,
    val textContent: String,
    val embeddingBlob: ByteArray?, // We store the embedding as a BLOB
    val sourceTitle: String? = null,
    val sourcePage: Int? = null,
    val sourceLocator: String? = null,
    val embeddingVersion: String? = null,
    /** Token/character provenance from the canonical chunk policy. */
    val charStart: Int? = null,
    val charEnd: Int? = null,
    val tokenStart: Int? = null,
    val tokenEnd: Int? = null,
    val chunkPolicyVersion: String = "legacy"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DocumentChunkEntity

        if (chunkId != other.chunkId) return false
        if (documentId != other.documentId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (textContent != other.textContent) return false
        if (sourceTitle != other.sourceTitle) return false
        if (sourcePage != other.sourcePage) return false
        if (sourceLocator != other.sourceLocator) return false
        if (embeddingVersion != other.embeddingVersion) return false
        if (charStart != other.charStart) return false
        if (charEnd != other.charEnd) return false
        if (tokenStart != other.tokenStart) return false
        if (tokenEnd != other.tokenEnd) return false
        if (chunkPolicyVersion != other.chunkPolicyVersion) return false
        if (embeddingBlob != null) {
            if (other.embeddingBlob == null) return false
            if (!embeddingBlob.contentEquals(other.embeddingBlob)) return false
        } else if (other.embeddingBlob != null) {
            return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + textContent.hashCode()
        result = 31 * result + (embeddingBlob?.contentHashCode() ?: 0)
        result = 31 * result + (sourceTitle?.hashCode() ?: 0)
        result = 31 * result + (sourcePage ?: 0)
        result = 31 * result + (sourceLocator?.hashCode() ?: 0)
        result = 31 * result + (embeddingVersion?.hashCode() ?: 0)
        result = 31 * result + (charStart ?: 0)
        result = 31 * result + (charEnd ?: 0)
        result = 31 * result + (tokenStart ?: 0)
        result = 31 * result + (tokenEnd ?: 0)
        result = 31 * result + chunkPolicyVersion.hashCode()
        return result
    }
}

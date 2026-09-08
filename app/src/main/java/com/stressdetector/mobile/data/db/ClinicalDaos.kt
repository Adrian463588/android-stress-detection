package com.stressdetector.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface VerbatimDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: VerbatimSessionEntity)

    @Query("SELECT * FROM verbatim_sessions ORDER BY startedAtMillis DESC")
    fun getAllSessions(): Flow<List<VerbatimSessionEntity>>

    @Query("SELECT * FROM verbatim_sessions ORDER BY startedAtMillis DESC")
    suspend fun getAllSessionsOnce(): List<VerbatimSessionEntity>

    @Query("SELECT * FROM verbatim_sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): VerbatimSessionEntity?

    @Query("SELECT * FROM verbatim_sessions WHERE sessionId = :sessionId")
    fun observeSessionById(sessionId: String): Flow<VerbatimSessionEntity?>

    @androidx.room.Update
    suspend fun updateSession(session: VerbatimSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptSegmentEntity>)

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY startOffsetMs ASC")
    fun getSegmentsForSession(sessionId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("UPDATE transcript_segments SET speakerId = :speakerId WHERE segmentId = :segmentId AND sessionId = :sessionId")
    suspend fun updateSpeakerLabel(sessionId: String, segmentId: String, speakerId: String): Int

    @Query("UPDATE transcript_segments SET cleanedText = :cleanedText WHERE segmentId = :segmentId AND sessionId = :sessionId")
    suspend fun updateCleanedText(sessionId: String, segmentId: String, cleanedText: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPsychologyCodes(codes: List<PsychologyCodeEntity>)

    @Query("SELECT * FROM psychology_codes WHERE segmentId IN (SELECT segmentId FROM transcript_segments WHERE sessionId = :sessionId)")
    suspend fun getCodesForSession(sessionId: String): List<PsychologyCodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysis(analysis: AiAnalysisEntity)

    @Query("SELECT * FROM ai_analyses WHERE sessionId = :sessionId ORDER BY version DESC LIMIT 1")
    suspend fun getLatestAnalysis(sessionId: String): AiAnalysisEntity?

    @Query("DELETE FROM verbatim_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM transcript_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegments(sessionId: String)

    @Query("DELETE FROM psychology_codes WHERE segmentId IN (SELECT segmentId FROM transcript_segments WHERE sessionId = :sessionId)")
    suspend fun deleteCodes(sessionId: String)

    @Query("DELETE FROM ai_analyses WHERE sessionId = :sessionId")
    suspend fun deleteAnalyses(sessionId: String)

    @Transaction
    suspend fun deleteSessionGraph(sessionId: String) {
        deleteCodes(sessionId)
        deleteAnalyses(sessionId)
        deleteSegments(sessionId)
        deleteSession(sessionId)
    }
}

@Dao
interface LiveMonitorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiveSession(session: LiveSessionEntity)

    @Query("SELECT * FROM live_sessions WHERE sessionId = :sessionId")
    suspend fun getLiveSessionById(sessionId: String): LiveSessionEntity?

    @androidx.room.Update
    suspend fun updateLiveSession(session: LiveSessionEntity)

    @Query("SELECT * FROM live_sessions ORDER BY startedAtMillis DESC")
    fun getAllLiveSessions(): Flow<List<LiveSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: RealtimeInferenceRecordEntity)

    @Query("SELECT * FROM realtime_inference_records WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    fun getRecordsForSession(sessionId: String): Flow<List<RealtimeInferenceRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDivergenceEvent(event: DivergenceEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDivergenceEvents(events: List<DivergenceEventEntity>)

    @Query("SELECT * FROM divergence_events WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    fun getDivergenceEventsForSession(sessionId: String): Flow<List<DivergenceEventEntity>>

    @Query("DELETE FROM divergence_events WHERE sessionId = :sessionId")
    suspend fun deleteDivergenceEvents(sessionId: String)

    @Transaction
    suspend fun replaceDivergenceEvents(sessionId: String, events: List<DivergenceEventEntity>) {
        deleteDivergenceEvents(sessionId)
        if (events.isNotEmpty()) insertDivergenceEvents(events)
    }

    @Query("DELETE FROM realtime_inference_records WHERE sessionId = :sessionId")
    suspend fun deleteRealtimeRecords(sessionId: String)

    @Query("DELETE FROM live_sessions WHERE sessionId = :sessionId")
    suspend fun deleteLiveSession(sessionId: String)

    @Transaction
    suspend fun deleteSessionGraph(sessionId: String) {
        deleteDivergenceEvents(sessionId)
        deleteRealtimeRecords(sessionId)
        deleteLiveSession(sessionId)
    }
}

@Dao
interface AnnotationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotation(annotation: AnnotationEntity)

    @Query("SELECT * FROM annotations WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getAnnotationsForSession(sessionId: String): List<AnnotationEntity>

    @Query("DELETE FROM annotations WHERE annotationId = :annotationId")
    suspend fun deleteAnnotation(annotationId: String)

    @Query("DELETE FROM annotations WHERE sessionId = :sessionId")
    suspend fun deleteAnnotationsForSession(sessionId: String)
}

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions ORDER BY lastMessageAtMillis DESC")
    fun getAllChatSessions(): Flow<List<ChatSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("UPDATE chat_sessions SET lastMessageAtMillis = :timestampMs WHERE chatSessionId = :sessionId")
    suspend fun touchChatSession(sessionId: String, timestampMs: Long)

    /**
     * A crisis response must never be persisted as two independently visible
     * turns. Reusing message IDs makes a retry idempotent through REPLACE.
     */
    @Transaction
    suspend fun persistCrisisTurn(session: ChatSessionEntity, userMessage: ChatMessageEntity, crisisMessage: ChatMessageEntity) {
        insertChatSession(session)
        insertMessage(userMessage)
        insertMessage(crisisMessage)
        touchChatSession(session.chatSessionId, crisisMessage.timestampMs)
    }

    @Query("SELECT * FROM chat_messages WHERE chatSessionId = :chatSessionId ORDER BY timestampMs ASC")
    fun getMessagesForSession(chatSessionId: String): Flow<List<ChatMessageEntity>>

    @Query("DELETE FROM chat_messages WHERE chatSessionId = :chatSessionId")
    suspend fun deleteMessagesForSession(chatSessionId: String)

    @Query("DELETE FROM chat_sessions WHERE chatSessionId = :chatSessionId")
    suspend fun deleteChatSession(chatSessionId: String)

    @Transaction
    suspend fun deleteChatSessionWithMessages(chatSessionId: String) {
        deleteMessagesForSession(chatSessionId)
        deleteChatSession(chatSessionId)
    }

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllChatSessions()

    @Transaction
    suspend fun deleteAllChatsWithMessages() {
        deleteAllMessages()
        deleteAllChatSessions()
    }
}

@Dao
interface CounselorPersonaDao {
    @Query("SELECT * FROM counselor_persona WHERE profileId = :profileId LIMIT 1")
    fun observe(profileId: String): Flow<CounselorPersonaEntity?>

    @Query("SELECT * FROM counselor_persona WHERE profileId = :profileId LIMIT 1")
    suspend fun get(profileId: String): CounselorPersonaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(persona: CounselorPersonaEntity)
}

@Dao
interface ConsentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConsent(consent: UserConsentEntity)

    @Query("SELECT * FROM user_consents WHERE consentType = :consentType")
    suspend fun getConsent(consentType: String): UserConsentEntity?

    @Query("SELECT * FROM user_consents WHERE consentType = :consentType")
    fun observeConsent(consentType: String): Flow<UserConsentEntity?>
}

@Dao
@Suppress("TooManyFunctions")
interface AiReminderDao {
    @Query("SELECT * FROM ai_reminders ORDER BY scheduledAtMillis DESC, createdAtMillis DESC LIMIT :limit")
    fun observeLatest(limit: Int = 10): Flow<List<AiReminderEntity>>

    @Query("SELECT * FROM ai_reminders ORDER BY scheduledAtMillis DESC, createdAtMillis DESC LIMIT 1")
    suspend fun latest(): AiReminderEntity?

    @Query("SELECT * FROM ai_reminders WHERE reminderId = :reminderId LIMIT 1")
    suspend fun getById(reminderId: String): AiReminderEntity?

    @Query("SELECT * FROM ai_reminders WHERE sourceSessionId = :sourceSessionId LIMIT 1")
    suspend fun getBySourceSessionId(sourceSessionId: String): AiReminderEntity?

    @Query("SELECT * FROM ai_reminders WHERE occurrenceKey = :occurrenceKey LIMIT 1")
    suspend fun getByOccurrenceKey(occurrenceKey: String): AiReminderEntity?

    @Query("SELECT * FROM ai_reminders WHERE status IN ('SCHEDULED', 'GENERATING', 'GENERATED') ORDER BY scheduledAtMillis ASC")
    suspend fun getPending(): List<AiReminderEntity>

    @Query("SELECT * FROM ai_reminders WHERE deliveredAtMillis IS NULL ORDER BY scheduledAtMillis ASC")
    suspend fun getUndelivered(): List<AiReminderEntity>

    @Query("SELECT * FROM ai_reminders WHERE deliveredAtMillis >= :sinceMillis ORDER BY deliveredAtMillis DESC LIMIT 1")
    suspend fun latestDeliveredSince(sinceMillis: Long): AiReminderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: AiReminderEntity): Long

    @Query(
        "UPDATE ai_reminders SET status = :status, lastErrorCode = :errorCode " +
            "WHERE reminderId = :reminderId"
    )
    suspend fun updateStatus(reminderId: String, status: String, errorCode: String? = null): Int

    @Query(
        "UPDATE ai_reminders SET status = :status, generatedAtMillis = :generatedAtMillis, " +
            "modelVersion = :modelVersion, lastErrorCode = NULL WHERE reminderId = :reminderId"
    )
    suspend fun markGenerated(reminderId: String, status: String, generatedAtMillis: Long, modelVersion: String?): Int

    @Query(
        "UPDATE ai_reminders SET status = :status, deliveredAtMillis = :deliveredAtMillis, " +
            "lastErrorCode = NULL WHERE reminderId = :reminderId"
    )
    suspend fun markDelivered(reminderId: String, status: String, deliveredAtMillis: Long): Int

    @Query("DELETE FROM ai_reminders WHERE sourceSessionId = :sourceSessionId AND deliveredAtMillis IS NULL")
    suspend fun deletePendingForSource(sourceSessionId: String): Int

    @Query("DELETE FROM ai_reminders WHERE deliveredAtMillis IS NULL")
    suspend fun deletePending(): Int

    @Query("DELETE FROM ai_reminders")
    suspend fun deleteAll()
}

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: ClinicalDocumentEntity)

    @Query("SELECT * FROM clinical_documents ORDER BY uploadedAtMillis DESC")
    fun getAllDocuments(): Flow<List<ClinicalDocumentEntity>>

    @Query("SELECT * FROM clinical_documents WHERE documentId = :documentId")
    suspend fun getDocument(documentId: String): ClinicalDocumentEntity?

    @Query("SELECT * FROM clinical_documents WHERE sourceSha256 = :sha256 LIMIT 1")
    suspend fun findDocumentBySha256(sha256: String): ClinicalDocumentEntity?

    @Query("UPDATE clinical_documents SET filePath = :filePath WHERE documentId = :documentId")
    suspend fun updateSourceReference(documentId: String, filePath: String)

    @Query(
        "UPDATE clinical_documents SET isProcessed = :isProcessed, processingState = :processingState, processingError = :processingError, embeddingStatus = :embeddingStatus WHERE documentId = :documentId"
    )
    suspend fun updateProcessingState(
        documentId: String,
        isProcessed: Boolean,
        processingState: String,
        processingError: String? = null,
        embeddingStatus: String = "UNAVAILABLE"
    )

    @Query(
        "UPDATE clinical_documents SET isProcessed = :isProcessed, processingState = :processingState, " +
            "processingError = :processingError, processingErrorCode = :processingErrorCode, " +
            "embeddingStatus = :embeddingStatus, processingUpdatedAtMillis = :processingUpdatedAtMillis " +
            "WHERE documentId = :documentId"
    )
    suspend fun updateProcessingStateDetailed(
        documentId: String,
        isProcessed: Boolean,
        processingState: String,
        processingError: String?,
        processingErrorCode: String?,
        embeddingStatus: String,
        processingUpdatedAtMillis: Long
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>)

    @Transaction
    suspend fun insertDocumentWithChunks(document: ClinicalDocumentEntity, chunks: List<DocumentChunkEntity>) {
        insertDocument(document)
        insertChunks(chunks)
    }

    /**
     * Publishes one complete index generation. Retrieval only observes the
     * READY document state after the replacement transaction commits.
     */
    @Transaction
    suspend fun replaceDocumentWithChunks(document: ClinicalDocumentEntity, chunks: List<DocumentChunkEntity>) {
        deleteChunksForDocument(document.documentId)
        insertDocument(document)
        if (chunks.isNotEmpty()) insertChunks(chunks)
    }

    @Query("SELECT * FROM document_chunks WHERE documentId = :documentId ORDER BY chunkIndex ASC")
    suspend fun getChunksForDocument(documentId: String): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks")
    suspend fun getAllChunks(): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks ORDER BY documentId ASC, chunkIndex ASC LIMIT :limit OFFSET :offset")
    suspend fun getChunksPage(limit: Int, offset: Int): List<DocumentChunkEntity>

    @Query("DELETE FROM clinical_documents WHERE documentId = :documentId")
    suspend fun deleteDocument(documentId: String)

    @Query("DELETE FROM document_chunks WHERE documentId = :documentId")
    suspend fun deleteChunksForDocument(documentId: String)

    @Transaction
    suspend fun deleteDocumentWithChunks(documentId: String) {
        deleteChunksForDocument(documentId)
        deleteDocument(documentId)
    }
}

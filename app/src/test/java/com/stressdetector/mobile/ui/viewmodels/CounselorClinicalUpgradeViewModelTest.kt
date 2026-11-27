package com.stressdetector.mobile.ui.viewmodels

import com.stressdetector.mobile.data.db.ChatDao
import com.stressdetector.mobile.data.db.ChatMessageEntity
import com.stressdetector.mobile.data.db.ChatSessionEntity
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentScore
import com.stressdetector.mobile.domain.assessment.AssessmentValidityStatus
import com.stressdetector.mobile.domain.assessment.InstrumentImplementationStatus
import com.stressdetector.mobile.domain.assessment.PsychometricInstrumentRegistry
import com.stressdetector.mobile.domain.engine.CounselingInteractionMode
import com.stressdetector.mobile.domain.engine.CounselorGenerationRequest
import com.stressdetector.mobile.domain.engine.CounselorPreferencesStore
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.LlmGenerationState
import com.stressdetector.mobile.domain.engine.LlmReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import com.stressdetector.mobile.domain.engine.PlanReviewStatus
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever
import com.stressdetector.mobile.domain.engine.ResponseProfile
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.VerbatimProsodyEvidence
import com.stressdetector.mobile.domain.engine.VerbatimSessionDetail
import com.stressdetector.mobile.domain.engine.VerbatimSessionRepository
import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import com.stressdetector.mobile.ml.assets.AiAssetEntry
import com.stressdetector.mobile.ml.assets.AiAssetKind
import com.stressdetector.mobile.ml.llm.ModelManager
import com.stressdetector.mobile.ml.llm.ModelReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class CounselorClinicalUpgradeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `planning mode integrates assessment verbatim and KB citations and allows plan review`() = runTest {
        val persisted = mutableListOf<ChatMessageEntity>()
        val capturedRequests = mutableListOf<CounselorGenerationRequest>()
        val tiu5 = AssessmentResult(
            resultId = "res-tiu5",
            instrumentId = "tiu_5",
            title = "TIU-5",
            version = "1.0",
            sourceTitle = "Bank Psikotes",
            sourceUrl = "asset://tiu_5",
            sourceLocator = "tiu_5",
            createdAtMillis = 1_700_000_000_000L,
            answered = 30,
            itemCount = 30,
            scores = listOf(AssessmentScore("Skor Mentah", 4.0, 0.0, 30.0, "Kurang Sekali (KS)")),
            interpretation = "Tantangan analogi figural cepat.",
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = InstrumentImplementationStatus.RAW_SCORE_ONLY,
            validityStatus = AssessmentValidityStatus.VALID,
            aiContextAllowed = true
        )
        val assessmentRepo = Mockito.mock(AssessmentRepository::class.java)
        Mockito.`when`(assessmentRepo.resultForDiscussion(Mockito.anyString())).thenReturn(tiu5)
        Mockito.`when`(assessmentRepo.getAllResults()).thenReturn(listOf(tiu5))

        val sessionSummary = VerbatimSessionSummary(
            sessionId = "v-1",
            sessionName = "Wawancara Awal",
            startedAtMillis = 1_700_000_000_000L,
            endedAtMillis = 1_700_000_300_000L,
            durationSeconds = 300L,
            audioFilePath = "",
            artifactFolderPath = "",
            finalizationStatus = "COMPLETED",
            summaryStatus = "COMPLETED"
        )
        val verbatimRepo = object : VerbatimSessionRepository {
            override fun observeSessions(): Flow<List<VerbatimSessionSummary>> = flowOf(listOf(sessionSummary))
            override fun observeSession(sessionId: String): Flow<VerbatimSessionDetail?> = flowOf(
                VerbatimSessionDetail(
                    summary = sessionSummary,
                    transcript = listOf(
                        SpeechSegment(
                            id = "s-1",
                            speakerId = "Klien",
                            startOffsetMs = 0L,
                            endOffsetMs = 3_000L,
                            startTimestampUtc = 1_700_000_000_000L,
                            endTimestampUtc = 1_700_000_003_000L,
                            rawText = "Saya sering panik saat mengerjakan soal batas waktu.",
                            confidence = 0.9f,
                            prosody = VerbatimProsodyEvidence(
                                features = mapOf("F0semitoneFrom27.5Hz" to 33.5),
                                frameCount = 20,
                                voicedFrameCount = 18
                            )
                        )
                    ),
                    divergenceEvents = emptyList()
                )
            )

            override suspend fun getLatestSessionNotes(sessionId: String): String? = "Catatan: responsif latihan REBT."
            override suspend fun deleteSession(sessionId: String): Result<Unit> = Result.success(Unit)
            override suspend fun deleteAllSessions(): Result<Unit> = Result.success(Unit)
        }

        val kbCitation = KnowledgeCitation(
            citationId = "bk03_rebt",
            chunkId = "bk03_rebt",
            sourceTitle = "Teori dan Intervensi Konseling",
            section = "REBT ABCDE",
            text = "Model ABCDE membantu klien menantang keyakinan irasional perfeksionis.",
            relevanceScore = 0.91f,
            sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
        )
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")
            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> {
                capturedRequests += request
                return flow {
                    emit(LlmGenerationState.Content("1. Ringkasan Data: Skor TIU-5 KS.\n2. Intervensi: Latihan REBT ABCDE [S1|bk03_rebt]."))
                }
            }
        }
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = object : RagRetriever {
                override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                    RagRetrievalResult.Retrieved(listOf(kbCitation), sourceTier = EvidenceSourceTier.BUNDLED_LOCAL)
            },
            chatDao = inMemoryChatDao(persisted),
            modelManager = readyTestModelManager(),
            ioDispatcher = dispatcher,
            assessmentRepository = assessmentRepo,
            verbatimSessionRepository = verbatimRepo
        )
        advanceUntilIdle()

        viewModel.setInteractionMode(CounselingInteractionMode.COUNSELOR_PLANNING)
        viewModel.sendMessage("Bahas hasil TIU-5 dan buatkan rencana terapi REBT")
        advanceUntilIdle()

        assertEquals(1, capturedRequests.size)
        val req = capturedRequests.single()
        assertEquals(CounselingInteractionMode.COUNSELOR_PLANNING, req.interactionMode)
        assertTrue(req.allAssessmentSummary.contains("[tiu_5]"))
        assertTrue(req.verbatimContext.contains("sering panik"))
        assertTrue(viewModel.uiState.value.activeContextSources.any { it.contains("Verbatim+") })

        val reply = viewModel.uiState.value.messages.single { it.sender == "counselor" }
        assertEquals(PlanReviewStatus.PENDING, reply.planReviewStatus)

        viewModel.reviewCounselingPlan(reply.id, PlanReviewStatus.REVISION_REQUESTED, "Tambahkan target sesi minggu 2")
        advanceUntilIdle()
        val updated = viewModel.uiState.value.messages.single { it.id == reply.id }
        assertEquals(PlanReviewStatus.REVISION_REQUESTED, updated.planReviewStatus)
        assertEquals("Tambahkan target sesi minggu 2", updated.planReviewNote)
    }

    @Test
    fun `truncation preserves partial response and continueResponse stitches seamless continuation`() = runTest {
        val persisted = mutableListOf<ChatMessageEntity>()
        var turnCount = 0
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")
            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = flow {
                turnCount++
                if (turnCount == 1) {
                    emit(LlmGenerationState.Content("A".repeat(1_205)))
                } else {
                    assertTrue(request.continuationPrefix.isNotBlank())
                    emit(LlmGenerationState.Content("AAA lanjutan teknik CBT hingga tuntas."))
                }
            }
        }
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = object : RagRetriever {
                override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                    RagRetrievalResult.Unavailable("none")
            },
            chatDao = inMemoryChatDao(persisted),
            modelManager = readyTestModelManager(),
            ioDispatcher = dispatcher
        )
        advanceUntilIdle()

        viewModel.setResponseProfile(ResponseProfile.SHORT)
        viewModel.sendMessage("Saya merasa tegang akhir-akhir ini, bantu latihan relaksasi")
        advanceUntilIdle()

        val firstMsg = viewModel.uiState.value.messages.single { it.sender == "counselor" }
        assertTrue(firstMsg.isTruncated)
        assertEquals(CounselorPhase.SUCCESS, viewModel.uiState.value.phase)

        viewModel.continueResponse(firstMsg.id)
        advanceUntilIdle()

        val continuedMsg = viewModel.uiState.value.messages.single { it.id == firstMsg.id }
        assertFalse(continuedMsg.isTruncated)
        assertTrue(continuedMsg.content.contains("lanjutan teknik CBT hingga tuntas."))
    }

    @Test
    fun `consecutive general turns do not repeat redundant disclaimers and shared preferences synchronize cleanly`() = runTest {
        val persisted = mutableListOf<ChatMessageEntity>()
        val preferencesStore = CounselorPreferencesStore.InMemory()
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")
            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = flowOf(
                LlmGenerationState.Content("Coba teknik napas 4-7-8 selama dua menit.")
            )
        }
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = object : RagRetriever {
                override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                    RagRetrievalResult.Unavailable("none")
            },
            chatDao = inMemoryChatDao(persisted),
            modelManager = readyTestModelManager(),
            ioDispatcher = dispatcher,
            preferencesStore = preferencesStore
        )
        advanceUntilIdle()

        preferencesStore.setInteractionMode(CounselingInteractionMode.ASSESSMENT_INTERPRETATION)
        preferencesStore.setResponseProfile(ResponseProfile.DETAILED)
        preferencesStore.setIncludeBehavioralContext(false)
        advanceUntilIdle()
        assertEquals(CounselingInteractionMode.ASSESSMENT_INTERPRETATION, viewModel.uiState.value.interactionMode)
        assertEquals(ResponseProfile.DETAILED, viewModel.uiState.value.responseProfile)
        assertFalse(viewModel.uiState.value.includeBehavioralContext)

        preferencesStore.setInteractionMode(CounselingInteractionMode.GENERAL_COUNSELING)
        preferencesStore.setResponseProfile(ResponseProfile.STANDARD)
        advanceUntilIdle()

        viewModel.sendMessage("Saya cemas menghadapi ujian")
        advanceUntilIdle()
        viewModel.sendMessage("Bagaimana langkah keduanya?")
        advanceUntilIdle()

        val replies = viewModel.uiState.value.messages.filter { it.sender == "counselor" }
        assertEquals(2, replies.size)
        assertTrue(replies[0].content.contains("bukan diagnosis"))
        assertFalse(replies[1].content.contains("bukan diagnosis"))
    }

    private fun readyTestModelManager(): ModelManager {
        val manager = Mockito.mock(ModelManager::class.java)
        val entry = AiAssetEntry(
            kind = AiAssetKind.LLM,
            fileName = ModelManager.MODEL_FILE,
            source = ModelManager.MODEL_SOURCE,
            license = "Apache-2.0",
            version = "test",
            sizeBytes = 1L,
            sha256 = ModelManager.MODEL_SHA256
        )
        Mockito.`when`(manager.readiness).thenReturn(MutableStateFlow(ModelReadiness.Ready(entry)))
        return manager
    }

    private fun inMemoryChatDao(messages: MutableList<ChatMessageEntity>) = object : ChatDao {
        override suspend fun insertChatSession(session: ChatSessionEntity) = Unit
        override fun getAllChatSessions(): Flow<List<ChatSessionEntity>> = flowOf(emptyList())
        override suspend fun insertMessage(message: ChatMessageEntity) {
            messages.removeAll { it.messageId == message.messageId }
            messages += message
        }

        override suspend fun touchChatSession(sessionId: String, timestampMs: Long) = Unit
        override fun getMessagesForSession(chatSessionId: String): Flow<List<ChatMessageEntity>> = flowOf(messages.toList())
        override suspend fun deleteMessagesForSession(chatSessionId: String) = Unit
        override suspend fun deleteChatSession(chatSessionId: String) = Unit
        override suspend fun deleteAllMessages() = Unit
        override suspend fun deleteAllChatSessions() = Unit
    }
}

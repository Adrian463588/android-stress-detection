package com.stressdetector.mobile.ui.viewmodels

import com.stressdetector.mobile.data.db.ChatDao
import com.stressdetector.mobile.data.db.ChatMessageEntity
import com.stressdetector.mobile.data.db.ChatSessionEntity
import com.stressdetector.mobile.domain.counselor.CounselorPersona
import com.stressdetector.mobile.domain.counselor.CounselorPersonaProfile
import com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository
import com.stressdetector.mobile.domain.engine.CitationSource
import com.stressdetector.mobile.domain.engine.CounselorGenerationRequest
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.LlmGenerationState
import com.stressdetector.mobile.domain.engine.LlmReadiness
import com.stressdetector.mobile.domain.engine.LlmRuntimeReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import com.stressdetector.mobile.domain.engine.RagMode
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever
import com.stressdetector.mobile.domain.search.WebGroundingGateway
import com.stressdetector.mobile.domain.search.WebGroundingResult
import com.stressdetector.mobile.domain.search.WebSearchMode
import com.stressdetector.mobile.ml.llm.ModelManager
import com.stressdetector.mobile.ml.llm.ModelReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class CounselorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `crisis is persisted before rag web or llm can run`() = runTest {
        var llmCalls = 0
        var ragCalls = 0
        var webCalls = 0
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")
            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> {
                llmCalls++
                return flowOf(LlmGenerationState.Content("unexpected"))
            }
        }
        val rag = object : RagRetriever {
            override suspend fun retrieveRelevantPassages(
                query: String,
                maxResults: Int,
                minRelevanceThreshold: Float
            ): RagRetrievalResult {
                ragCalls++
                return RagRetrievalResult.Retrieved(emptyList())
            }
        }
        val persisted = mutableListOf<ChatMessageEntity>()
        val dao = fakeDao(persisted)
        val manager = Mockito.mock(ModelManager::class.java)
        Mockito.`when`(manager.readiness).thenReturn(MutableStateFlow(ModelReadiness.Missing))
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = rag,
            chatDao = dao,
            modelManager = manager,
            ioDispatcher = dispatcher,
            webGroundingGateway = object : WebGroundingGateway {
                override suspend fun retrieveAutomatically(query: String): Result<WebGroundingResult> {
                    webCalls++
                    return Result.failure(AssertionError("web must not run for crisis"))
                }
            }
        )
        advanceUntilIdle()

        viewModel.sendMessage("saya ingin bunuh diri")
        advanceUntilIdle()

        assertEquals(0, ragCalls)
        assertEquals(0, llmCalls)
        assertEquals(0, webCalls)
        assertEquals(listOf("user", "crisis_system"), persisted.map { it.sender })
        assertTrue(persisted.last().content.contains("Keselamatanmu penting"))
        assertEquals(CounselorPhase.CRISIS_ESCALATION, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.messages.last().crisisSupportActions.isNotEmpty())
    }

    @Test
    fun `crisis escalation bypasses AI consent`() = runTest {
        val persisted = mutableListOf<ChatMessageEntity>()
        val viewModel = CounselorViewModel(
            llmEngine = unavailableLlm(),
            ragRetriever = unavailableRag(),
            chatDao = fakeDao(persisted),
            modelManager = readyManager(),
            ioDispatcher = dispatcher,
            aiConsentGranted = { false }
        )
        advanceUntilIdle()

        viewModel.sendMessage("Saya ingin bunuh diri")
        advanceUntilIdle()

        assertEquals(CounselorPhase.CRISIS_ESCALATION, viewModel.uiState.value.phase)
        assertEquals(listOf("user", "crisis_system"), persisted.map { it.sender })
    }

    @Test
    fun `crisis persistence failure keeps safety bubble and retry is idempotent`() = runTest {
        var failCrisisMessage = true
        val persisted = linkedMapOf<String, ChatMessageEntity>()
        val failingDao = object : ChatDao {
            override suspend fun insertChatSession(session: ChatSessionEntity) = Unit
            override fun getAllChatSessions(): Flow<List<ChatSessionEntity>> = flowOf(emptyList())
            override suspend fun insertMessage(message: ChatMessageEntity) {
                if (message.sender == "crisis_system" && failCrisisMessage) error("disk full")
                persisted[message.messageId] = message
            }
            override suspend fun touchChatSession(sessionId: String, timestampMs: Long) = Unit
            override fun getMessagesForSession(chatSessionId: String): Flow<List<ChatMessageEntity>> = flowOf(emptyList())
            override suspend fun deleteMessagesForSession(chatSessionId: String) = Unit
            override suspend fun deleteChatSession(chatSessionId: String) = Unit
            override suspend fun deleteAllMessages() = Unit
            override suspend fun deleteAllChatSessions() = Unit
        }
        val viewModel = CounselorViewModel(
            llmEngine = unavailableLlm(),
            ragRetriever = unavailableRag(),
            chatDao = failingDao,
            modelManager = readyManager(),
            ioDispatcher = dispatcher
        )
        advanceUntilIdle()

        viewModel.sendMessage("ingin bunuh diri")
        advanceUntilIdle()

        assertEquals(CounselorPhase.CRISIS_ESCALATION, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.crisisPersistenceState is CrisisPersistenceState.RetryableFailure)
        assertEquals(listOf("user", "crisis_system"), viewModel.uiState.value.messages.takeLast(2).map { it.sender })
        assertEquals(1, persisted.values.count { it.sender == "user" })
        assertEquals(0, persisted.values.count { it.sender == "crisis_system" })

        failCrisisMessage = false
        viewModel.retryCrisisPersistence()
        advanceUntilIdle()

        assertEquals(CrisisPersistenceState.Idle, viewModel.uiState.value.crisisPersistenceState)
        assertEquals(1, persisted.values.count { it.sender == "user" })
        assertEquals(1, persisted.values.count { it.sender == "crisis_system" })
    }

    @Test
    fun `missing signed model remains unavailable`() = runTest {
        var webCalls = 0
        val manager = Mockito.mock(ModelManager::class.java)
        Mockito.`when`(manager.readiness).thenReturn(MutableStateFlow(ModelReadiness.Missing))
        val viewModel = CounselorViewModel(
            llmEngine = object : OnDeviceLlmEngine {
                override fun readiness(): LlmReadiness = LlmReadiness.ModelMissing
                override fun runtimeReadiness() = MutableStateFlow<LlmRuntimeReadiness>(LlmRuntimeReadiness.Unknown)
                override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> =
                    flowOf(LlmGenerationState.Unavailable(LlmReadiness.ModelMissing))
            },
            ragRetriever = object : RagRetriever {
                override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                    RagRetrievalResult.Unavailable("model missing")
            },
            chatDao = fakeDao(mutableListOf()),
            modelManager = manager,
            webGroundingGateway = object : WebGroundingGateway {
                override suspend fun retrieveAutomatically(query: String): Result<WebGroundingResult> {
                    webCalls++
                    return Result.failure(AssertionError("web must not run without model"))
                }
            }
        )
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.isModelReady)
        assertTrue(viewModel.uiState.value.statusMessage!!.contains("signed model package"))

        viewModel.setWebSearchMode(WebSearchMode.Persistent)
        viewModel.sendMessage("Apa itu depresi?")
        advanceUntilIdle()

        assertEquals(0, webCalls)
        assertEquals(CounselorPhase.MODEL_MISSING, viewModel.uiState.value.phase)
    }

    @Test
    fun `no document still invokes gemma in general safe mode`() = runTest {
        var request: CounselorGenerationRequest? = null
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

            override suspend fun generate(requestValue: CounselorGenerationRequest): Flow<LlmGenerationState> {
                request = requestValue
                return flowOf(LlmGenerationState.Content("Respons dukungan umum dari Gemma."))
            }
        }
        val persisted = mutableListOf<ChatMessageEntity>()
        val rag = object : RagRetriever {
            override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                RagRetrievalResult.Unavailable("no indexed documents")
        }
        val viewModel = CounselorViewModel(llm, rag, fakeDao(persisted), readyManager())
        advanceUntilIdle()

        viewModel.sendMessage("Bagaimana cara menenangkan diri?")
        advanceUntilIdle()

        assertEquals(RagMode.GENERAL_SAFE, request?.ragMode)
        assertTrue(request?.retrievedCitations?.isEmpty() == true)
        assertTrue(persisted.any { it.sender == "counselor" && it.content.contains("Respons dukungan umum") })
    }

    @Test
    fun `saved persona is included in the next counselor generation request`() = runTest {
        var request: CounselorGenerationRequest? = null
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

            override suspend fun generate(requestValue: CounselorGenerationRequest): Flow<LlmGenerationState> {
                request = requestValue
                return flowOf(LlmGenerationState.Content("Respons konseling."))
            }
        }
        val savedProfile = CounselorPersonaProfile(
            focusNote = "Gunakan bahasa hangat.",
            version = 7
        ).withIntegrityHash()
        val persona = object : CounselorPersonaRepository {
            override fun observe(): Flow<CounselorPersona> = flowOf(CounselorPersona(savedProfile, 1L))
            override suspend fun get(): CounselorPersona = CounselorPersona(savedProfile, 1L)
            override suspend fun save(profile: CounselorPersonaProfile): Result<CounselorPersona> = error("not used")
            override suspend fun reset(): Result<CounselorPersona> = error("not used")
        }
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = object : RagRetriever {
                override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                    RagRetrievalResult.Unavailable("no documents")
            },
            chatDao = fakeDao(mutableListOf()),
            modelManager = readyManager(),
            personaRepository = persona
        )
        advanceUntilIdle()

        viewModel.sendMessage("Saya cemas")
        advanceUntilIdle()

        assertEquals("Gunakan bahasa hangat.", request?.personaProfile?.focusNote)
        assertEquals(7L, request?.personaProfile?.version)
    }

    @Test
    fun `streamed response fragments produce one complete assistant bubble`() = runTest {
        val persisted = mutableListOf<ChatMessageEntity>()
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = flow {
                emit(LlmGenerationState.Content("Halo "))
                emit(LlmGenerationState.Content("selamat "))
                emit(LlmGenerationState.Content("pagi."))
            }
        }
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = object : RagRetriever {
                override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                    RagRetrievalResult.Unavailable("no indexed documents")
            },
            chatDao = fakeDao(persisted),
            modelManager = readyManager()
        )
        advanceUntilIdle()

        viewModel.sendMessage("halo")
        advanceUntilIdle()

        val expected = "Halo selamat pagi.\n\nCatatan: respons ini adalah dukungan umum dan bukan diagnosis medis."
        assertEquals(expected, persisted.single { it.sender == "counselor" }.content)
        assertEquals(expected, viewModel.uiState.value.messages.single { it.sender == "counselor" }.content)
    }

    @Test
    fun `stream fragments stay internal until the final safety guard completes`() = runTest {
        val firstFragmentEmitted = CompletableDeferred<Unit>()
        val releaseFinalFragment = CompletableDeferred<Unit>()
        val persisted = mutableListOf<ChatMessageEntity>()
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = flow {
                emit(LlmGenerationState.Content("Fragmen rahasia "))
                firstFragmentEmitted.complete(Unit)
                releaseFinalFragment.await()
                emit(LlmGenerationState.Content("yang sudah dijaga."))
            }
        }
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = unavailableRag(),
            chatDao = fakeDao(persisted),
            modelManager = readyManager(),
            ioDispatcher = dispatcher
        )
        advanceUntilIdle()

        viewModel.sendMessage("halo")
        advanceUntilIdle()
        firstFragmentEmitted.await()

        val interim = viewModel.uiState.value
        assertTrue(interim.isGenerating)
        assertEquals("", interim.messages.single { it.sender == "counselor" }.content)
        assertTrue(persisted.none { it.sender == "counselor" })

        releaseFinalFragment.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.messages.single { it.sender == "counselor" }.content
                .contains("Fragmen rahasia yang sudah dijaga.")
        )
        assertTrue(persisted.single { it.sender == "counselor" }.content.contains("sudah dijaga"))
    }

    @Test
    fun `runtime preflight failure keeps user bubble and skips retrieval`() = runTest {
        var ragCalls = 0
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")
            override suspend fun preflight() = LlmRuntimeReadiness.Unavailable(
                userMessage = "AI lokal belum dapat menjalankan model pada perangkat ini.",
                technicalMessage = "compiled model invocation failed"
            )

            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> =
                flowOf(LlmGenerationState.Content("unexpected"))
        }
        val rag = object : RagRetriever {
            override suspend fun retrieveRelevantPassages(
                query: String,
                maxResults: Int,
                minRelevanceThreshold: Float
            ): RagRetrievalResult {
                ragCalls++
                return RagRetrievalResult.BelowThreshold(0f)
            }
        }
        val viewModel = CounselorViewModel(llm, rag, fakeDao(mutableListOf()), readyManager())
        advanceUntilIdle()

        viewModel.sendMessage("Tes runtime")
        advanceUntilIdle()

        assertEquals(0, ragCalls)
        assertTrue(viewModel.uiState.value.messages.any { it.sender == "user" && it.content == "Tes runtime" })
        assertEquals(CounselorPhase.ERROR, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.statusMessage!!.contains("belum dapat menjalankan"))
    }

    @Test
    fun `generation failure keeps user message and retry does not duplicate it`() = runTest {
        var shouldFail = true
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = flow {
                if (shouldFail) {
                    emit(LlmGenerationState.Failed("compiled model invocation failed"))
                } else {
                    emit(LlmGenerationState.Content("Respons setelah retry."))
                }
            }
        }
        val persisted = mutableListOf<ChatMessageEntity>()
        val rag = object : RagRetriever {
            override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                RagRetrievalResult.BelowThreshold(0.1f)
        }
        val viewModel = CounselorViewModel(llm, rag, fakeDao(persisted), readyManager())
        advanceUntilIdle()

        viewModel.sendMessage("Saya butuh bantuan")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.messages.any { it.sender == "user" && it.content == "Saya butuh bantuan" })
        assertTrue(viewModel.uiState.value.statusMessage!!.contains("gagal menghasilkan"))
        assertEquals(1, persisted.count { it.sender == "user" })

        shouldFail = false
        viewModel.retryLastMessage()
        advanceUntilIdle()

        assertEquals(1, persisted.count { it.sender == "user" })
        assertTrue(persisted.any { it.sender == "counselor" && it.content.contains("setelah retry") })
    }

    @Test
    fun `below threshold uses general safe mode without citations`() = runTest {
        var request: CounselorGenerationRequest? = null
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

            override suspend fun generate(requestValue: CounselorGenerationRequest): Flow<LlmGenerationState> {
                request = requestValue
                return flowOf(LlmGenerationState.Content("Dukungan umum tanpa diagnosis."))
            }
        }
        val rag = object : RagRetriever {
            override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                RagRetrievalResult.BelowThreshold(0.2f)
        }
        val persisted = mutableListOf<ChatMessageEntity>()
        val manager = readyManager()
        val viewModel = CounselorViewModel(llm, rag, fakeDao(persisted), manager)
        advanceUntilIdle()

        viewModel.sendMessage("Saya merasa tertekan")
        advanceUntilIdle()

        assertEquals(RagMode.GENERAL_SAFE, request?.ragMode)
        assertTrue(request?.retrievedCitations?.isEmpty() == true)
        assertTrue(persisted.any { it.sender == "counselor" && it.citationsJson == "[]" })
    }

    @Test
    fun `web mode requires consent and discovers sources automatically`() = runTest {
        var receivedQuery: String? = null
        var request: CounselorGenerationRequest? = null
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

            override suspend fun generate(requestValue: CounselorGenerationRequest): Flow<LlmGenerationState> {
                request = requestValue
                return flowOf(LlmGenerationState.Content("Jawaban dengan sumber."))
            }
        }
        val gateway = object : WebGroundingGateway {
            override suspend fun retrieveAutomatically(query: String): Result<WebGroundingResult> {
                receivedQuery = query
                return Result.success(WebGroundingResult(query, listOf(webCitation()), 10L))
            }
        }
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = unavailableRag(),
            chatDao = fakeDao(mutableListOf()),
            modelManager = readyManager(),
            ioDispatcher = dispatcher,
            webGroundingGateway = gateway
        )
        advanceUntilIdle()

        viewModel.setWebSearchMode(WebSearchMode.NextMessage)
        viewModel.sendMessage("apa itu coping")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.webConsentRequired)
        assertEquals(null, receivedQuery)
        viewModel.confirmWebConsent()
        advanceUntilIdle()

        assertEquals("apa itu coping", receivedQuery)
        assertEquals(RagMode.WEB_GROUNDED, request?.ragMode)
        assertEquals(listOf("web-test"), request?.webCitations?.map { it.citationId })
        assertTrue(viewModel.uiState.value.messages.any { it.content == "apa itu coping" })
    }

    @Test
    fun `web disabled does not call automatic discovery when local rag is sufficient`() = runTest {
        var webCalls = 0
        var request: CounselorGenerationRequest? = null
        val viewModel = CounselorViewModel(
            llmEngine = object : OnDeviceLlmEngine {
                override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

                override suspend fun generate(requestValue: CounselorGenerationRequest): Flow<LlmGenerationState> {
                    request = requestValue
                    return flowOf(LlmGenerationState.Content("Jawaban lokal."))
                }
            },
            ragRetriever = object : RagRetriever {
                override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                    RagRetrievalResult.Retrieved(listOf(localCitation()))
            },
            chatDao = fakeDao(mutableListOf()),
            modelManager = readyManager(),
            webGroundingGateway = object : WebGroundingGateway {
                override suspend fun retrieveAutomatically(query: String): Result<WebGroundingResult> {
                    webCalls++
                    return Result.failure(AssertionError("web must not be called"))
                }
            }
        )
        advanceUntilIdle()

        viewModel.sendMessage("Apa yang dimaksud coping?")
        advanceUntilIdle()

        assertEquals(0, webCalls)
        assertEquals(RagMode.GROUNDED, request?.ragMode)
    }

    @Test
    fun `evidence required with local sources unavailable abstains before llm`() = runTest {
        var llmCalls = 0
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> {
                llmCalls++
                return flowOf(LlmGenerationState.Content("must not run"))
            }
        }
        val persisted = mutableListOf<ChatMessageEntity>()
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = unavailableRag(),
            chatDao = fakeDao(persisted),
            modelManager = readyManager(),
            ioDispatcher = dispatcher
        )
        advanceUntilIdle()

        viewModel.sendMessage("Apa itu depresi?")
        advanceUntilIdle()

        assertEquals(0, llmCalls)
        assertEquals(CounselorPhase.RETRIEVAL_UNAVAILABLE, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.messages.none { it.sender == "counselor" })
        assertTrue(persisted.none { it.sender == "counselor" })
        assertTrue(viewModel.uiState.value.statusMessage!!.contains("Evidence"))
    }

    @Test
    fun `bundled evidence selects bundled grounded mode and persists tier`() = runTest {
        var request: CounselorGenerationRequest? = null
        val citation = KnowledgeCitation(
            citationId = "bundled-test",
            sourceTitle = "Dataset coping",
            section = "Bagian 1",
            text = "Coping dapat membantu.",
            relevanceScore = 0.9f,
            sourceFileName = "coping.md",
            sourceSha256 = "a".repeat(64),
            chunkId = "bundled-test",
            documentId = "bundled-doc",
            sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
        )
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

            override suspend fun generate(requestValue: CounselorGenerationRequest): Flow<LlmGenerationState> {
                request = requestValue
                return flowOf(LlmGenerationState.Content("Coping dapat membantu. [S1|bundled-test]"))
            }
        }
        val persisted = mutableListOf<ChatMessageEntity>()
        val viewModel = CounselorViewModel(
            llmEngine = llm,
            ragRetriever = object : RagRetriever {
                override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                    RagRetrievalResult.Retrieved(
                        citations = listOf(citation),
                        sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
                    )
            },
            chatDao = fakeDao(persisted),
            modelManager = readyManager(),
            ioDispatcher = dispatcher
        )
        advanceUntilIdle()

        viewModel.sendMessage("Apa itu coping?")
        advanceUntilIdle()

        assertEquals(RagMode.BUNDLED_GROUNDED, request?.ragMode)
        assertTrue(persisted.single { it.sender == "counselor" }.citationsJson!!.contains("BUNDLED_LOCAL"))
        assertEquals(RagMode.BUNDLED_GROUNDED, viewModel.uiState.value.messages.single { it.sender == "counselor" }.retrievalMode)
    }

    @Test
    fun `crisis message never invokes web fallback`() = runTest {
        var webCalls = 0
        val viewModel = CounselorViewModel(
            llmEngine = object : OnDeviceLlmEngine {
                override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")

                override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> =
                    flowOf(LlmGenerationState.Content("unexpected"))
            },
            ragRetriever = unavailableRag(),
            chatDao = fakeDao(mutableListOf()),
            modelManager = readyManager(),
            webGroundingGateway = object : WebGroundingGateway {
                override suspend fun retrieveAutomatically(query: String): Result<WebGroundingResult> {
                    webCalls++
                    return Result.success(WebGroundingResult(query, listOf(webCitation()), 1L))
                }
            }
        )
        advanceUntilIdle()
        viewModel.setWebSearchMode(WebSearchMode.Persistent)
        viewModel.confirmWebConsent()
        advanceUntilIdle()

        viewModel.sendMessage("saya ingin bunuh diri")
        advanceUntilIdle()

        assertEquals(0, webCalls)
        assertEquals(CounselorPhase.CRISIS_ESCALATION, viewModel.uiState.value.phase)
    }

    @Test
    fun `cancellation keeps technical status separate and does not persist it`() = runTest {
        var cancelCalls = 0
        val llm = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")
            override fun cancelGeneration() {
                cancelCalls++
            }

            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = kotlinx.coroutines.flow.flow {
                emit(LlmGenerationState.Content("partial"))
                awaitCancellation()
            }
        }
        val rag = object : RagRetriever {
            override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
                RagRetrievalResult.BelowThreshold(0.1f)
        }
        val persisted = mutableListOf<ChatMessageEntity>()
        val viewModel = CounselorViewModel(llm, rag, fakeDao(persisted), readyManager())
        advanceUntilIdle()

        viewModel.sendMessage("Tolong bantu saya")
        advanceUntilIdle()
        viewModel.cancelGeneration()
        advanceUntilIdle()

        assertEquals(1, cancelCalls)
        assertTrue(persisted.none { it.sender == "counselor" })
        assertTrue(viewModel.uiState.value.messages.none { it.sender == "counselor" })
        assertEquals("Respons dibatalkan.", viewModel.uiState.value.statusMessage)
    }

    @Test
    fun `message persistence failure clears generating state`() = runTest {
        val failingDao = object : ChatDao {
            override suspend fun insertChatSession(session: ChatSessionEntity) = Unit
            override fun getAllChatSessions(): Flow<List<ChatSessionEntity>> = flowOf(emptyList())
            override suspend fun insertMessage(message: ChatMessageEntity): Unit = error("disk full")
            override suspend fun touchChatSession(sessionId: String, timestampMs: Long) = Unit
            override fun getMessagesForSession(chatSessionId: String): Flow<List<ChatMessageEntity>> = flowOf(emptyList())
            override suspend fun deleteMessagesForSession(chatSessionId: String) = Unit
            override suspend fun deleteChatSession(chatSessionId: String) = Unit
            override suspend fun deleteAllMessages() = Unit
            override suspend fun deleteAllChatSessions() = Unit
        }
        val viewModel = CounselorViewModel(
            llmEngine = object : OnDeviceLlmEngine {
                override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")
                override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> =
                    flowOf(LlmGenerationState.Content("unexpected"))
            },
            ragRetriever = unavailableRag(),
            chatDao = failingDao,
            modelManager = readyManager()
        )
        advanceUntilIdle()

        viewModel.sendMessage("Tes penyimpanan")
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.isGenerating)
        assertEquals(CounselorPhase.ERROR, viewModel.uiState.value.phase)
    }

    private fun readyManager(): ModelManager {
        val manager = Mockito.mock(ModelManager::class.java)
        val entry = com.stressdetector.mobile.ml.assets.AiAssetEntry(
            kind = com.stressdetector.mobile.ml.assets.AiAssetKind.LLM,
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

    private fun unavailableRag() = object : RagRetriever {
        override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float) =
            RagRetrievalResult.Unavailable("no indexed documents")
    }

    private fun unavailableLlm() = object : OnDeviceLlmEngine {
        override fun readiness(): LlmReadiness = LlmReadiness.ModelMissing

        override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> =
            flowOf(LlmGenerationState.Unavailable(LlmReadiness.ModelMissing))
    }

    private fun localCitation() = com.stressdetector.mobile.domain.engine.KnowledgeCitation(
        citationId = "local-test",
        sourceTitle = "Local source",
        section = "Section",
        text = "Local grounding",
        relevanceScore = 0.9f
    )

    private fun webCitation() = com.stressdetector.mobile.domain.engine.KnowledgeCitation(
        citationId = "web-test",
        sourceTitle = "Web source",
        section = "Sumber web",
        text = "Web grounding",
        relevanceScore = 0f,
        sourceType = CitationSource.WEB,
        sourceUrl = "https://example.com/source",
        sourceDomain = "example.com",
        retrievedAtMillis = 10L,
        contentHash = "a".repeat(64)
    )

    private fun fakeDao(messages: MutableList<ChatMessageEntity>) = object : ChatDao {
        override suspend fun insertChatSession(session: ChatSessionEntity) = Unit
        override fun getAllChatSessions(): Flow<List<ChatSessionEntity>> = flowOf(emptyList())
        override suspend fun insertMessage(message: ChatMessageEntity) {
            messages += message
        }
        override suspend fun touchChatSession(sessionId: String, timestampMs: Long) = Unit
        override fun getMessagesForSession(chatSessionId: String): Flow<List<ChatMessageEntity>> = flowOf(emptyList())
        override suspend fun deleteMessagesForSession(chatSessionId: String) = Unit
        override suspend fun deleteChatSession(chatSessionId: String) = Unit
        override suspend fun deleteAllMessages() = Unit
        override suspend fun deleteAllChatSessions() = Unit
    }
}

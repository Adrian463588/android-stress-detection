package com.stressdetector.mobile.ui.viewmodels

import com.stressdetector.mobile.data.db.ChatDao
import com.stressdetector.mobile.data.db.ChatMessageEntity
import com.stressdetector.mobile.data.db.ChatSessionEntity
import com.stressdetector.mobile.domain.engine.CounselorGenerationRequest
import com.stressdetector.mobile.domain.engine.LlmGenerationState
import com.stressdetector.mobile.domain.engine.LlmReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever
import com.stressdetector.mobile.domain.voice.VoiceBackend
import com.stressdetector.mobile.domain.voice.VoiceLanguage
import com.stressdetector.mobile.domain.voice.VoiceOutputEngine
import com.stressdetector.mobile.domain.voice.VoiceOutputState
import com.stressdetector.mobile.ml.assets.AiAssetEntry
import com.stressdetector.mobile.ml.assets.AiAssetKind
import com.stressdetector.mobile.ml.llm.ModelManager
import com.stressdetector.mobile.ml.llm.ModelReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class CounselorVoicePlaybackTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `same message toggles playback off and waits for old job`() = runTest {
        val output = BlockingVoiceOutputEngine()
        val viewModel = createViewModel(output)
        advanceUntilIdle()

        viewModel.toggleVoiceReply("assistant-1", "Halo")
        runCurrent()
        assertEquals("assistant-1", viewModel.uiState.value.voicePlayback.activeMessageId)
        assertEquals(1, output.speakCount)

        viewModel.toggleVoiceReply("assistant-1", "Halo")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.voicePlayback.activeMessageId)
        assertEquals(1, output.stopCount)
    }

    @Test
    fun `starting another message stops the previous message before replacement`() = runTest {
        val output = BlockingVoiceOutputEngine()
        val viewModel = createViewModel(output)
        advanceUntilIdle()

        viewModel.toggleVoiceReply("assistant-1", "Pertama")
        runCurrent()
        viewModel.toggleVoiceReply("assistant-2", "Kedua")
        runCurrent()

        assertEquals(2, output.speakCount)
        assertEquals(1, output.stopCount)
        assertEquals("assistant-2", viewModel.uiState.value.voicePlayback.activeMessageId)

        viewModel.stopVoiceReply()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.voicePlayback.activeMessageId)
    }

    @Test
    fun `voice failure after a crisis escalation leaves the safety response intact`() = runTest {
        val viewModel = createViewModel(FailingVoiceOutputEngine())
        advanceUntilIdle()

        viewModel.sendVoiceMessage("ingin bunuh diri")
        advanceUntilIdle()

        assertEquals(CounselorPhase.CRISIS_ESCALATION, viewModel.uiState.value.phase)
        assertEquals("crisis_system", viewModel.uiState.value.messages.last().sender)
        assertEquals("TTS unavailable", viewModel.uiState.value.voiceError)
    }

    private fun createViewModel(output: VoiceOutputEngine) = CounselorViewModel(
        llmEngine = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")
            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> =
                flowOf(LlmGenerationState.Content("unused"))
        },
        ragRetriever = object : RagRetriever {
            override suspend fun retrieveRelevantPassages(
                query: String,
                maxResults: Int,
                minRelevanceThreshold: Float
            ): RagRetrievalResult = RagRetrievalResult.Unavailable("unused")
        },
        chatDao = fakeDao(),
        modelManager = readyManager(),
        voiceOutputEngine = output,
        ioDispatcher = dispatcher
    )

    private class BlockingVoiceOutputEngine : VoiceOutputEngine {
        private val _state = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Idle)
        var speakCount = 0
        var stopCount = 0

        override val state: StateFlow<VoiceOutputState> = _state

        override suspend fun speak(messageId: String, text: String, language: VoiceLanguage): Result<VoiceBackend> {
            speakCount++
            _state.value = VoiceOutputState.Speaking(messageId, VoiceBackend.PIPER_LEGACY)
            try {
                awaitCancellation()
            } finally {
                _state.value = VoiceOutputState.Stopped
            }
            return Result.success(VoiceBackend.PIPER_LEGACY)
        }

        override suspend fun stopAndWait(): Result<Unit> {
            stopCount++
            _state.value = VoiceOutputState.Stopped
            return Result.success(Unit)
        }

        override suspend fun close() = Unit
    }

    private class FailingVoiceOutputEngine : VoiceOutputEngine {
        private val _state = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Idle)

        override val state: StateFlow<VoiceOutputState> = _state

        override suspend fun speak(messageId: String, text: String, language: VoiceLanguage): Result<VoiceBackend> =
            Result.failure(IllegalStateException("TTS unavailable"))

        override suspend fun stopAndWait(): Result<Unit> = Result.success(Unit)

        override suspend fun close() = Unit
    }

    private fun readyManager(): ModelManager {
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

    private fun fakeDao() = object : ChatDao {
        override suspend fun insertChatSession(session: ChatSessionEntity) = Unit
        override fun getAllChatSessions() = flowOf(emptyList<ChatSessionEntity>())
        override suspend fun insertMessage(message: ChatMessageEntity) = Unit
        override suspend fun touchChatSession(chatSessionId: String, timestampMs: Long) = Unit
        override fun getMessagesForSession(chatSessionId: String) = flowOf(emptyList<ChatMessageEntity>())
        override suspend fun deleteMessagesForSession(chatSessionId: String) = Unit
        override suspend fun deleteChatSession(chatSessionId: String) = Unit
        override suspend fun deleteAllMessages() = Unit
        override suspend fun deleteAllChatSessions() = Unit
    }
}

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
import com.stressdetector.mobile.ml.llm.ModelManager
import com.stressdetector.mobile.ml.llm.ModelReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class CounselorChatSessionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun newChatCreatesSeparateSessionAndSelectingOldSessionRestoresIt() = runTest {
        val dao = InMemoryChatDao()
        val viewModel = CounselorViewModel(
            llmEngine = TestLlmEngine,
            ragRetriever = NoOpRetriever,
            chatDao = dao,
            modelManager = readyManager(),
            ioDispatcher = dispatcher
        )
        advanceUntilIdle()
        val firstSessionId = viewModel.uiState.value.activeChatSessionId

        viewModel.newChat()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.chatSessions.size)
        assertNotEquals(firstSessionId, viewModel.uiState.value.activeChatSessionId)

        viewModel.selectChatSession(firstSessionId)
        advanceUntilIdle()

        assertEquals(firstSessionId, viewModel.uiState.value.activeChatSessionId)
    }

    @Test
    fun deletingAllChatsCreatesARecoverableFreshSession() = runTest {
        val dao = InMemoryChatDao()
        val viewModel = CounselorViewModel(
            llmEngine = TestLlmEngine,
            ragRetriever = NoOpRetriever,
            chatDao = dao,
            modelManager = readyManager(),
            ioDispatcher = dispatcher
        )
        advanceUntilIdle()

        viewModel.deleteAllChats()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.chatSessions.size)
        assertEquals("Chat baru", viewModel.uiState.value.chatSessions.single().title)
        assertEquals(
            viewModel.uiState.value.chatSessions.single().id,
            viewModel.uiState.value.activeChatSessionId
        )
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

    private object TestLlmEngine : OnDeviceLlmEngine {
        override fun readiness(): LlmReadiness = LlmReadiness.Ready("test")
        override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = flowOf(
            LlmGenerationState.Content("ok")
        )
    }

    private object NoOpRetriever : RagRetriever {
        override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float): RagRetrievalResult =
            RagRetrievalResult.Unavailable("test")
    }

    private class InMemoryChatDao : ChatDao {
        private val sessions = MutableStateFlow<List<ChatSessionEntity>>(emptyList())
        private val messages = mutableMapOf<String, MutableStateFlow<List<ChatMessageEntity>>>()

        override suspend fun insertChatSession(session: ChatSessionEntity) {
            sessions.value = (sessions.value.filterNot { it.chatSessionId == session.chatSessionId } + session)
                .sortedByDescending { it.lastMessageAtMillis }
        }

        override fun getAllChatSessions(): Flow<List<ChatSessionEntity>> = sessions

        override suspend fun insertMessage(message: ChatMessageEntity) {
            val state = messages.getOrPut(message.chatSessionId) { MutableStateFlow(emptyList()) }
            state.value = state.value + message
        }

        override suspend fun touchChatSession(sessionId: String, timestampMs: Long) {
            sessions.value = sessions.value.map {
                if (it.chatSessionId == sessionId) it.copy(lastMessageAtMillis = timestampMs) else it
            }.sortedByDescending { it.lastMessageAtMillis }
        }

        override fun getMessagesForSession(chatSessionId: String): Flow<List<ChatMessageEntity>> =
            messages.getOrPut(chatSessionId) { MutableStateFlow(emptyList()) }

        override suspend fun deleteMessagesForSession(chatSessionId: String) {
            messages.remove(chatSessionId)
        }

        override suspend fun deleteChatSession(chatSessionId: String) {
            sessions.value = sessions.value.filterNot { it.chatSessionId == chatSessionId }
        }

        override suspend fun deleteAllMessages() {
            messages.clear()
        }

        override suspend fun deleteAllChatSessions() {
            sessions.value = emptyList()
        }
    }
}

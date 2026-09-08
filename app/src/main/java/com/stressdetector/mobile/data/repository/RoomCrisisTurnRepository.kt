package com.stressdetector.mobile.data.repository

import com.stressdetector.mobile.data.db.ChatDao
import com.stressdetector.mobile.data.db.ChatMessageEntity
import com.stressdetector.mobile.data.db.ChatSessionEntity
import com.stressdetector.mobile.domain.counselor.CrisisTurn
import com.stressdetector.mobile.domain.counselor.CrisisTurnRepository
import kotlinx.coroutines.CancellationException

class RoomCrisisTurnRepository(private val chatDao: ChatDao) : CrisisTurnRepository {
    override suspend fun persist(turn: CrisisTurn): Result<Unit> = try {
        chatDao.persistCrisisTurn(
            session =
            ChatSessionEntity(
                chatSessionId = turn.sessionId,
                title = turn.sessionTitle,
                startedAtMillis = turn.sessionStartedAtMillis,
                lastMessageAtMillis = turn.crisisTimestampMillis
            ),
            userMessage =
            ChatMessageEntity(
                messageId = turn.userMessageId,
                chatSessionId = turn.sessionId,
                sender = USER_SENDER,
                content = turn.userText,
                timestampMs = turn.userTimestampMillis,
                citationsJson = EMPTY_CITATIONS_JSON,
                inputMode = turn.inputMode
            ),
            crisisMessage =
            ChatMessageEntity(
                messageId = turn.crisisMessageId,
                chatSessionId = turn.sessionId,
                sender = CRISIS_SYSTEM_SENDER,
                content = turn.crisisText,
                timestampMs = turn.crisisTimestampMillis,
                citationsJson = EMPTY_CITATIONS_JSON,
                promptVersion = turn.templateVersion,
                inputMode = turn.inputMode
            )
        )
        Result.success(Unit)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private companion object {
        const val USER_SENDER = "user"
        const val CRISIS_SYSTEM_SENDER = "crisis_system"
        const val EMPTY_CITATIONS_JSON = "[]"
    }
}

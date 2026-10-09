package com.stressdetector.mobile

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stressdetector.mobile.data.db.ChatMessageEntity
import com.stressdetector.mobile.data.db.ChatSessionEntity
import com.stressdetector.mobile.data.db.StressDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantTurnRoomInstrumentedTest {
    @Test
    fun cancelledAssistantWriteRollsBackWithoutDeletingUserTurn() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, StressDatabase::class.java).build()
        try {
            val dao = database.chatDao()
            dao.insertChatSession(ChatSessionEntity("transaction-test", "Test", 1L, 1L))
            dao.insertMessage(ChatMessageEntity("user", "transaction-test", "user", "Pesan uji transaksi", 1L, null))
            val written = CompletableDeferred<Unit>()
            var checks = 0
            val writer = launch(Dispatchers.IO) {
                dao.persistAssistantTurn(
                    ChatMessageEntity("assistant", "transaction-test", "counselor", "Respons uji transaksi", 2L, null)
                ) {
                    checks++
                    if (checks == 2) {
                        written.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            written.await()
            writer.cancelAndJoin()
            assertEquals(listOf("user"), dao.getMessagesForSession("transaction-test").first().map { it.messageId })
            assertEquals(1L, dao.getAllChatSessions().first().single().lastMessageAtMillis)
        } finally {
            database.close()
        }
    }
}

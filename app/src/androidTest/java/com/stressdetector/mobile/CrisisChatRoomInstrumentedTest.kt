package com.stressdetector.mobile

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.data.repository.RoomCrisisTurnRepository
import com.stressdetector.mobile.domain.counselor.CrisisTurn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrisisChatRoomInstrumentedTest {
    @Test
    fun crisis_turn_is_written_and_read_with_the_production_room_schema() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database =
            Room
                .inMemoryDatabaseBuilder(context, StressDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val result =
                RoomCrisisTurnRepository(database.chatDao())
                    .persist(
                        CrisisTurn(
                            sessionId = "crisis-session",
                            sessionTitle = "Butuh bantuan",
                            sessionStartedAtMillis = 1L,
                            userMessageId = "user-message",
                            userText = "ingin bunuh diri",
                            userTimestampMillis = 1L,
                            inputMode = "TEXT",
                            crisisMessageId = "crisis-message",
                            crisisText = "Keselamatanmu penting.",
                            crisisTimestampMillis = 2L,
                            templateVersion = "crisis-id-v1"
                        )
                    )

            assertTrue(result.isSuccess)
            val messages = database.chatDao().getMessagesForSession("crisis-session").first()
            assertEquals(listOf("user", "crisis_system"), messages.map { it.sender })
            assertEquals("crisis-id-v1", messages.last().promptVersion)
        } finally {
            database.close()
        }
    }
}

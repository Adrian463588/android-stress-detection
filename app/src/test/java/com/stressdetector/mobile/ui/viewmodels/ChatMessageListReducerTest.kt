package com.stressdetector.mobile.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageListReducerTest {
    @Test
    fun persistedMessagesAreAuthoritativeAndEveryIdIsUnique() {
        val current = listOf(message("same", "optimistic", 3), message("same", "streaming", 2))
        val persisted = listOf(message("same", "persisted-old", 1), message("same", "persisted", 4))

        val result = ChatMessageListReducer.mergePersisted(persisted, current)

        assertEquals(1, result.size)
        assertEquals("persisted", result.single().content)
    }

    @Test
    fun latestReplacementWinsAndOrderingIsDeterministic() {
        val result = ChatMessageListReducer.replace(
            listOf(message("b", "before", 20), message("a", "stable", 10)),
            message("b", "replacement-old", 10),
            message("b", "replacement-new", 10)
        )

        assertEquals(listOf("a", "b"), result.map(UiChatMessage::id))
        assertEquals("replacement-new", result.last().content)
    }

    @Test
    fun emptyRoomEmissionPreservesOneOptimisticMessage() {
        val optimistic = message("optimistic", "hello", 1)

        val result = ChatMessageListReducer.mergePersisted(emptyList(), listOf(optimistic, optimistic))

        assertEquals(listOf(optimistic), result)
    }

    private fun message(id: String, content: String, timestamp: Long) = UiChatMessage(
        id = id,
        sender = "user",
        content = content,
        timestampMs = timestamp
    )
}

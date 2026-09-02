package com.stressdetector.mobile.ui.viewmodels

/** Maintains the single stable-id invariant required by Compose lazy lists. */
internal object ChatMessageListReducer {
    private val deterministicOrder = compareBy(UiChatMessage::timestampMs).thenBy(UiChatMessage::id)

    fun mergePersisted(persisted: List<UiChatMessage>, current: List<UiChatMessage>): List<UiChatMessage> = reduce(current, persisted)

    fun append(current: List<UiChatMessage>, message: UiChatMessage): List<UiChatMessage> = reduce(current, listOf(message))

    fun replace(current: List<UiChatMessage>, vararg replacements: UiChatMessage): List<UiChatMessage> =
        reduce(current, replacements.asList())

    fun remove(current: List<UiChatMessage>, messageId: String): List<UiChatMessage> = current.filterNot { it.id == messageId }

    private fun reduce(vararg sources: List<UiChatMessage>): List<UiChatMessage> {
        val latestById = linkedMapOf<String, UiChatMessage>()
        sources.forEach { source ->
            source.forEach { message -> latestById[message.id] = message }
        }
        return latestById.values.sortedWith(deterministicOrder)
    }
}

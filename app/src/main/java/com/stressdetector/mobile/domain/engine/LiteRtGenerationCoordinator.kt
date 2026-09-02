package com.stressdetector.mobile.domain.engine

import com.google.ai.edge.litertlm.Conversation

/**
 * Serialises the native LiteRT conversation lifecycle. Cancellation may request
 * a cooperative stop once, but native resources are closed only after the
 * collecting coroutine has relinquished the conversation.
 */
internal class LiteRtGenerationCoordinator {
    private val lock = Any()
    private var activeConversation: Conversation? = null
    private var cancellationRequested = false
    private var resetRequested = false

    fun begin(conversation: Conversation) = synchronized(lock) {
        check(activeConversation == null) { "LiteRT generation is already active" }
        activeConversation = conversation
        cancellationRequested = false
    }

    fun requestCancellation(): Conversation? = synchronized(lock) {
        val conversation = activeConversation ?: return@synchronized null
        if (cancellationRequested) return@synchronized null
        cancellationRequested = true
        conversation
    }

    fun requestReset(): Conversation? = synchronized(lock) {
        if (activeConversation == null) return@synchronized null
        resetRequested = true
        requestCancellationLocked()
    }

    /** Returns true when the caller must reset the engine after closing [conversation]. */
    fun finish(conversation: Conversation?): Boolean = synchronized(lock) {
        if (conversation != null && activeConversation !== conversation) return@synchronized false
        activeConversation = null
        cancellationRequested = false
        val reset = resetRequested
        resetRequested = false
        reset
    }

    fun hasActiveGeneration(): Boolean = synchronized(lock) { activeConversation != null }

    private fun requestCancellationLocked(): Conversation? {
        val conversation = activeConversation ?: return null
        if (cancellationRequested) return null
        cancellationRequested = true
        return conversation
    }
}

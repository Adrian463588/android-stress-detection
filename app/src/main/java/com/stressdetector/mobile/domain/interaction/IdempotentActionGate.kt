package com.stressdetector.mobile.domain.interaction

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prevents duplicate execution of the same logical action while it is in flight.
 * The gate deliberately does not own a coroutine scope: callers retain lifecycle
 * ownership and every operation releases its key from a finally block.
 */
class IdempotentActionGate<K> {
    private val mutex = Mutex()
    private val activeKeys = mutableSetOf<K>()

    suspend fun <T> run(key: K, action: suspend () -> T): T? {
        val acquired = mutex.withLock { activeKeys.add(key) }
        if (!acquired) return null

        return try {
            action()
        } finally {
            mutex.withLock { activeKeys.remove(key) }
        }
    }

    suspend fun isActive(key: K): Boolean = mutex.withLock { key in activeKeys }
}

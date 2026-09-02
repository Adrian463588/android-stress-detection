package com.stressdetector.mobile.ml.rag

import java.util.PriorityQueue

/** Keeps only the highest scoring candidates while a large corpus is scanned. */
class BoundedScorePool<T>(private val capacity: Int, private val score: (T) -> Float) {
    private val values = PriorityQueue<T>(compareBy(score))

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun offer(value: T) {
        if (values.size < capacity) {
            values.add(value)
            return
        }
        val lowest = values.peek() ?: return
        if (score(value) > score(lowest)) {
            values.poll()
            values.add(value)
        }
    }

    fun descending(): List<T> = values.toList().sortedByDescending(score)
    fun isEmpty(): Boolean = values.isEmpty()
}

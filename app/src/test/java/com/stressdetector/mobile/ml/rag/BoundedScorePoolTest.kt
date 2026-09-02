package com.stressdetector.mobile.ml.rag

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedScorePoolTest {
    @Test
    fun `pool retains only highest scores`() {
        val pool = BoundedScorePool<Int>(capacity = 3, score = Int::toFloat)
        (1..100).forEach(pool::offer)
        assertEquals(listOf(100, 99, 98), pool.descending())
    }
}

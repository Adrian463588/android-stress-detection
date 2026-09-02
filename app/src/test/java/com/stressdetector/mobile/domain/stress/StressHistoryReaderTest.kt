package com.stressdetector.mobile.domain.stress

import com.stressdetector.mobile.data.db.StressSessionDao
import com.stressdetector.mobile.data.db.StressSessionEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class StressHistoryReaderTest {
    @Test
    fun `room projection maps newest history fields`() = runTest {
        val dao = Mockito.mock(StressSessionDao::class.java)
        val entity = StressSessionEntity(
            sessionId = "stress-1",
            startedAtMillis = 100L,
            endedAtMillis = 200L,
            durationMillis = 100L,
            primarySensor = "ppg_green",
            finalLabel = "stress",
            confidence = 0.81f,
            qualityTier = "good",
            stressProbability = 0.73f,
            greenSampleCount = 100,
            irSampleCount = 100,
            errorReason = null
        )
        Mockito.`when`(dao.latest(7)).thenReturn(flowOf(listOf(entity)))

        val items = RoomStressHistoryReader(dao).latest(7)

        assertEquals(1, items.size)
        assertEquals("stress-1", items.single().sessionId)
        assertEquals("Stres", StressHistoryFormatter.formatLabel(items.single().finalLabel))
        assertEquals("73%", StressHistoryFormatter.formatProbability(items.single().stressProbability))
    }

    @Test
    fun `prompt formatter is bounded to seven sessions and carries disclaimer`() {
        val items = (0 until 9).map { index ->
            StressHistoryItem("$index", index.toLong(), "normal", 0.5f, "good", 0.2f)
        }

        val prompt = StressHistoryFormatter.toPromptContext(items)

        assertTrue(prompt.contains("not a diagnosis"))
        assertEquals(7, prompt.lines().count { it.startsWith("- timestamp=") })
    }
}

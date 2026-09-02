package com.stressdetector.mobile.domain.stress

import com.stressdetector.mobile.data.db.StressSessionDao
import com.stressdetector.mobile.data.db.StressSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class StressHistoryContextProviderTest {
    @Test
    fun `production provider rejects non probability values and excludes them from prompt`() = runTest {
        val dao = Mockito.mock(StressSessionDao::class.java)
        Mockito.`when`(dao.latest(7)).thenReturn(flowOf(listOf(session("bad", probability = 1.2f))))
        val provider = RoomStressHistoryContextProvider(
            dao = dao,
            settings = InMemoryStressHistoryContextSettings(initialEnabled = true)
        )

        val snapshot = provider.snapshot()

        assertFalse(snapshot.entries.single().valid)
        assertEquals(emptyList<String>(), snapshot.sourceSessionIds)
        assertTrue(snapshot.toPromptContext().startsWith("No valid"))
    }

    @Test
    fun `provider emits unavailable instead of masking dao failure`() = runTest {
        val dao = Mockito.mock(StressSessionDao::class.java)
        val failure: Flow<List<StressSessionEntity>> = flow {
            throw IllegalStateException("database unavailable")
        }
        Mockito.`when`(dao.latest(7)).thenReturn(failure)
        val provider = RoomStressHistoryContextProvider(
            dao = dao,
            settings = InMemoryStressHistoryContextSettings(initialEnabled = true)
        )

        val state = provider.observe().first()

        assertTrue(state is StressHistoryContextState.Unavailable)
        assertTrue((state as StressHistoryContextState.Unavailable).reason.contains("database unavailable"))
    }

    @Test
    fun `valid history is newest first and complete lines fit prompt budget`() = runTest {
        val dao = Mockito.mock(StressSessionDao::class.java)
        Mockito.`when`(dao.latest(7)).thenReturn(
            flowOf((0 until 7).map { index -> session("session-$index", endedAt = 1_000L + index) })
        )
        val provider = RoomStressHistoryContextProvider(
            dao = dao,
            settings = InMemoryStressHistoryContextSettings(initialEnabled = true)
        )

        val snapshot = provider.snapshot()
        val prompt = snapshot.toPromptContext(maxChars = 300)

        assertEquals(7, snapshot.sourceSessionIds.size)
        assertTrue(prompt.lines().none { it.contains("session=") && !it.contains("; quality=") })
        assertTrue(prompt.length <= 300)
    }

    @Test
    fun `transport warning does not hide an otherwise valid prediction from AI context`() = runTest {
        val dao = Mockito.mock(StressSessionDao::class.java)
        Mockito.`when`(dao.latest(7)).thenReturn(
            flowOf(
                listOf(
                    session("transport-warning").copy(
                        transportIncomplete = true,
                        transportFailureReason = "direct_send_gap"
                    )
                )
            )
        )
        val provider = RoomStressHistoryContextProvider(
            dao = dao,
            settings = InMemoryStressHistoryContextSettings(initialEnabled = true)
        )

        val snapshot = provider.snapshot()

        assertTrue(snapshot.entries.single().valid)
        assertEquals(listOf("transport-warning"), snapshot.sourceSessionIds)
    }

    private fun session(id: String, probability: Float = 0.7f, confidence: Float = 0.8f, endedAt: Long = 2_000L) = StressSessionEntity(
        sessionId = id,
        startedAtMillis = endedAt - 1_000L,
        endedAtMillis = endedAt,
        durationMillis = 1_000L,
        primarySensor = "ppg_green",
        finalLabel = "stress",
        confidence = confidence,
        qualityTier = "good",
        stressProbability = probability,
        greenSampleCount = 100,
        irSampleCount = 100,
        errorReason = null
    )
}

package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.db.StressSessionDao
import com.stressdetector.mobile.data.db.StressSessionEntity
import com.stressdetector.mobile.data.db.StressSessionReportEntity
import com.stressdetector.mobile.data.db.StressWindowEvaluationEntity
import com.stressdetector.mobile.domain.engine.BpmSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSessionFinalizerBpmTest {
    @Test
    fun observedPerChannelBpmIsPersistedWithoutFabricatingMissingIr() = runTest {
        val dao = RecordingStressSessionDao()
        val green = BpmSummary(
            meanBpm = 72.5f,
            medianBpm = 72f,
            minBpm = 68f,
            maxBpm = 78f,
            observationCount = 6,
            validPeakCount = 1_250
        )

        HomeSessionFinalizer(dao).finalize(
            snapshot = HomeSessionSnapshot(
                sessionId = "session-bpm",
                startedAtMillis = 1L,
                endedAtMillis = 301_000L,
                durationMillis = 300_000L,
                greenSampleCount = 18_000,
                irSampleCount = 18_000,
                greenState = PredictionAvailability.Unavailable("test"),
                irState = PredictionAvailability.Unavailable("test")
            ),
            aggregatedGreen = null,
            aggregatedIr = null,
            report = FinalizedSessionReport(
                expectedWindowCount = 7,
                windows = emptyList(),
                selectedChannel = null,
                rejectionReason = "test",
                greenBpmSummary = green,
                irBpmSummary = null
            )
        )

        val stored = requireNotNull(dao.report)
        assertEquals(72.5f, stored.greenMeanBpm)
        assertEquals(72f, stored.greenMedianBpm)
        assertEquals(6, stored.greenBpmObservationCount)
        assertEquals(1_250, stored.greenValidPeakCount)
        assertNull(stored.irMeanBpm)
        assertNull(stored.irMedianBpm)
        assertEquals(0, stored.irBpmObservationCount)
        assertEquals(0, stored.irValidPeakCount)
        assertNull(dao.session?.stressProbability)
    }

    private class RecordingStressSessionDao : StressSessionDao {
        var report: StressSessionReportEntity? = null
        var session: StressSessionEntity? = null

        override suspend fun insert(entity: StressSessionEntity) {
            session = entity
        }

        override fun latest(limit: Int): Flow<List<StressSessionEntity>> = flowOf(emptyList())

        override fun between(startMillis: Long, endMillis: Long): Flow<List<StressSessionEntity>> = flowOf(emptyList())

        override suspend fun getById(sessionId: String): StressSessionEntity? = null

        override fun observeById(sessionId: String): Flow<StressSessionEntity?> = flowOf(null)

        override suspend fun insertReport(report: StressSessionReportEntity) {
            this.report = report
        }

        override suspend fun insertWindowEvaluations(evaluations: List<StressWindowEvaluationEntity>) = Unit

        override suspend fun deleteWindowEvaluations(sessionId: String) = Unit
    }
}

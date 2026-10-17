package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.db.StressSessionDao
import com.stressdetector.mobile.data.db.StressSessionEntity
import com.stressdetector.mobile.data.db.StressSessionReportEntity
import com.stressdetector.mobile.data.db.StressWindowEvaluationEntity
import com.stressdetector.mobile.domain.engine.BpmSummary
import com.stressdetector.shared.PpgType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun sevenDistinctWindowFingerprintsPersistSessionLineageAndOriginalEvidence() = runTest {
        val windows = (0 until 7).map { window(it, "ppg_green:window-$it") }

        val dao = persistLineage(windows)

        val fingerprint = requireNotNull(dao.report?.preprocessingFingerprint)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            windows.map { it.snapshot.processingFingerprint },
            dao.evaluations.map { it.preprocessingFingerprint }
        )
    }

    @Test
    fun sessionLineageIsDeterministicRegardlessOfWindowArrivalOrder() = runTest {
        val windows = (0 until 7).map { window(it, "ppg_green:window-$it") }

        val original = persistLineage(windows).report?.preprocessingFingerprint
        val reversed = persistLineage(windows.reversed()).report?.preprocessingFingerprint
        val repeated = persistLineage(windows).report?.preprocessingFingerprint

        assertNotNull(original)
        assertEquals(original, reversed)
        assertEquals(original, repeated)
    }

    @Test
    fun sessionLineageBindsFingerprintsToTheirWindowIndices() = runTest {
        val original = listOf(window(0, "first"), window(1, "second"))
        val swapped = listOf(window(0, "second"), window(1, "first"))

        assertNotEquals(
            persistLineage(original).report?.preprocessingFingerprint,
            persistLineage(swapped).report?.preprocessingFingerprint
        )
    }

    @Test
    fun singleWindowRetainsItsOriginalFingerprint() = runTest {
        val dao = persistLineage(listOf(window(0, "existing-single-window-fingerprint")))

        assertEquals("existing-single-window-fingerprint", dao.report?.preprocessingFingerprint)
    }

    @Test
    fun absentSelectedChannelEvidenceRetainsNullLineage() = runTest {
        assertNull(persistLineage(emptyList()).report?.preprocessingFingerprint)
        assertNull(persistLineage(listOf(window(0, "observed")), selectedChannel = null).report?.preprocessingFingerprint)
        assertNull(persistLineage(listOf(window(0, "observed")), selectedChannel = PpgType.IR).report?.preprocessingFingerprint)
    }

    @Test
    fun blankFingerprintDoesNotProducePartialSessionLineage() = runTest {
        listOf("", "  ", "\n").forEach { blank ->
            val dao = persistLineage(listOf(window(0, "observed"), window(1, blank)))

            assertNull(dao.report?.preprocessingFingerprint)
        }
    }

    @Test
    fun duplicateOrNegativeWindowIndicesRetainNullLineage() = runTest {
        val duplicate = listOf(window(0, "first"), window(0, "second"))
        val negative = listOf(window(-1, "observed"))

        assertNull(persistLineage(duplicate).report?.preprocessingFingerprint)
        assertNull(persistLineage(negative).report?.preprocessingFingerprint)
    }

    @Test
    fun mismatchedSnapshotChannelRetainsNullLineage() = runTest {
        val reported = window(0, "observed")
        val mismatched = reported.copy(snapshot = reported.snapshot.copy(channel = PpgType.IR))

        assertNull(persistLineage(listOf(mismatched)).report?.preprocessingFingerprint)
    }

    @Test
    fun outOfRangeWindowIndicesRetainNullLineage() = runTest {
        assertNull(persistLineage(listOf(window(7, "observed"))).report?.preprocessingFingerprint)
        assertNull(persistLineage(listOf(window(Int.MAX_VALUE, "observed"))).report?.preprocessingFingerprint)
        assertNull(
            persistLineage(listOf(window(0, "valid"), window(7, "invalid")))
                .report?.preprocessingFingerprint
        )
    }

    @Test
    fun nonPositiveExpectedWindowCountRetainsNullLineage() = runTest {
        listOf(0, -1).forEach { count ->
            assertNull(
                persistLineage(listOf(window(0, "observed")), expectedWindowCount = count)
                    .report?.preprocessingFingerprint
            )
        }
    }

    private fun window(index: Int, fingerprint: String): FinalizedWindowReport = FinalizedWindowReport(
        channel = PpgType.GREEN,
        windowIndex = index,
        snapshot = PpgProcessingSnapshot(
            channel = PpgType.GREEN,
            rawSampleCount = 0,
            durationMillis = 0L,
            preparedWindow = null,
            quality = null,
            availability = PredictionAvailability.Unavailable("lineage-only test"),
            chart = PpgChartData.empty(),
            rejectionReason = "lineage-only test",
            processingFingerprint = fingerprint
        )
    )

    private suspend fun persistLineage(
        windows: List<FinalizedWindowReport>,
        selectedChannel: PpgType? = PpgType.GREEN,
        expectedWindowCount: Int = 7
    ): RecordingStressSessionDao {
        val dao = RecordingStressSessionDao()
        val result = HomeSessionFinalizer(dao).finalize(
            snapshot = HomeSessionSnapshot(
                sessionId = "session-lineage",
                startedAtMillis = 1L,
                endedAtMillis = 301_000L,
                durationMillis = 300_000L,
                greenSampleCount = 0,
                irSampleCount = 0,
                greenState = PredictionAvailability.Unavailable("lineage-only test"),
                irState = PredictionAvailability.Unavailable("lineage-only test")
            ),
            aggregatedGreen = null,
            aggregatedIr = null,
            report = FinalizedSessionReport(
                expectedWindowCount = expectedWindowCount,
                windows = windows,
                selectedChannel = selectedChannel,
                rejectionReason = "lineage-only test"
            )
        )
        assertTrue(result is HomeFinalizationResult.Stored)
        return dao
    }

    private class RecordingStressSessionDao : StressSessionDao {
        var report: StressSessionReportEntity? = null
        var session: StressSessionEntity? = null
        var evaluations: List<StressWindowEvaluationEntity> = emptyList()

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

        override suspend fun insertWindowEvaluations(evaluations: List<StressWindowEvaluationEntity>) {
            this.evaluations = evaluations
        }

        override suspend fun deleteWindowEvaluations(sessionId: String) = Unit
    }
}

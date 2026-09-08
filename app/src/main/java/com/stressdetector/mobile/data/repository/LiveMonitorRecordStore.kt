package com.stressdetector.mobile.data.repository

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.data.db.LiveMonitorDao
import com.stressdetector.mobile.data.db.RealtimeInferenceRecordEntity
import com.stressdetector.mobile.domain.engine.LiveMonitorStopSummary

interface LiveMonitorRecordStore {
    suspend fun recordInference(record: RealtimeInferenceRecordEntity)
    suspend fun recordDivergence(event: DivergenceEventEntity)
    suspend fun completeSession(summary: LiveMonitorStopSummary)
}

class RoomLiveMonitorRecordStore(private val dao: LiveMonitorDao) : LiveMonitorRecordStore {
    override suspend fun recordInference(record: RealtimeInferenceRecordEntity) {
        dao.insertRecord(record)
    }

    override suspend fun recordDivergence(event: DivergenceEventEntity) {
        dao.insertDivergenceEvent(event)
    }

    override suspend fun completeSession(summary: LiveMonitorStopSummary) {
        val existing = dao.getLiveSessionById(summary.sessionId) ?: return
        dao.updateLiveSession(
            existing.copy(
                endedAtMillis = summary.endedAtMillis,
                durationSeconds = ((summary.endedAtMillis - summary.startedAtMillis).coerceAtLeast(0L) / 1000L),
                averageStressProbability = summary.averageStressProbability ?: 0f
            )
        )
    }
}

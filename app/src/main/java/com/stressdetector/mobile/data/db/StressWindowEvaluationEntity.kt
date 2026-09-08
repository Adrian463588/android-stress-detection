package com.stressdetector.mobile.data.db

import androidx.room.Entity
import androidx.room.Index

/** Persisted evidence for one channel/window evaluation within a Home session. */
@Entity(
    tableName = "stress_window_evaluations",
    primaryKeys = ["sessionId", "channel", "windowIndex"],
    indices = [Index(value = ["sessionId"])]
)
data class StressWindowEvaluationEntity(
    val sessionId: String,
    val channel: String,
    val windowIndex: Int,
    val windowId: String,
    val startElapsedMillis: Long?,
    val endElapsedMillis: Long?,
    val status: String,
    val label: String?,
    val stressProbability: Float?,
    val confidence: Float?,
    val qualityTier: String?,
    val qualityScore: Float?,
    val sampleCount: Int,
    val interpolatedSampleCount: Int,
    val rejectionReason: String?,
    val modelVersion: String?,
    val preprocessingFingerprint: String?
)

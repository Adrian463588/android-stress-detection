package com.stressdetector.mobile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stress_sessions")
data class StressSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationMillis: Long,
    val primarySensor: String,
    val finalLabel: String,
    val confidence: Float,
    val qualityTier: String,
    val stressProbability: Float,
    val greenSampleCount: Int,
    val irSampleCount: Int,
    val errorReason: String?,
    /** Terminal transport status; independent from persisted core prediction. */
    val transportIncomplete: Boolean = false,
    val transportFailureReason: String? = null
)

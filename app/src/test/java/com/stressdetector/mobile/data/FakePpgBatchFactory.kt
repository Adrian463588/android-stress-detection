package com.stressdetector.mobile.data

import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionConfig
import kotlin.math.sin

/** Deterministic numeric fixture. Never used by production sources or readiness evidence. */
object FakePpgBatchFactory {
    fun inferenceWindowBatch(type: PpgType): PpgBatch = sessionBatch(type, "fixture-session", SessionConfig.INFERENCE_WINDOW_SECONDS)

    fun sessionBatch(type: PpgType, sessionId: String, seconds: Int): PpgBatch {
        val count = type.sourceRateHz * seconds
        val intervalMs = 1000L / type.sourceRateHz
        val timestamps = LongArray(count) { index -> index * intervalMs }
        val values = IntArray(count) { index ->
            (100_000.0 + 10_000.0 * sin(2.0 * Math.PI * 1.2 * index / type.sourceRateHz)).toInt()
        }
        return PpgBatch(type, timestamps, values, sessionId, seconds * 1000L)
    }
}

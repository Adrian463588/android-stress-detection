package com.stressdetector.mobile.domain

import com.stressdetector.inference.InferenceResult
import com.stressdetector.inference.InferenceStatus

/** One mapping from inference failures to user-visible availability states. */
object PredictionAvailabilityMapper {
    fun from(result: InferenceResult, sampleCount: Int): PredictionAvailability {
        val reason = result.rejectionReason ?: result.status.name.lowercase()
        return when (result.status) {
            InferenceStatus.MODEL_UNAVAILABLE,
            InferenceStatus.MODEL_HASH_MISMATCH,
            InferenceStatus.MODEL_ARTIFACT_INVALID,
            InferenceStatus.RUNTIME_UNAVAILABLE,
            InferenceStatus.PREFLIGHT_FAILED -> PredictionAvailability.Unavailable(reason)
            InferenceStatus.INVALID_QUALITY,
            InferenceStatus.INVALID_TIMESTAMP -> PredictionAvailability.Rejected(reason, sampleCount)
            InferenceStatus.SCHEMA_MISMATCH -> PredictionAvailability.InferenceError(reason)
            InferenceStatus.VALID -> PredictionAvailability.InferenceError("missing_probability")
        }
    }
}

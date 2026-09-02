package com.stressdetector.shared

object SessionConfig {
    val PREDICTION_WINDOW: PredictionWindowConfig = PredictionWindowConfig()
    const val SESSION_DURATION_SECONDS: Int = 300
    const val SESSION_DURATION_MILLIS: Long = SESSION_DURATION_SECONDS * 1_000L
    const val INFERENCE_WINDOW_SECONDS: Int = 180
}

package com.stressdetector.mobile.signal

fun testLiveMonitorConfig() = LiveMonitorConfig(
    adrReference = "ADR-012",
    inferenceStepSeconds = 10,
    minimumQualityScore = 0.5f,
    maxTimestampGapDeltaMs = 250.0,
    maxTimestampGapCountPerSecond = 1,
    maxFlatlineRatioUsable = 0.20,
    maxFlatlineRatioExcellent = 0.01,
    divergenceDeltaMin = 0.20f,
    divergenceProbabilityMin = 0.65f,
    baselineWindowCount = 10
)

package com.stressdetector.shared

/**
 * Explicit Galaxy Watch Sensor SDK Heart Rate & IBI packet for Verbatim+ live observation.
 * Strictly isolated from canonical PPG Green / IR / Red channels and model predictions.
 */
data class WearHeartRateBatch(
    val sessionId: String,
    val sessionNonce: String = "",
    val generation: Long = 0L,
    val sampleTimestampElapsedMillis: Long,
    val sampleTimestampEpochMillis: Long,
    val bpm: Int,
    val heartRateStatus: Int,
    val ibiMillisList: List<Int> = emptyList(),
    val ibiStatusList: List<Int> = emptyList(),
    val source: String = SOURCE_WATCH_SDK_HR
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(generation >= 0L) { "generation must be non-negative" }
        require(sampleTimestampElapsedMillis >= 0L) { "sampleTimestampElapsedMillis must be non-negative" }
        require(bpm >= 0) { "bpm must be non-negative" }
        require(ibiMillisList.size <= MAX_IBI_ENTRIES) { "ibiMillisList exceeds max entries" }
        require(ibiStatusList.size <= MAX_IBI_ENTRIES) { "ibiStatusList exceeds max entries" }
        require(source.isNotBlank()) { "source must not be blank" }
    }

    val validIbiMillis: List<Int>
        get() = ibiMillisList.filterIndexed { index, ibi ->
            val status = ibiStatusList.getOrElse(index) { IBI_STATUS_OK }
            status == IBI_STATUS_OK && ibi in MIN_PLAUSIBLE_IBI_MS..MAX_PLAUSIBLE_IBI_MS
        }

    companion object {
        const val SOURCE_WATCH_SDK_HR = "WATCH_SDK_HR"
        const val IBI_STATUS_OK = 0
        const val MIN_PLAUSIBLE_IBI_MS = 300
        const val MAX_PLAUSIBLE_IBI_MS = 1500
        const val MAX_IBI_ENTRIES = 128
    }
}

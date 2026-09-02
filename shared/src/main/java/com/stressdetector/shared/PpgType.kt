package com.stressdetector.shared

enum class PpgType(
    val messagePath: String,
    /** Raw rate emitted by the individual Samsung tracker used by the active PDSatu-compatible path. */
    val sourceRateHz: Int,
    /** Canonical rate required by the current phone model. */
    val samplingRateHz: Int,
    val rawBatchDurationSeconds: Int,
    val assetSuffix: String
) {
    GREEN(MessagePath.PPG_GREEN, 25, 25, 12, "ppg_green"),
    IR(MessagePath.PPG_IR, 100, 100, 3, "ppg_ir"),
    RED(MessagePath.PPG_RED, 100, 100, 3, "ppg_red");

    companion object {
        /** Channels acquired from the three individual Samsung PPG trackers. */
        val acquisitionChannels: Set<PpgType> = entries.toSet()

        /** Independent ONNX inference channels accepted by the current release. */
        val inferenceChannels: Set<PpgType> = setOf(GREEN, IR)

        /** Compatibility alias for code whose "production" concern is inference. */
        val productionChannels: Set<PpgType> = inferenceChannels

        fun fromPath(path: String): PpgType? = entries.firstOrNull { it.messagePath == path }
        fun fromWire(value: String): PpgType = entries.first { it.name == value }
    }

    val isAcquisitionChannel: Boolean get() = this in acquisitionChannels
    val isProductionChannel: Boolean get() = this in productionChannels
    val modelRateHz: Int get() = samplingRateHz
    val sourceSamplesPerBatch: Int get() = sourceRateHz * rawBatchDurationSeconds

    @Deprecated("Use rawBatchDurationSeconds")
    val batchSeconds: Int get() = rawBatchDurationSeconds
}

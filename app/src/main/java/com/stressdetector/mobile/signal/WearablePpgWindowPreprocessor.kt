package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.PpgFilterContract
import com.stressdetector.shared.PpgTransportDiagnostics
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionConfig
import kotlin.math.roundToLong

/** A raw-watch window that is safe to pass to the canonical feature extractor. */
data class PreparedPpgWindow(
    val type: PpgType,
    val timestampsMillis: LongArray,
    val filteredValues: DoubleArray,
    val rawSampleCount: Int,
    val interpolatedSampleCount: Int,
    val sessionElapsedStartMillis: Long = 0L,
    val sessionElapsedEndMillis: Long = 0L,
    val elapsedTimestampsMillis: LongArray = LongArray(0),
    val interpolatedMask: BooleanArray = BooleanArray(filteredValues.size),
    val motionAnnotation: MotionAnnotation = MotionAnnotation(),
    val processingStatus: String = "ready",
    val rejectionReason: String? = null,
    /** Target samples interpolated across a true source-rate gap. */
    val sourceGapInterpolatedSampleCount: Int = 0,
    val sourceGapInterpolatedMask: BooleanArray = BooleanArray(filteredValues.size),
    val sourceRateHz: Double = type.sourceRateHz.toDouble(),
    val modelRateHz: Double = type.modelRateHz.toDouble(),
    val preprocessingVersion: String = "wearable_ppg_v1",
    val featureVersion: String = "v3",
    val manifestFingerprint: String? = null,
    val sessionId: String? = null,
    val windowId: String? = null,
    val duplicateSampleCount: Int = 0,
    val observedCadenceHz: Double? = null,
    val maxObservedGapMillis: Long = 0L,
    val filterAlgorithm: String = PpgFilterContract.ALGORITHM,
    val filterOrder: Int = 4,
    val filterLowCutHz: Double = 0.0,
    val filterHighCutHz: Double = 0.0,
    val filterContractFingerprint: String? = null,
    val transportDiagnostics: PpgTransportDiagnostics? = null
) {
    val size: Int get() = filteredValues.size
}

sealed interface PpgPreprocessResult {
    data class Ready(val window: PreparedPpgWindow) : PpgPreprocessResult
    data class WarmingUp(val currentSamples: Int, val requiredSamples: Int) : PpgPreprocessResult
    data class Rejected(val reason: String, val diagnostics: PpgRejectionDiagnostics? = null) : PpgPreprocessResult
}

/** Metadata-only reason for a rejected window; raw PPG is never retained. */
data class PpgRejectionDiagnostics(
    val channel: PpgType,
    val sourceRateHz: Double,
    val modelRateHz: Double,
    val sessionId: String? = null,
    val windowId: String? = null,
    val observedStartMillis: Long? = null,
    val observedEndMillis: Long? = null,
    val coverageMillis: Long = 0L,
    val observedCadenceHz: Double? = null,
    val maxGapMillis: Long = 0L,
    val filterAlgorithm: String? = null,
    val filterOrder: Int? = null,
    val filterLowCutHz: Double? = null,
    val filterHighCutHz: Double? = null,
    val filterContractFingerprint: String? = null,
    val acceptedSampleCount: Int = 0,
    val rejectedSampleCount: Int = 0,
    val duplicateSampleCount: Int = 0,
    val sequenceLossCount: Long = 0L,
    val rejectionReason: String
)

/** Immutable model/preprocessing contract supplied by the manifest. */
data class PpgPreprocessingContract(
    val windowSeconds: Int = SessionConfig.INFERENCE_WINDOW_SECONDS,
    val sourceRateHz: Double? = null,
    val modelRateHz: Double? = null,
    val preprocessingVersion: String = "wearable_ppg_v1",
    val featureVersion: String = "v3",
    val manifestFingerprint: String? = null,
    val filterContract: PpgFilterContract? = null
) {
    init {
        require(windowSeconds > 0) { "windowSeconds must be positive" }
        sourceRateHz?.let { require(it.isFinite() && it > 0.0) { "sourceRateHz must be positive" } }
        modelRateHz?.let { require(it.isFinite() && it > 0.0) { "modelRateHz must be positive" } }
        require(preprocessingVersion.isNotBlank()) { "preprocessingVersion must not be blank" }
        require(featureVersion.isNotBlank()) { "featureVersion must not be blank" }
        filterContract?.let { require(it.fingerprint.isNotBlank()) { "missing_ppg_filter_fingerprint" } }
    }
}

/** Bounded signal-quality policy; kept separate from model identity. */
data class PpgPreprocessingPolicy(
    val maxInterpolationGapMillis: Long = 500L,
    val maximumCadenceJitterRatio: Double = 0.20,
    /** Require at least 20% of the contract rate before accepting a window. */
    val minimumObservedRateRatio: Double = 0.20
) {
    init {
        require(maxInterpolationGapMillis >= 0L) { "maxInterpolationGapMillis must be non-negative" }
        require(maximumCadenceJitterRatio >= 0.0) { "maximumCadenceJitterRatio must be non-negative" }
        require(minimumObservedRateRatio in 0.0..1.0) { "minimumObservedRateRatio must be in [0,1]" }
    }
}

/**
 * Single phone-side boundary for raw Galaxy Watch PPG. The watch is kept raw;
 * this class owns time normalization, bounded interpolation, filtering, and
 * MADC so model callers cannot accidentally preprocess twice.
 */
class WearablePpgWindowPreprocessor(
    private val contract: PpgPreprocessingContract = PpgPreprocessingContract(),
    private val qualityPolicy: PpgPreprocessingPolicy = PpgPreprocessingPolicy(),
    private val butterworthFilter: ButterworthFilter = ButterworthFilter(),
    private val motionAnnotator: MotionArtifactAnnotator = MotionArtifactAnnotator()
) {
    fun prepare(snapshot: WindowSnapshot): PpgPreprocessResult = prepareInternal(snapshot, contract.windowSeconds)

    /**
     * Prepares the retained session once. Unlike [prepare], this path uses the
     * actual available session span and applies the canonical filter exactly
     * once; core/model windows are slices of its filtered output.
     */
    fun prepareSession(snapshot: WindowSnapshot): PpgPreprocessResult = prepareInternal(snapshot, targetDurationSeconds = null)

    private fun prepareInternal(snapshot: WindowSnapshot, targetDurationSeconds: Int?): PpgPreprocessResult {
        if (snapshot.timestampsMillis.size != snapshot.rawValues.size) {
            return PpgPreprocessResult.Rejected("ppg_shape_mismatch")
        }
        if (snapshot.timestampsMillis.isEmpty()) {
            return PpgPreprocessResult.WarmingUp(0, requiredSamples(snapshot.type))
        }
        // Raw buffers preserve duplicate callback timestamps for transport and
        // diagnostics. Canonical preprocessing collapses only here, once.
        val deduplicated = collapseDuplicatesForResampling(snapshot)
            ?: return PpgPreprocessResult.Rejected("ppg_timestamp_out_of_order")
        val duplicateSampleCount = (snapshot.timestampsMillis.size - deduplicated.timestamps.size).coerceAtLeast(0)
        val sourceRateHz = sourceRate(snapshot.type)
        val modelRateHz = modelRate(snapshot.type)
        val required = targetDurationSeconds?.let { requiredSamples(snapshot.type, it) }
        val requiredDuration = targetDurationSeconds?.let { requiredDurationMillis(snapshot.type, it) }
        val boundaryTolerance = sourceBoundaryToleranceMillis(sourceRateHz)
        val availableDuration = deduplicated.timestamps.lastOrNull()?.minus(
            deduplicated.timestamps.firstOrNull() ?: 0L
        ) ?: 0L
        // Samsung source cadence is quantized to integer milliseconds. Allow
        // at most one source interval at either boundary; target timestamps
        // stay within captured endpoints and no source sample is extrapolated.
        if (required != null &&
            requiredDuration != null &&
            availableDuration + boundaryTolerance < requiredDuration
        ) {
            return PpgPreprocessResult.WarmingUp(deduplicated.timestamps.size, required)
        }
        if (availableDuration <= 0L) {
            return PpgPreprocessResult.Rejected("ppg_duration_insufficient")
        }
        val expectedInterval = sourceIntervalMillis(sourceRateHz)
        if (deduplicated.timestamps.indices.drop(1).any { index ->
                deduplicated.timestamps[index] -
                    deduplicated.timestamps[index - 1] > qualityPolicy.maxInterpolationGapMillis
            }
        ) {
            return PpgPreprocessResult.Rejected("ppg_gap_exceeds_interpolation_policy")
        }
        if (!hasPlausibleCadence(deduplicated.timestamps, expectedInterval)) {
            return PpgPreprocessResult.Rejected("ppg_cadence_unstable")
        }

        val targetTimes = canonicalTargetTimes(
            firstTimestamp = deduplicated.timestamps.first(),
            lastTimestamp = deduplicated.timestamps.last(),
            type = snapshot.type,
            targetDurationSeconds = targetDurationSeconds
        )
        if (targetDurationSeconds != null &&
            deduplicated.timestamps.first() - targetTimes.first() > boundaryTolerance
        ) {
            return PpgPreprocessResult.WarmingUp(
                deduplicated.timestamps.size,
                required ?: requiredSamples(snapshot.type)
            )
        }
        val resampled = resampleBounded(deduplicated, targetTimes, expectedInterval)
            ?: return PpgPreprocessResult.Rejected("ppg_gap_exceeds_interpolation_policy")
        if (resampled.values.all { it == resampled.values.first() }) {
            return PpgPreprocessResult.Rejected("ppg_flatline_signal")
        }

        val filterContract = contract.filterContract
            ?: return PpgPreprocessResult.Rejected(
                "model_contract_mismatch",
                rejectionDiagnostics(
                    snapshot = snapshot,
                    sourceRateHz = sourceRateHz,
                    modelRateHz = modelRateHz,
                    deduplicatedCount = deduplicated.timestamps.size,
                    duplicateSampleCount = duplicateSampleCount,
                    reason = "model_contract_mismatch"
                )
            )
        val filtered = try {
            butterworthFilter.filter(
                signal = resampled.values,
                samplingRateHz = modelRateHz,
                contract = filterContract
            )
        } catch (_: IllegalArgumentException) {
            return PpgPreprocessResult.Rejected(
                "ppg_filter_precondition_failed",
                rejectionDiagnostics(
                    snapshot = snapshot,
                    sourceRateHz = sourceRateHz,
                    modelRateHz = modelRateHz,
                    observedCadenceHz = observedCadence(deduplicated.timestamps),
                    maxGapMillis = maxGap(deduplicated.timestamps),
                    deduplicatedCount = deduplicated.timestamps.size,
                    duplicateSampleCount = duplicateSampleCount,
                    filterContract = filterContract,
                    reason = "ppg_filter_precondition_failed"
                )
            )
        }
        if (filtered.any { !it.isFinite() }) {
            return PpgPreprocessResult.Rejected("ppg_filter_output_non_finite")
        }
        val elapsedTargetTimes = LongArray(targetTimes.size) { index ->
            (
                snapshot.sessionElapsedStartMillis +
                    (targetTimes[index] - deduplicated.timestamps.first())
                ).coerceAtLeast(0L)
        }
        return PpgPreprocessResult.Ready(
            PreparedPpgWindow(
                type = snapshot.type,
                timestampsMillis = targetTimes,
                filteredValues = filtered,
                rawSampleCount = deduplicated.timestamps.size,
                interpolatedSampleCount = resampled.interpolatedMask.count { it },
                sessionElapsedStartMillis = snapshot.sessionElapsedStartMillis,
                sessionElapsedEndMillis = elapsedTargetTimes.lastOrNull()
                    ?: snapshot.sessionElapsedEndMillis,
                elapsedTimestampsMillis = elapsedTargetTimes,
                interpolatedMask = resampled.interpolatedMask,
                motionAnnotation = motionAnnotator.annotate(filtered),
                sourceGapInterpolatedSampleCount = resampled.sourceGapInterpolatedMask.count { it },
                sourceGapInterpolatedMask = resampled.sourceGapInterpolatedMask,
                sourceRateHz = sourceRateHz,
                modelRateHz = modelRateHz,
                preprocessingVersion = contract.preprocessingVersion,
                featureVersion = contract.featureVersion,
                manifestFingerprint = contract.manifestFingerprint,
                sessionId = snapshot.sessionId,
                windowId = snapshot.windowId
                    ?: "${snapshot.type.assetSuffix}:" +
                    "${elapsedTargetTimes.firstOrNull() ?: 0L}-" +
                    (elapsedTargetTimes.lastOrNull() ?: 0L),
                duplicateSampleCount = duplicateSampleCount,
                observedCadenceHz = observedCadence(deduplicated.timestamps),
                maxObservedGapMillis = maxGap(deduplicated.timestamps),
                filterAlgorithm = filterContract.algorithm,
                filterOrder = filterContract.order,
                filterLowCutHz = filterContract.lowCutHz,
                filterHighCutHz = filterContract.highCutHz,
                filterContractFingerprint = filterContract.fingerprint,
                transportDiagnostics = snapshot.transportDiagnostics
            )
        )
    }

    fun requiredSamples(type: PpgType): Int = requiredSamples(type, contract.windowSeconds)

    private fun requiredSamples(type: PpgType, durationSeconds: Int): Int =
        kotlin.math.ceil(sourceRate(type) * durationSeconds).toInt().coerceAtLeast(2)

    private fun requiredModelSamples(type: PpgType, durationSeconds: Int): Int =
        kotlin.math.ceil(modelRate(type) * durationSeconds).toInt().coerceAtLeast(2)

    private fun collapseDuplicatesForResampling(snapshot: WindowSnapshot): TimedSamples? {
        val timestamps = ArrayList<Long>(snapshot.size)
        val values = ArrayList<Double>(snapshot.size)
        var previous = Long.MIN_VALUE
        snapshot.timestampsMillis.indices.forEach { index ->
            val timestamp = snapshot.timestampsMillis[index]
            if (timestamp < previous) return null
            if (timestamp > previous) {
                timestamps += timestamp
                values += snapshot.rawValues[index].toDouble()
            }
            previous = timestamp
        }
        return TimedSamples(timestamps.toLongArray(), values.toDoubleArray())
    }

    private fun hasPlausibleCadence(timestamps: LongArray, expectedInterval: Double): Boolean {
        if (timestamps.size < 2) return false
        val intervals = LongArray(timestamps.size - 1) { index -> timestamps[index + 1] - timestamps[index] }
        if (intervals.any { it <= 0L }) return false
        // Health Tracking batches are not a metronome: valid callbacks show
        // 20/31 ms jitter and duplicate timestamps. Gate sustained density and
        // true dropout only; max gap is already checked separately.
        val spanMillis = (timestamps.last() - timestamps.first()).coerceAtLeast(1L)
        val observedRateHz = (timestamps.size - 1) * 1_000.0 / spanMillis
        return observedRateHz >= snapshotMinimumRate(expectedInterval)
    }

    private fun snapshotMinimumRate(expectedInterval: Double): Double =
        (1_000.0 / expectedInterval) * qualityPolicy.minimumObservedRateRatio

    @Suppress("ReturnCount")
    private fun canonicalTargetTimes(firstTimestamp: Long, lastTimestamp: Long, type: PpgType, targetDurationSeconds: Int?): LongArray {
        val interval = modelIntervalMillis(type)
        if (targetDurationSeconds == null) {
            val sampleCount = ((lastTimestamp - firstTimestamp) / interval).toInt() + 1
            return LongArray(sampleCount.coerceAtLeast(2)) { index ->
                (firstTimestamp + (index * interval).roundToLong()).coerceAtMost(lastTimestamp)
            }
        }
        val required = requiredModelSamples(type, targetDurationSeconds)
        val nominalSpan = ((required - 1) * interval).roundToLong()
        val start = lastTimestamp - nominalSpan
        if (firstTimestamp > start) {
            // Only the one-millisecond rounding allowance reaches this
            // branch. Preserve both real endpoints instead of extrapolating
            // a synthetic sample outside the captured interval.
            val sourceSpan = lastTimestamp - firstTimestamp
            return LongArray(required) { index ->
                firstTimestamp + (sourceSpan * index.toLong() / (required - 1))
            }
        }
        return LongArray(required) { index -> start + (index * interval).roundToLong() }
    }

    @Suppress("ReturnCount")
    private fun resampleBounded(source: TimedSamples, targetTimes: LongArray, expectedSourceIntervalMillis: Double): ResampledSamples? {
        val output = DoubleArray(targetTimes.size)
        val interpolatedMask = BooleanArray(targetTimes.size)
        val sourceGapInterpolatedMask = BooleanArray(targetTimes.size)
        var rightIndex = 0
        targetTimes.forEachIndexed { targetIndex, targetTime ->
            while (rightIndex < source.timestamps.size && source.timestamps[rightIndex] < targetTime) {
                rightIndex++
            }
            if (rightIndex < source.timestamps.size && source.timestamps[rightIndex] == targetTime) {
                output[targetIndex] = source.values[rightIndex]
                return@forEachIndexed
            }
            val leftIndex = rightIndex - 1
            if (leftIndex < 0 || rightIndex >= source.timestamps.size) return null
            val leftTime = source.timestamps[leftIndex]
            val rightTime = source.timestamps[rightIndex]
            val gap = rightTime - leftTime
            if (gap > qualityPolicy.maxInterpolationGapMillis) return null
            val weight = (targetTime - leftTime).toDouble() / gap.toDouble()
            output[targetIndex] = source.values[leftIndex] * (1.0 - weight) + source.values[rightIndex] * weight
            interpolatedMask[targetIndex] = true
            sourceGapInterpolatedMask[targetIndex] =
                gap >= (expectedSourceIntervalMillis * 2.0).roundToLong()
        }
        return ResampledSamples(output, interpolatedMask, sourceGapInterpolatedMask)
    }

    private fun sourceIntervalMillis(sourceRateHz: Double): Double = 1_000.0 / sourceRateHz

    private fun sourceBoundaryToleranceMillis(sourceRateHz: Double): Long = sourceIntervalMillis(sourceRateHz).roundToLong()

    private fun modelIntervalMillis(type: PpgType): Double = 1_000.0 / modelRate(type)

    private fun requiredDurationMillis(type: PpgType, durationSeconds: Int): Long =
        ((requiredSamples(type, durationSeconds) - 1) * sourceIntervalMillis(sourceRate(type))).roundToLong()

    private fun sourceRate(type: PpgType): Double = contract.sourceRateHz
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: type.sourceRateHz.toDouble()

    private fun modelRate(type: PpgType): Double = contract.modelRateHz
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: type.modelRateHz.toDouble()

    private fun observedCadence(timestamps: LongArray): Double? {
        if (timestamps.size < 2) return null
        val span = timestamps.last() - timestamps.first()
        return span.takeIf { it > 0L }?.let { (timestamps.size - 1) * 1_000.0 / it }
    }

    private fun maxGap(timestamps: LongArray): Long = timestamps.asSequence()
        .zipWithNext { left, right -> (right - left).coerceAtLeast(0L) }
        .maxOrNull() ?: 0L

    @Suppress("LongParameterList")
    private fun rejectionDiagnostics(
        snapshot: WindowSnapshot,
        sourceRateHz: Double,
        modelRateHz: Double,
        observedCadenceHz: Double? = null,
        maxGapMillis: Long = 0L,
        deduplicatedCount: Int,
        duplicateSampleCount: Int,
        filterContract: PpgFilterContract? = contract.filterContract,
        reason: String
    ): PpgRejectionDiagnostics = PpgRejectionDiagnostics(
        channel = snapshot.type,
        sourceRateHz = sourceRateHz,
        modelRateHz = modelRateHz,
        sessionId = snapshot.sessionId,
        windowId = snapshot.windowId,
        observedStartMillis = snapshot.sessionElapsedStartMillis,
        observedEndMillis = snapshot.sessionElapsedEndMillis.takeIf { it > 0L }
            ?: snapshot.elapsedTimestampsMillis.lastOrNull(),
        coverageMillis = (snapshot.sessionElapsedEndMillis - snapshot.sessionElapsedStartMillis)
            .takeIf { it > 0L }
            ?: snapshot.timestampsMillis.lastOrNull()?.minus(snapshot.timestampsMillis.firstOrNull() ?: 0L)
            ?: 0L,
        observedCadenceHz = observedCadenceHz,
        maxGapMillis = maxGapMillis,
        filterAlgorithm = filterContract?.algorithm,
        filterOrder = filterContract?.order,
        filterLowCutHz = filterContract?.lowCutHz,
        filterHighCutHz = filterContract?.highCutHz,
        filterContractFingerprint = filterContract?.fingerprint,
        acceptedSampleCount = snapshot.transportDiagnostics?.acceptedSampleCount?.toInt()
            ?: deduplicatedCount,
        rejectedSampleCount = snapshot.transportDiagnostics?.rejectedSampleCount?.toInt() ?: 0,
        duplicateSampleCount = duplicateSampleCount,
        sequenceLossCount = snapshot.transportDiagnostics?.sequenceLossCount ?: 0L,
        rejectionReason = reason
    )

    private data class TimedSamples(val timestamps: LongArray, val values: DoubleArray)

    private data class ResampledSamples(
        val values: DoubleArray,
        val interpolatedMask: BooleanArray,
        val sourceGapInterpolatedMask: BooleanArray
    )
}

package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.AccelerometerSample
import com.stressdetector.shared.PpgFilterContract
import com.stressdetector.shared.PpgTransportDiagnostics
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

/** A raw-watch window that is safe to pass to the canonical feature extractor. */
data class PreparedPpgWindow(
    val type: PpgType,
    val timestampsMillis: LongArray,
    val filteredValues: DoubleArray,
    /** Raw Watch values aligned one-to-one with [timestampsMillis]. */
    val rawValues: IntArray = IntArray(0),
    val rawValuesSha256: String = "",
    val filteredValuesSha256: String = "",
    val timestampBatchIds: Array<String> = emptyArray(),
    val sourceBatchIds: List<String> = emptyList(),
    val sourceBatchChecksums: List<String> = emptyList(),
    val rawSampleCount: Int,
    val interpolatedSampleCount: Int,
    val sessionElapsedStartMillis: Long = 0L,
    val sessionElapsedEndMillis: Long = 0L,
    val elapsedTimestampsMillis: LongArray = LongArray(0),
    val interpolatedMask: BooleanArray = BooleanArray(filteredValues.size),
    val motionAnnotation: MotionAnnotation = MotionAnnotation(),
    val accelerometerSamples: List<AccelerometerSample> = emptyList(),
    val processingStatus: String = "ready",
    val rejectionReason: String? = null,
    /** Target samples interpolated across a true source-rate gap. */
    val sourceGapInterpolatedSampleCount: Int = 0,
    val sourceGapInterpolatedMask: BooleanArray = BooleanArray(filteredValues.size),
    /** Observed samples retained after deterministic duplicate removal. */
    val observedSampleCount: Int = rawSampleCount,
    val observedMask: BooleanArray = BooleanArray(filteredValues.size) { true },
    /** Short-gap repair is unsupported by the active live contract and stays zero. */
    val repairedShortGapSampleCount: Int = 0,
    val repairedShortGapMask: BooleanArray = BooleanArray(filteredValues.size),
    /** True timestamp gaps are never bridged into model input. */
    val trueGapCount: Int = 0,
    val unresolvedSampleCount: Int = 0,
    /** Grid resampling is forbidden for the active run-scoped live contract. */
    val gridResampledSampleCount: Int = 0,
    val gridResampledMask: BooleanArray = BooleanArray(filteredValues.size),
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
    val observedSampleCount: Int = acceptedSampleCount,
    val trueGapCount: Int = 0,
    val unresolvedSampleCount: Int = 0,
    val gridResampledSampleCount: Int = 0,
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
    /** A larger gap is a structural rejection, never a request to manufacture samples. */
    val maxObservedGapMillis: Long = 500L,
    /** The aggregate observed cadence may differ slightly from the frozen source rate. */
    val maximumObservedRateDeviationRatio: Double = 0.20
) {
    init {
        require(maxObservedGapMillis >= 0L) { "maxObservedGapMillis must be non-negative" }
        require(maximumObservedRateDeviationRatio in 0.0..1.0) {
            "maximumObservedRateDeviationRatio must be in [0,1]"
        }
    }
}

/**
 * Single phone-side boundary for raw Galaxy Watch PPG. The watch is kept raw;
 * this class owns duplicate removal, contract-gated filtering, and MADC so
 * model callers cannot accidentally preprocess twice. It never creates a
 * timestamp grid for the active live contract.
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
        val deduplicated = collapseDuplicates(snapshot)
            ?: return PpgPreprocessResult.Rejected("ppg_timestamp_out_of_order")
        val duplicateSampleCount = (snapshot.timestampsMillis.size - deduplicated.timestamps.size).coerceAtLeast(0)
        val sourceRateHz = sourceRate(snapshot.type)
        val modelRateHz = modelRate(snapshot.type)
        val required = targetDurationSeconds?.let { requiredSamples(snapshot.type, it) }
        val requiredDuration = targetDurationSeconds?.let { requiredDurationMillis(sourceRateHz, it) }
        val boundaryTolerance = sourceBoundaryToleranceMillis(sourceRateHz)
        val availableDuration = deduplicated.timestamps.lastOrNull()?.minus(
            deduplicated.timestamps.firstOrNull() ?: 0L
        ) ?: 0L
        // Samsung source cadence is quantized to integer milliseconds. Allow
        // at most one source interval at either boundary while retaining only
        // captured samples; no endpoint is extrapolated.
        if (required != null &&
            requiredDuration != null &&
            availableDuration + boundaryTolerance < requiredDuration
        ) {
            return PpgPreprocessResult.WarmingUp(deduplicated.timestamps.size, required)
        }
        if (availableDuration <= 0L) {
            return PpgPreprocessResult.Rejected("ppg_duration_insufficient")
        }
        if (!ratesMatch(sourceRateHz, modelRateHz)) {
            return PpgPreprocessResult.Rejected(
                "ppg_rate_resampling_not_permitted",
                rejectionDiagnostics(
                    snapshot = snapshot,
                    sourceRateHz = sourceRateHz,
                    modelRateHz = modelRateHz,
                    observedCadenceHz = observedCadence(deduplicated.timestamps),
                    maxGapMillis = maxGap(deduplicated.timestamps),
                    deduplicatedCount = deduplicated.timestamps.size,
                    duplicateSampleCount = duplicateSampleCount,
                    reason = "ppg_rate_resampling_not_permitted"
                )
            )
        }
        val expectedInterval = sourceIntervalMillis(sourceRateHz)
        val trueGapCount = countTrueGaps(deduplicated.timestamps, expectedInterval)
        if (trueGapCount > 0) {
            return PpgPreprocessResult.Rejected(
                "ppg_true_gap_detected",
                rejectionDiagnostics(
                    snapshot = snapshot,
                    sourceRateHz = sourceRateHz,
                    modelRateHz = modelRateHz,
                    observedCadenceHz = observedCadence(deduplicated.timestamps),
                    maxGapMillis = maxGap(deduplicated.timestamps),
                    deduplicatedCount = deduplicated.timestamps.size,
                    duplicateSampleCount = duplicateSampleCount,
                    trueGapCount = trueGapCount,
                    reason = "ppg_true_gap_detected"
                )
            )
        }
        if (!hasPlausibleCadence(deduplicated.timestamps, expectedInterval)) {
            return PpgPreprocessResult.Rejected(
                "ppg_observed_cadence_out_of_contract",
                rejectionDiagnostics(
                    snapshot = snapshot,
                    sourceRateHz = sourceRateHz,
                    modelRateHz = modelRateHz,
                    observedCadenceHz = observedCadence(deduplicated.timestamps),
                    maxGapMillis = maxGap(deduplicated.timestamps),
                    deduplicatedCount = deduplicated.timestamps.size,
                    duplicateSampleCount = duplicateSampleCount,
                    reason = "ppg_observed_cadence_out_of_contract"
                )
            )
        }
        val observed = trailingObservedWindow(deduplicated, targetDurationSeconds, sourceRateHz)
        if (observed.values.all { it == observed.values.first() }) {
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
                signal = observed.values,
                samplingRateHz = sourceRateHz,
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
        val elapsedObservedTimes = observed.elapsedTimestamps
        val motionSamples = snapshot.accelerometerSamples.filter { sample ->
            sample.elapsedMillis >= (elapsedObservedTimes.firstOrNull() ?: Long.MAX_VALUE) &&
                sample.elapsedMillis <= (elapsedObservedTimes.lastOrNull() ?: Long.MIN_VALUE)
        }
        return PpgPreprocessResult.Ready(
            PreparedPpgWindow(
                type = snapshot.type,
                timestampsMillis = observed.timestamps,
                filteredValues = filtered,
                rawValues = observed.rawValues,
                rawValuesSha256 = sha256Text(observed.rawValues.joinToString(",")),
                filteredValuesSha256 = sha256Text(filtered.joinToString(",")),
                timestampBatchIds = observed.batchIds,
                sourceBatchIds = observed.batchIds.asList().distinct(),
                rawSampleCount = observed.timestamps.size,
                interpolatedSampleCount = 0,
                sessionElapsedStartMillis = snapshot.sessionElapsedStartMillis,
                sessionElapsedEndMillis = elapsedObservedTimes.lastOrNull()
                    ?: snapshot.sessionElapsedEndMillis,
                elapsedTimestampsMillis = elapsedObservedTimes,
                interpolatedMask = BooleanArray(observed.timestamps.size),
                motionAnnotation = motionAnnotator.annotate(
                    motionSamples,
                    elapsedObservedTimes.first(),
                    elapsedObservedTimes.last()
                ),
                accelerometerSamples = motionSamples,
                sourceGapInterpolatedSampleCount = 0,
                sourceGapInterpolatedMask = BooleanArray(observed.timestamps.size),
                observedSampleCount = observed.timestamps.size,
                observedMask = BooleanArray(observed.timestamps.size) { true },
                repairedShortGapSampleCount = 0,
                repairedShortGapMask = BooleanArray(observed.timestamps.size),
                trueGapCount = 0,
                unresolvedSampleCount = 0,
                gridResampledSampleCount = 0,
                gridResampledMask = BooleanArray(observed.timestamps.size),
                sourceRateHz = sourceRateHz,
                modelRateHz = modelRateHz,
                preprocessingVersion = contract.preprocessingVersion,
                featureVersion = contract.featureVersion,
                manifestFingerprint = contract.manifestFingerprint,
                sessionId = snapshot.sessionId,
                windowId = snapshot.windowId
                    ?: "${snapshot.type.assetSuffix}:" +
                    "${elapsedObservedTimes.firstOrNull() ?: 0L}-" +
                    (elapsedObservedTimes.lastOrNull() ?: 0L),
                duplicateSampleCount = duplicateSampleCount,
                observedCadenceHz = observedCadence(observed.timestamps),
                maxObservedGapMillis = maxGap(observed.timestamps),
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

    private fun collapseDuplicates(snapshot: WindowSnapshot): TimedSamples? {
        val timestamps = ArrayList<Long>(snapshot.size)
        val rawValues = ArrayList<Int>(snapshot.size)
        val batchIds = ArrayList<String>(snapshot.size)
        val values = ArrayList<Double>(snapshot.size)
        val elapsed = ArrayList<Long>(snapshot.size)
        val hasElapsedTimestamps = snapshot.elapsedTimestampsMillis.size == snapshot.timestampsMillis.size
        var previous = Long.MIN_VALUE
        snapshot.timestampsMillis.indices.forEach { index ->
            val timestamp = snapshot.timestampsMillis[index]
            if (timestamp < previous) return null
            if (timestamp > previous) {
                timestamps += timestamp
                rawValues += snapshot.rawValues[index]
                if (snapshot.timestampBatchIds.size == snapshot.timestampsMillis.size) {
                    batchIds += snapshot.timestampBatchIds[index]
                }
                values += snapshot.rawValues[index].toDouble()
                elapsed += if (hasElapsedTimestamps) {
                    snapshot.elapsedTimestampsMillis[index]
                } else {
                    0L
                }
            }
            previous = timestamp
        }
        val retainedTimestamps = timestamps.toLongArray()
        val retainedElapsed = if (hasElapsedTimestamps) {
            elapsed.toLongArray()
        } else {
            LongArray(retainedTimestamps.size) { index ->
                (
                    snapshot.sessionElapsedStartMillis +
                        (retainedTimestamps[index] - retainedTimestamps.first())
                    ).coerceAtLeast(0L)
            }
        }
        return TimedSamples(
            timestamps = retainedTimestamps,
            rawValues = rawValues.toIntArray(),
            batchIds = batchIds.toTypedArray(),
            values = values.toDoubleArray(),
            elapsedTimestamps = retainedElapsed
        )
    }

    private fun hasPlausibleCadence(timestamps: LongArray, expectedInterval: Double): Boolean {
        if (timestamps.size < 2) return false
        val intervals = LongArray(timestamps.size - 1) { index -> timestamps[index + 1] - timestamps[index] }
        if (intervals.any { it <= 0L }) return false
        // Health Tracking batches are not a metronome. We judge aggregate
        // sensor cadence, not each individual integer-millisecond delta.
        val spanMillis = (timestamps.last() - timestamps.first()).coerceAtLeast(1L)
        val observedRateHz = (timestamps.size - 1) * 1_000.0 / spanMillis
        val expectedRateHz = 1_000.0 / expectedInterval
        val deviation = abs(observedRateHz - expectedRateHz) / expectedRateHz
        return deviation <= qualityPolicy.maximumObservedRateDeviationRatio
    }

    private fun trailingObservedWindow(source: TimedSamples, targetDurationSeconds: Int?, sourceRateHz: Double): TimedSamples {
        if (targetDurationSeconds == null) return source
        val targetDurationMillis = requiredDurationMillis(sourceRateHz, targetDurationSeconds)
        val boundaryTolerance = sourceBoundaryToleranceMillis(sourceRateHz)
        val startInclusive = source.timestamps.last() - targetDurationMillis - boundaryTolerance
        val firstIndex = source.timestamps.indexOfFirst { it >= startInclusive }.coerceAtLeast(0)
        return source.slice(firstIndex)
    }

    private fun sourceIntervalMillis(sourceRateHz: Double): Double = 1_000.0 / sourceRateHz

    private fun sourceBoundaryToleranceMillis(sourceRateHz: Double): Long = sourceIntervalMillis(sourceRateHz).roundToLong()

    private fun requiredDurationMillis(sourceRateHz: Double, durationSeconds: Int): Long = (
        (kotlin.math.ceil(sourceRateHz * durationSeconds).toInt().coerceAtLeast(2) - 1) *
            sourceIntervalMillis(sourceRateHz)
        ).roundToLong()

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

    private fun countTrueGaps(timestamps: LongArray, expectedIntervalMillis: Double): Int {
        // Delivery jitter is tolerated, but a missing block is not silently
        // bridged. Four source intervals supports the observed 20/60 ms Green
        // jitter while rejecting an IR dropout that skips multiple 10 ms beats.
        val maximumContractGap = minOf(
            qualityPolicy.maxObservedGapMillis.toDouble(),
            expectedIntervalMillis * MAX_CONSECUTIVE_OBSERVED_INTERVALS
        )
        return timestamps.asSequence()
            .zipWithNext { left, right -> right - left }
            .count { gap -> gap > maximumContractGap }
    }

    private fun ratesMatch(sourceRateHz: Double, modelRateHz: Double): Boolean = abs(sourceRateHz - modelRateHz) <= RATE_MATCH_TOLERANCE_HZ

    @Suppress("LongParameterList")
    private fun rejectionDiagnostics(
        snapshot: WindowSnapshot,
        sourceRateHz: Double,
        modelRateHz: Double,
        observedCadenceHz: Double? = null,
        maxGapMillis: Long = 0L,
        deduplicatedCount: Int,
        duplicateSampleCount: Int,
        trueGapCount: Int = 0,
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
        observedSampleCount = deduplicatedCount,
        trueGapCount = trueGapCount,
        unresolvedSampleCount = 0,
        gridResampledSampleCount = 0,
        rejectionReason = reason
    )

    private data class TimedSamples(
        val timestamps: LongArray,
        val rawValues: IntArray,
        val batchIds: Array<String>,
        val values: DoubleArray,
        val elapsedTimestamps: LongArray
    ) {
        fun slice(fromIndex: Int): TimedSamples = TimedSamples(
            timestamps = timestamps.copyOfRange(fromIndex, timestamps.size),
            rawValues = rawValues.copyOfRange(fromIndex, rawValues.size),
            batchIds = batchIds.takeIf { it.size == timestamps.size }
                ?.copyOfRange(fromIndex, batchIds.size)
                ?: emptyArray(),
            values = values.copyOfRange(fromIndex, values.size),
            elapsedTimestamps = elapsedTimestamps.copyOfRange(fromIndex, elapsedTimestamps.size)
        )
    }

    private companion object {
        const val RATE_MATCH_TOLERANCE_HZ = 1.0e-6
        const val MAX_CONSECUTIVE_OBSERVED_INTERVALS = 4.0
    }

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

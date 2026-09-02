package com.stressdetector.mobile.domain.engine

import com.stressdetector.shared.PpgType

/** Pure reducer for consecutive physiological markers. */
class DivergenceEpisodeTracker(private val mergeGapMillis: Long) {
    fun add(existing: List<DivergenceEvent>, candidate: DivergenceEvent): List<DivergenceEvent> {
        val previousIndex = existing.indexOfLast { marker ->
            val gapMillis = candidate.episodeStartElapsedMillis - marker.episodeEndElapsedMillis
            marker.sessionId == candidate.sessionId &&
                marker.markerType == candidate.markerType &&
                marker.channel == candidate.channel &&
                marker.sourceChannels == candidate.sourceChannels &&
                gapMillis in 0L..mergeGapMillis
        }
        val previous = existing.getOrNull(previousIndex)
        if (previous == null) {
            return existing + candidate
        }

        val merged = previous.copy(
            timestampMs = candidate.timestampMs,
            markerElapsedMillis = candidate.markerElapsedMillis,
            episodeEndElapsedMillis = maxOf(
                previous.episodeEndElapsedMillis,
                candidate.episodeEndElapsedMillis
            ),
            episodeStartElapsedMillis = minOf(
                previous.episodeStartElapsedMillis,
                candidate.episodeStartElapsedMillis
            ),
            currentStressProbability = candidate.currentStressProbability ?: previous.currentStressProbability,
            baselineStressProbability = candidate.baselineStressProbability ?: previous.baselineStressProbability,
            divergenceDelta = listOfNotNull(previous.divergenceDelta, candidate.divergenceDelta).maxOrNull(),
            confidence = candidate.confidence,
            signalQuality = candidate.signalQuality,
            contextWindowStartElapsedMillis = minOf(
                previous.contextWindowStartElapsedMillis,
                candidate.contextWindowStartElapsedMillis
            ),
            contextWindowEndElapsedMillis = candidate.contextWindowEndElapsedMillis,
            sourceWindowId = candidate.sourceWindowId,
            bpm = candidate.bpm ?: previous.bpm,
            baselineBpm = candidate.baselineBpm ?: previous.baselineBpm,
            peakBpm = listOfNotNull(previous.peakBpm, candidate.peakBpm, candidate.bpm).maxOrNull(),
            bpmDelta = candidate.bpmDelta ?: previous.bpmDelta,
            bpmTrend = candidate.bpmTrend ?: previous.bpmTrend,
            sourceChannels = previous.sourceChannels,
            copyText = candidate.copyText
        )
        return existing.toMutableList().also { it[previousIndex] = merged }
    }

    @Suppress("LongParameterList")
    fun candidate(
        id: String,
        sessionId: String,
        channel: PpgType,
        markerElapsedMillis: Long,
        contextStartElapsedMillis: Long,
        contextEndElapsedMillis: Long,
        currentProbability: Float?,
        baselineProbability: Float?,
        confidence: Float,
        signalQuality: Float,
        markerType: PhysiologicalMarkerType = PhysiologicalMarkerType.TEMPORAL_BASELINE_AROUSAL,
        episodeStartElapsedMillis: Long = markerElapsedMillis,
        episodeEndElapsedMillis: Long = markerElapsedMillis,
        bpm: Float? = null,
        baselineBpm: Float? = null,
        peakBpm: Float? = null,
        bpmDelta: Float? = null,
        bpmTrend: String? = null,
        markerConfigVersion: String = "physiological-markers-v1",
        copyText: String? = null,
        isElevatedArousal: Boolean = true,
        sourceChannels: Set<PpgType> = setOf(channel)
    ): DivergenceEvent = DivergenceEvent(
        id = id,
        sessionId = sessionId,
        timestampMs = markerElapsedMillis,
        currentStressProbability = currentProbability,
        baselineStressProbability = baselineProbability,
        divergenceDelta = if (currentProbability != null && baselineProbability != null) {
            currentProbability - baselineProbability
        } else {
            null
        },
        isElevatedArousal = isElevatedArousal,
        channel = channel,
        markerElapsedMillis = markerElapsedMillis,
        episodeStartElapsedMillis = episodeStartElapsedMillis,
        episodeEndElapsedMillis = episodeEndElapsedMillis,
        contextWindowStartElapsedMillis = contextStartElapsedMillis,
        contextWindowEndElapsedMillis = contextEndElapsedMillis,
        confidence = confidence,
        signalQuality = signalQuality,
        sourceWindowId = "${sessionId}_${sourceChannels.sortedBy { it.ordinal }.joinToString("_")}_${markerType.name}_$markerElapsedMillis",
        markerType = markerType,
        bpm = bpm,
        baselineBpm = baselineBpm,
        peakBpm = peakBpm,
        bpmDelta = bpmDelta,
        bpmTrend = bpmTrend,
        sourceChannels = sourceChannels,
        markerConfigVersion = markerConfigVersion,
        copyText = copyText ?: markerCopy(markerType, bpm, baselineBpm, currentProbability)
    )

    @Suppress("CyclomaticComplexMethod")
    private fun markerCopy(markerType: PhysiologicalMarkerType, bpm: Float?, baselineBpm: Float?, probability: Float?): String =
        when (markerType) {
            PhysiologicalMarkerType.TEMPORAL_BASELINE_AROUSAL ->
                probability?.let { "Kenaikan arousal fisiologis terhadap baseline temporal (${(it * 100).toInt()}%)" }
                    ?: "Kenaikan arousal fisiologis terhadap baseline temporal (probabilitas model tidak tersedia)"
            PhysiologicalMarkerType.CHANNEL_AGREEMENT ->
                "Perbedaan probabilitas kanal PPG Green/IR; bukan fusi model"
            PhysiologicalMarkerType.BPM_TRAJECTORY ->
                "Perubahan trajektori BPM: ${bpm?.toInt()?.toString() ?: "tidak tersedia"} BPM dari baseline " +
                    "${baselineBpm?.toInt()?.toString() ?: "tidak tersedia"} BPM"
            PhysiologicalMarkerType.MODEL_AROUSAL ->
                probability?.let { "Marker arousal fisiologis dari model PPG (${(it * 100).toInt()}%)" }
                    ?: "Marker arousal fisiologis dari model PPG (probabilitas model tidak tersedia)"
            PhysiologicalMarkerType.BPM_RISE ->
                "Kenaikan BPM sementara: ${bpm?.toInt()?.toString() ?: "tidak tersedia"} BPM dari baseline " +
                    "${baselineBpm?.toInt()?.toString() ?: "tidak tersedia"} BPM"
            PhysiologicalMarkerType.BPM_RECOVERY ->
                "BPM kembali mendekati baseline: ${bpm?.toInt()?.toString() ?: "tidak tersedia"} BPM"
        }
}

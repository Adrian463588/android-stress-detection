@file:Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber", "NestedBlockDepth", "TooManyFunctions", "ReturnCount")

package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import java.util.Locale
import kotlin.math.abs

/**
 * Stateful, session-scoped skin temperature tracker and statistical thermal divergence detector.
 *
 * Enforces:
 * - Warm-up stabilization & personal median baseline
 * - Baseline freeze during active episodes
 * - Wearer-role / wrist-contact reset
 * - Pause exclusion & stop cutoff fail-closed behavior
 * - Ambient temperature swing confound abstention
 * - Single-episode deduplication + hysteresis recovery updates (zero interpolation)
 */
class ThermalMonitoringEngine(private val config: ThermalPolicyConfig = ThermalPolicyConfig.CANDIDATE_OBSERVE_ONLY) {
    private var activeSessionId: String? = null
    private var activeWearerRole: String = WatchWearerRole.CLIENT.name
    private var isPaused: Boolean = false
    private var watchEnabled: Boolean = false
    private var stopCutoffElapsedMs: Long? = null

    private val seenKeys = HashSet<String>()
    private val allObservations = mutableListOf<SkinTemperatureObservation>()
    private var epochAnchorElapsedMs: Long? = null
    private var contactLostSinceElapsedMs: Long? = null
    private var baselineTemperatureC: Float? = null
    private var pendingDirection: ThermalDirection? = null
    private var pendingStartElapsedMs: Long? = null
    private var lastEpisodeEndedElapsedMs: Long? = null
    private var episodeCounter: Int = 0
    private var activeEpisode: ActiveThermalEpisode? = null
    private val persistedEpisodes = linkedMapOf<String, DivergenceEventEntity>()

    private data class ActiveThermalEpisode(
        val eventId: String,
        val direction: ThermalDirection,
        val startElapsedMs: Long,
        val detectedElapsedMs: Long,
        val frozenBaselineC: Float,
        var peakObservedC: Float,
        var peakDeltaC: Float,
        var peakSlopeCPerMin: Float?,
        var ambientTemperatureC: Float?,
        var ambientDeltaC: Float?,
        var endElapsedMs: Long,
        val evidenceIds: MutableList<String>,
        var linkedSegmentId: String?
    )

    @Synchronized
    fun beginSession(sessionId: String, wearerRole: String, watchEnabled: Boolean) {
        activeSessionId = sessionId
        activeWearerRole = wearerRole
        this.watchEnabled = watchEnabled
        isPaused = false
        stopCutoffElapsedMs = null
        seenKeys.clear()
        allObservations.clear()
        epochAnchorElapsedMs = null
        contactLostSinceElapsedMs = null
        baselineTemperatureC = null
        pendingDirection = null
        pendingStartElapsedMs = null
        lastEpisodeEndedElapsedMs = null
        episodeCounter = 0
        activeEpisode = null
        persistedEpisodes.clear()
    }

    @Synchronized
    fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    @Synchronized
    fun applyStopCutoff(cutoffElapsedMs: Long) {
        stopCutoffElapsedMs = cutoffElapsedMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun onWearerRoleChanged(newRole: String, elapsedMillis: Long) {
        if (newRole == activeWearerRole) return
        activeWearerRole = newRole
        resetBaselineAndActiveCandidate(elapsedMillis)
    }

    @Synchronized
    fun ingest(
        observation: SkinTemperatureObservation,
        transcriptSegments: List<SpeechSegment> = emptyList()
    ): List<DivergenceEventEntity> {
        val sessionId = activeSessionId ?: return emptyList()
        if (observation.sessionId != sessionId) return emptyList()
        val cutoff = stopCutoffElapsedMs
        if (cutoff != null && observation.elapsedMillis > cutoff) return emptyList()

        val dedupKey = "${observation.generation}:${observation.sequenceNumber}:${observation.sensorTimestampMs}"
        if (!seenKeys.add(dedupKey)) return emptyList()

        val stored = observation.copy(isPaused = observation.isPaused || isPaused)
        insertSortedObservation(stored)

        if (stored.isPaused) return emptyList()

        if (!stored.isValid || stored.skinTemperatureC == null || !stored.skinTemperatureC.isFinite()) {
            handleContactInvalid(stored.elapsedMillis)
            return emptyList()
        }

        // Valid observation arrives after prolonged off-wrist invalid status -> reset personal baseline.
        val lostSince = contactLostSinceElapsedMs
        if (lostSince != null && (stored.elapsedMillis - lostSince) >= config.contactLossResetMs) {
            resetBaselineAndActiveCandidate(stored.elapsedMillis)
        }
        contactLostSinceElapsedMs = null

        if (epochAnchorElapsedMs == null) {
            epochAnchorElapsedMs = stored.elapsedMillis
        }

        if (stored.wearerRole != activeWearerRole) {
            activeWearerRole = stored.wearerRole
            resetBaselineAndActiveCandidate(stored.elapsedMillis)
        }

        return evaluateThermalState(stored, transcriptSegments)
    }

    @Synchronized
    fun snapshot(currentElapsedMillis: Long, watchConnected: Boolean): LiveTemperatureSnapshot {
        if (!watchEnabled) {
            return LiveTemperatureSnapshot(
                status = ThermalSignalStatus.UNAVAILABLE,
                autoMarkerEnabled = config.autoMarkerEnabled,
                algorithmVersion = config.algorithmVersion,
                statusReason = "Pemantauan Watch nonaktif untuk sesi ini"
            )
        }

        val anchor = epochAnchorElapsedMs
        val validActive = allObservations.filter {
            !it.isPaused && it.isValid && it.skinTemperatureC != null && it.skinTemperatureC.isFinite()
        }
        val latestAny = allObservations.lastOrNull()
        val latestValid = validActive.lastOrNull()
        val windowStart = (currentElapsedMillis - config.chartWindowMs).coerceAtLeast(0L)
        val recentPoints = allObservations.filter { it.elapsedMillis in windowStart..currentElapsedMillis }

        val stats = computeSkinTemperatureStatistics(
            observations = allObservations.filter { !it.isPaused },
            baselineC = baselineTemperatureC,
            slopeCPerMin = latestValid?.let { computeSlopeCPerMin(it.elapsedMillis) }
        )

        val status: ThermalSignalStatus
        val reason: String?
        val warmupRemaining: Int = if (anchor == null) {
            (config.warmupDurationMs / 1_000L).toInt()
        } else {
            (((anchor + config.warmupDurationMs) - currentElapsedMillis).coerceAtLeast(0L) / 1_000L).toInt()
        }

        when {
            isPaused -> {
                status = ThermalSignalStatus.PAUSED
                reason = "Pemantauan dijeda"
            }
            !watchConnected && latestAny == null -> {
                status = ThermalSignalStatus.DISCONNECTED
                reason = "Watch belum terhubung"
            }
            latestAny == null -> {
                status = ThermalSignalStatus.WARMING_UP
                reason = "Menunggu sampel suhu kulit dari Watch"
            }
            !latestAny.isValid && (latestValid == null || latestAny.elapsedMillis >= latestValid.elapsedMillis) -> {
                status = ThermalSignalStatus.INVALID_STATUS
                reason = "Pembacaan suhu Watch tidak valid (status=${latestAny.measurementStatus})"
            }
            latestValid != null && (currentElapsedMillis - latestValid.elapsedMillis) > config.staleTimeoutMs -> {
                status = ThermalSignalStatus.STALE
                reason = "Data suhu tertunda lebih dari ${config.staleTimeoutMs / 1_000L} detik"
            }
            isAmbientConfounded(currentElapsedMillis) -> {
                status = ThermalSignalStatus.AMBIENT_CONFOUND
                reason = "Perubahan suhu lingkungan besar; penanda otomatis ditahan"
            }
            baselineTemperatureC == null || warmupRemaining > 0 -> {
                status = ThermalSignalStatus.WARMING_UP
                reason = if (warmupRemaining > 0) {
                    "Kalibrasi baseline termal (${warmupRemaining}d tersisa)"
                } else {
                    "Mengumpulkan sampel valid baseline (${stats.validCount}/${config.minWarmupValidSamples})"
                }
            }
            else -> {
                status = ThermalSignalStatus.ACTIVE
                reason = if (config.autoMarkerEnabled) {
                    "Baseline personal aktif"
                } else {
                    "Observasi suhu aktif · penanda otomatis nonaktif sebelum validasi ambang"
                }
            }
        }

        val currentVal = if (status == ThermalSignalStatus.STALE || status == ThermalSignalStatus.INVALID_STATUS) {
            null
        } else {
            latestValid?.skinTemperatureC
        }
        val currentDelta = if (currentVal != null && baselineTemperatureC != null) {
            currentVal - baselineTemperatureC!!
        } else {
            null
        }

        return LiveTemperatureSnapshot(
            status = status,
            currentSkinTempC = currentVal,
            baselineSkinTempC = baselineTemperatureC,
            deltaC = currentDelta,
            ambientTempC = stats.latestAmbientC,
            ambientDeltaC = latestValid?.let { computeAmbientDelta(it.elapsedMillis) },
            slopeCPerMin = stats.slopeCPerMin,
            minSkinTempC = stats.minC,
            maxSkinTempC = stats.maxC,
            meanSkinTempC = stats.meanC,
            medianSkinTempC = stats.medianC,
            validSampleCount = stats.validCount,
            totalSampleCount = stats.totalCount,
            measurementStatusCode = latestAny?.measurementStatus,
            lastUpdatedElapsedMillis = latestAny?.elapsedMillis,
            warmupRemainingSeconds = warmupRemaining,
            autoMarkerEnabled = config.autoMarkerEnabled,
            algorithmVersion = config.algorithmVersion,
            statusReason = reason,
            recentPoints = recentPoints,
            activeEpisodeId = activeEpisode?.eventId,
            activeDirection = activeEpisode?.direction
        )
    }

    @Synchronized
    fun allObservations(): List<SkinTemperatureObservation> = allObservations.toList()

    @Synchronized
    fun markerEntities(): List<DivergenceEventEntity> = persistedEpisodes.values.toList()

    private fun handleContactInvalid(elapsedMillis: Long) {
        if (contactLostSinceElapsedMs == null) {
            contactLostSinceElapsedMs = elapsedMillis
        }
        pendingDirection = null
        pendingStartElapsedMs = null
    }

    private fun resetBaselineAndActiveCandidate(elapsedMillis: Long) {
        epochAnchorElapsedMs = elapsedMillis
        baselineTemperatureC = null
        pendingDirection = null
        pendingStartElapsedMs = null
        activeEpisode?.let { open ->
            closeEpisode(open, closeElapsedMs = elapsedMillis, recovered = false)
        }
        activeEpisode = null
    }

    private fun insertSortedObservation(observation: SkinTemperatureObservation) {
        val idx = allObservations.indexOfLast { it.elapsedMillis <= observation.elapsedMillis }
        if (idx < 0) {
            allObservations.add(0, observation)
        } else {
            allObservations.add(idx + 1, observation)
        }
    }

    private fun evaluateThermalState(
        latest: SkinTemperatureObservation,
        transcriptSegments: List<SpeechSegment>
    ): List<DivergenceEventEntity> {
        val anchor = epochAnchorElapsedMs ?: return emptyList()
        val skinC = latest.skinTemperatureC ?: return emptyList()

        val validInBaselinePool = allObservations.filter {
            !it.isPaused &&
                it.isValid &&
                it.skinTemperatureC != null &&
                it.skinTemperatureC.isFinite() &&
                it.elapsedMillis >= anchor
        }

        // Baseline stabilizes after warmup + min sample count; stays frozen while episode active.
        val openEpisode = activeEpisode
        if (openEpisode == null &&
            (latest.elapsedMillis - anchor) >= config.warmupDurationMs &&
            validInBaselinePool.size >= config.minWarmupValidSamples
        ) {
            val nonEpisodeSamples = validInBaselinePool.filter { sample ->
                persistedEpisodes.values.none { ep ->
                    sample.elapsedMillis in ep.episodeStartElapsedMillis..ep.episodeEndElapsedMillis
                }
            }.mapNotNull { it.skinTemperatureC }
            if (nonEpisodeSamples.size >= config.minWarmupValidSamples) {
                baselineTemperatureC = medianFloat(nonEpisodeSamples)
            }
        }

        val baseline = baselineTemperatureC ?: return emptyList()
        if (!config.autoMarkerEnabled) return emptyList()

        val ambientDelta = computeAmbientDelta(latest.elapsedMillis)
        if (ambientDelta != null && abs(ambientDelta) > config.maxAmbientSwingC) {
            pendingDirection = null
            pendingStartElapsedMs = null
            return emptyList()
        }

        val delta = skinC - baseline
        val slope = computeSlopeCPerMin(latest.elapsedMillis)
        val recoveryThreshold = config.deltaThresholdC - config.hysteresisC

        if (openEpisode != null) {
            openEpisode.endElapsedMs = latest.elapsedMillis
            openEpisode.evidenceIds += latest.sampleId
            openEpisode.ambientTemperatureC = latest.ambientTemperatureC ?: openEpisode.ambientTemperatureC
            if (abs(delta) > abs(openEpisode.peakDeltaC)) {
                openEpisode.peakObservedC = skinC
                openEpisode.peakDeltaC = delta
                openEpisode.peakSlopeCPerMin = slope
                openEpisode.ambientDeltaC = ambientDelta
            }
            val recovered = when (openEpisode.direction) {
                ThermalDirection.INCREASE -> delta <= recoveryThreshold
                ThermalDirection.DECREASE -> -delta <= recoveryThreshold
                ThermalDirection.RECOVERY -> true
            }
            val updatedEntity = if (recovered) {
                closeEpisode(openEpisode, latest.elapsedMillis, recovered = true).also {
                    activeEpisode = null
                    lastEpisodeEndedElapsedMs = latest.elapsedMillis
                }
            } else {
                buildEntity(openEpisode, directionOverride = openEpisode.direction).also {
                    persistedEpisodes[it.eventId] = it
                }
            }
            return listOf(updatedEntity)
        }

        val cooldownUntil = (lastEpisodeEndedElapsedMs ?: Long.MIN_VALUE) + config.cooldownMs
        if (latest.elapsedMillis < cooldownUntil) {
            pendingDirection = null
            pendingStartElapsedMs = null
            return emptyList()
        }

        val candidateDirection = when {
            delta >= config.deltaThresholdC -> ThermalDirection.INCREASE
            delta <= -config.deltaThresholdC -> ThermalDirection.DECREASE
            else -> null
        }

        if (candidateDirection == null) {
            pendingDirection = null
            pendingStartElapsedMs = null
            return emptyList()
        }

        if (pendingDirection != candidateDirection) {
            pendingDirection = candidateDirection
            pendingStartElapsedMs = latest.elapsedMillis
            return emptyList()
        }

        val startMs = pendingStartElapsedMs ?: latest.elapsedMillis
        if ((latest.elapsedMillis - startMs) < config.persistenceMs) {
            return emptyList()
        }

        val windowValidCount = validInBaselinePool.count {
            it.elapsedMillis in (latest.elapsedMillis - config.analysisWindowMs)..latest.elapsedMillis
        }
        if (windowValidCount < config.minAnalysisValidSamples) {
            return emptyList()
        }

        episodeCounter += 1
        val eventId = "${latest.sessionId}:thermal:$episodeCounter"
        val linkedSegmentId = transcriptSegments
            .filter { it.sessionId == latest.sessionId && it.isFinal }
            .firstOrNull { startMs in (it.startOffsetMs - 5_000L)..(latest.elapsedMillis + 5_000L) }
            ?.id
        val newEpisode = ActiveThermalEpisode(
            eventId = eventId,
            direction = candidateDirection,
            startElapsedMs = startMs,
            detectedElapsedMs = latest.elapsedMillis,
            frozenBaselineC = baseline,
            peakObservedC = skinC,
            peakDeltaC = delta,
            peakSlopeCPerMin = slope,
            ambientTemperatureC = latest.ambientTemperatureC,
            ambientDeltaC = ambientDelta,
            endElapsedMs = latest.elapsedMillis,
            evidenceIds = validInBaselinePool
                .filter { it.elapsedMillis in startMs..latest.elapsedMillis }
                .mapTo(mutableListOf()) { it.sampleId },
            linkedSegmentId = linkedSegmentId
        )
        activeEpisode = newEpisode
        pendingDirection = null
        pendingStartElapsedMs = null
        val entity = buildEntity(newEpisode, directionOverride = candidateDirection)
        persistedEpisodes[entity.eventId] = entity
        return listOf(entity)
    }

    private fun closeEpisode(episode: ActiveThermalEpisode, closeElapsedMs: Long, recovered: Boolean): DivergenceEventEntity {
        episode.endElapsedMs = closeElapsedMs
        val entity = buildEntity(
            episode = episode,
            directionOverride = if (recovered) ThermalDirection.RECOVERY else episode.direction
        )
        persistedEpisodes[entity.eventId] = entity
        return entity
    }

    private fun buildEntity(episode: ActiveThermalEpisode, directionOverride: ThermalDirection): DivergenceEventEntity {
        val dirLabel = when (episode.direction) {
            ThermalDirection.INCREASE -> "Suhu kulit meningkat"
            ThermalDirection.DECREASE -> "Suhu kulit menurun"
            ThermalDirection.RECOVERY -> "Suhu kulit pulih"
        }
        val sign = if (episode.peakDeltaC >= 0f) "+" else ""
        val copyText = if (directionOverride == ThermalDirection.RECOVERY) {
            "$dirLabel ($sign${formatOneDecimal(episode.peakDeltaC)} °C) · kembali mendekati baseline"
        } else {
            "$dirLabel ($sign${formatOneDecimal(episode.peakDeltaC)} °C terhadap baseline)"
        }
        val ambientSuffix = episode.ambientDeltaC?.takeIf { it.isFinite() }?.let {
            " · Δ sekitar ${if (it >= 0f) "+" else ""}${formatOneDecimal(it)} °C"
        }.orEmpty()
        return DivergenceEventEntity(
            eventId = episode.eventId,
            sessionId = activeSessionId.orEmpty(),
            timestampMs = episode.startElapsedMs,
            delta = episode.peakDeltaC,
            currentProbability = null,
            baselineProbability = null,
            copyText = copyText + ambientSuffix,
            channel = "SKIN_TEMPERATURE",
            markerElapsedMillis = episode.startElapsedMs,
            episodeStartElapsedMillis = episode.startElapsedMs,
            episodeEndElapsedMillis = episode.endElapsedMs,
            contextWindowStartElapsedMillis = (episode.startElapsedMs - config.analysisWindowMs).coerceAtLeast(0L),
            contextWindowEndElapsedMillis = episode.endElapsedMs,
            confidence = 1f,
            signalQuality = 1f,
            sourceWindowId = null,
            transcriptSegmentId = episode.linkedSegmentId,
            markerType = ThermalPolicyConfig.MARKER_TYPE_THERMAL_DIVERGENCE,
            sourceChannels = "SKIN_TEMPERATURE",
            markerConfigVersion = config.algorithmVersion,
            baselineValue = episode.frozenBaselineC,
            observedValue = episode.peakObservedC,
            deltaValue = episode.peakDeltaC,
            unit = "°C",
            evidenceIdsJson = MultimodalCueAnalyzer.encodeEvidenceIds(episode.evidenceIds.distinct()),
            detectedAtElapsedMillis = episode.detectedElapsedMs,
            thermalDirection = directionOverride.name,
            ambientTemperatureC = episode.ambientTemperatureC,
            ambientDeltaC = episode.ambientDeltaC,
            slopeCelsiusPerMin = episode.peakSlopeCPerMin
        )
    }

    private fun isAmbientConfounded(currentElapsedMillis: Long): Boolean {
        val swing = computeAmbientDelta(currentElapsedMillis) ?: return false
        return abs(swing) > config.maxAmbientSwingC
    }

    private fun computeAmbientDelta(currentElapsedMillis: Long): Float? {
        val windowStart = (currentElapsedMillis - config.analysisWindowMs).coerceAtLeast(0L)
        val ambients = allObservations.filter {
            !it.isPaused &&
                it.elapsedMillis in windowStart..currentElapsedMillis &&
                it.ambientTemperatureC != null &&
                it.ambientTemperatureC.isFinite()
        }.mapNotNull { it.ambientTemperatureC }
        if (ambients.size < 2) return null
        return ambients.last() - ambients.first()
    }

    private fun computeSlopeCPerMin(currentElapsedMillis: Long): Float? {
        val windowStart = (currentElapsedMillis - config.analysisWindowMs).coerceAtLeast(0L)
        val pts = allObservations.filter {
            !it.isPaused &&
                it.isValid &&
                it.skinTemperatureC != null &&
                it.skinTemperatureC.isFinite() &&
                it.elapsedMillis in windowStart..currentElapsedMillis
        }
        if (pts.size < 2) return null
        val t0 = pts.first().elapsedMillis
        val xs = pts.map { (it.elapsedMillis - t0) / 60_000.0 }
        val ys = pts.map { it.skinTemperatureC!!.toDouble() }
        val xMean = xs.average()
        val yMean = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - xMean
            num += dx * (ys[i] - yMean)
            den += dx * dx
        }
        if (den <= 1e-9) return null
        return (num / den).toFloat()
    }

    private fun formatOneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
}

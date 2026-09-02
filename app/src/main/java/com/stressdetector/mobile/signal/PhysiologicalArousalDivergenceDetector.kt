package com.stressdetector.mobile.signal

class PhysiologicalArousalDivergenceDetector(private val config: LiveMonitorConfig) {
    private var observations = 0
    private var consecutiveDivergentObservations = 0
    private var active = false
    private var lastEmittedElapsedMillis: Long? = null

    fun isDivergent(currentProbability: Float, baselineProbability: Float): Boolean =
        currentProbability - baselineProbability >= config.divergenceDeltaMin &&
            currentProbability >= config.divergenceProbabilityMin

    /**
     * Stateful live policy. A single noisy model window cannot emit an event;
     * recovery uses hysteresis and a cooldown prevents marker storms.
     */
    @Suppress("ReturnCount")
    fun observe(currentProbability: Float, baselineProbability: Float, elapsedMillis: Long): Boolean {
        if (!currentProbability.isFinite() || !baselineProbability.isFinite()) {
            reset()
            return false
        }
        observations += 1
        if (active) {
            val recovered = currentProbability < config.divergenceProbabilityMin ||
                currentProbability - baselineProbability <
                config.divergenceDeltaMin - config.divergenceRecoveryHysteresis
            if (recovered) {
                active = false
                consecutiveDivergentObservations = 0
            }
            return false
        }
        if (observations < config.divergenceWarmupObservations) {
            return false
        }
        if (isDivergent(currentProbability, baselineProbability)) {
            consecutiveDivergentObservations += 1
        } else {
            consecutiveDivergentObservations = 0
        }
        val cooldownComplete = lastEmittedElapsedMillis?.let {
            elapsedMillis - it >= config.divergenceCooldownSeconds * COOLDOWN_MILLIS
        } ?: true
        if (cooldownComplete && consecutiveDivergentObservations >= config.divergencePersistenceObservations) {
            active = true
            lastEmittedElapsedMillis = elapsedMillis
            consecutiveDivergentObservations = 0
            return true
        }
        return false
    }

    fun reset() {
        observations = 0
        consecutiveDivergentObservations = 0
        active = false
        lastEmittedElapsedMillis = null
    }

    private companion object {
        const val COOLDOWN_MILLIS = 1_000L
    }
}

package com.stressdetector.wear.service

import com.stressdetector.shared.PpgType

/**
 * Promotes a channel to ready only after confirmed normal samples.
 *
 * Samsung callbacks arrive on a dedicated thread; callers must invoke this
 * gate from their serialized lifecycle owner.
 */
internal class PpgChannelReadinessGate(private val requiredNormalSamples: Int) {
    init {
        require(requiredNormalSamples > 0) { "requiredNormalSamples must be positive" }
    }

    private val normalSampleCounts = mutableMapOf<PpgType, Int>()
    private val readyChannels = mutableSetOf<PpgType>()

    val ready: Set<PpgType>
        get() = readyChannels.toSet()

    val observedAllChannels: Boolean
        get() = normalSampleCounts.keys.containsAll(PpgType.productionChannels)

    val allChannelsReady: Boolean
        get() = readyChannels.containsAll(PpgType.productionChannels)

    /** Returns true once, at the transition from collecting to ready for [type]. */
    fun observeConfirmedNormalSample(type: PpgType): Boolean {
        if (!type.isProductionChannel) return false
        val count = (normalSampleCounts[type] ?: 0) + 1
        normalSampleCounts[type] = count
        return count >= requiredNormalSamples && readyChannels.add(type)
    }

    fun reset() {
        normalSampleCounts.clear()
        readyChannels.clear()
    }
}

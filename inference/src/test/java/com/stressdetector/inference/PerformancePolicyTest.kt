package com.stressdetector.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePolicyTest {
    private val policy =
        PackagedPerformancePolicy(
            schema_version = "1.0.0",
            performance_policy_version = "ml_dl_tiered_v1",
            performance_policy_sha256 = "a".repeat(64),
            minimum_research = MetricGate(0.70, 0.70, 0.70),
            preferred = MetricGate(0.80, 0.78, 0.83),
            research_disclaimer = "Model penelitian — validasi internal terbatas. Bukan diagnosis klinis."
        )

    @Test
    fun minimumGateIncludesExactSeventyPercentBoundary() {
        assertEquals(
            PerformanceTier.RESEARCH_USABLE,
            policy.declaredTier(0.70, 0.70, 0.70)
        )
    }

    @Test
    fun minimumGateRejectsAnyMetricBelowSeventyPercent() {
        assertEquals(
            PerformanceTier.BELOW_MINIMUM,
            policy.declaredTier(0.6999, 0.70, 0.70)
        )
    }

    @Test
    fun preferredTierRemainsSeparateFromResearchUsable() {
        assertEquals(
            PerformanceTier.RESEARCH_USABLE,
            policy.declaredTier(0.75, 0.75, 0.81)
        )
        assertEquals(
            PerformanceTier.PREFERRED,
            policy.declaredTier(0.80, 0.78, 0.83)
        )
        assertTrue(policy.minimum_research.accepts(0.70, 0.70, 0.70))
        assertFalse(policy.preferred.accepts(0.75, 0.75, 0.81))
    }
}

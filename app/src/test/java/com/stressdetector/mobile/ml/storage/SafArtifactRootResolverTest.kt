package com.stressdetector.mobile.ml.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class SafArtifactRootResolverTest {
    @Test
    fun `direct StressDetector selection never creates a nested root`() {
        val decision = SafArtifactRootResolver.resolve(evidence(selectedName = "StressDetector"))

        assertEquals(SafArtifactRootLayout.SELECTED_TREE, decision.layout)
        assertEquals(".", decision.relativeRootPath)
    }

    @Test
    fun `existing nested StressDetector with artifacts is reused`() {
        val decision = SafArtifactRootResolver.resolve(
            evidence(
                selectedName = "StressDetection",
                stressDetector = candidate(hasArtifacts = true, hasLegacyModel = true)
            )
        )

        assertEquals(SafArtifactRootLayout.CHILD_STRESS_DETECTOR, decision.layout)
    }

    @Test
    fun `generic empty tree creates exactly one StressDetector child`() {
        val decision = SafArtifactRootResolver.resolve(evidence(selectedName = "Documents"))

        assertEquals(SafArtifactRootLayout.NEW_CHILD_STRESS_DETECTOR, decision.layout)
        assertEquals("StressDetector", decision.relativeRootPath)
    }

    private fun evidence(selectedName: String, stressDetector: SafArtifactRootCandidateEvidence = candidate(exists = false)) =
        SafArtifactRootEvidence(
            selectedDisplayName = selectedName,
            selected = candidate(),
            stressDetectorChild = stressDetector,
            stressDetectionChild = candidate(exists = false)
        )

    private fun candidate(exists: Boolean = true, hasArtifacts: Boolean = false, hasLegacyModel: Boolean = false) =
        SafArtifactRootCandidateEvidence(
            exists = exists,
            hasArtifacts = hasArtifacts,
            hasLegacyModel = hasLegacyModel
        )
}

package com.stressdetector.mobile.ml.storage

enum class SafArtifactRootLayout {
    SELECTED_TREE,
    CHILD_STRESS_DETECTOR,
    CHILD_STRESS_DETECTION,
    NEW_CHILD_STRESS_DETECTOR
}

internal data class SafArtifactRootCandidateEvidence(
    val exists: Boolean,
    val hasArtifacts: Boolean = false,
    val hasLegacyModel: Boolean = false,
    val hasMarker: Boolean = false
) {
    val score: Int
        get() = if (!exists) {
            -1
        } else {
            (
                (if (hasMarker) 4 else 0) +
                    (if (hasArtifacts) 2 else 0) +
                    (if (hasLegacyModel) 1 else 0)
                )
        }
}

internal data class SafArtifactRootEvidence(
    val selectedDisplayName: String?,
    val selected: SafArtifactRootCandidateEvidence,
    val stressDetectorChild: SafArtifactRootCandidateEvidence,
    val stressDetectionChild: SafArtifactRootCandidateEvidence
)

internal data class SafArtifactRootDecision(val layout: SafArtifactRootLayout, val relativeRootPath: String)

/** Selects an existing artifact root before allowing creation of a new one. */
internal object SafArtifactRootResolver {
    fun resolve(evidence: SafArtifactRootEvidence): SafArtifactRootDecision {
        val existing = listOf(
            SafArtifactRootLayout.SELECTED_TREE to evidence.selected,
            SafArtifactRootLayout.CHILD_STRESS_DETECTOR to evidence.stressDetectorChild,
            SafArtifactRootLayout.CHILD_STRESS_DETECTION to evidence.stressDetectionChild
        ).filter { (_, candidate) -> candidate.score > 0 }
            .maxByOrNull { (_, candidate) -> candidate.score }

        if (existing != null) return decision(existing.first)
        if (evidence.selectedDisplayName.equals("StressDetector", ignoreCase = true) ||
            evidence.selectedDisplayName.equals("StressDetection", ignoreCase = true)
        ) {
            return decision(SafArtifactRootLayout.SELECTED_TREE)
        }
        return decision(SafArtifactRootLayout.NEW_CHILD_STRESS_DETECTOR)
    }

    private fun decision(layout: SafArtifactRootLayout): SafArtifactRootDecision = SafArtifactRootDecision(
        layout = layout,
        relativeRootPath = when (layout) {
            SafArtifactRootLayout.SELECTED_TREE -> "."
            SafArtifactRootLayout.CHILD_STRESS_DETECTOR,
            SafArtifactRootLayout.NEW_CHILD_STRESS_DETECTOR -> "StressDetector"
            SafArtifactRootLayout.CHILD_STRESS_DETECTION -> "StressDetection"
        }
    )
}

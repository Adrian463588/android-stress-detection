package com.stressdetector.mobile.domain.history

import com.stressdetector.shared.StressLabel

/** Prediction evidence and transport diagnostics intentionally remain independent. */
enum class PredictionStatus {
    VALID,
    INVALID
}

data class TransportWarning(val reason: String)

data class SessionOutcome(
    val predictionStatus: PredictionStatus,
    val label: StressLabel?,
    val transportWarning: TransportWarning?,
    val rejectionReason: String?
)

object SessionOutcomeResolver {
    fun resolve(finalLabel: String?, errorReason: String?, reportStatus: String?, transportIncomplete: Boolean = false): SessionOutcome {
        val transportWarning = if (transportIncomplete) {
            TransportWarning("transport_incomplete")
        } else {
            null
        }
        val label = StressLabel.canonical(finalLabel.orEmpty()).let { raw ->
            StressLabel.entries.firstOrNull { it.value == raw && it != StressLabel.INVALID }
        }
        if (label != null) {
            return SessionOutcome(
                predictionStatus = PredictionStatus.VALID,
                label = label,
                transportWarning = transportWarning,
                rejectionReason = null
            )
        }

        val rejectionReason = listOfNotNull(errorReason, reportStatus)
            .map(String::trim)
            .firstOrNull(String::isNotBlank)

        return SessionOutcome(
            predictionStatus = PredictionStatus.INVALID,
            label = null,
            transportWarning = transportWarning,
            rejectionReason = rejectionReason
        )
    }
}

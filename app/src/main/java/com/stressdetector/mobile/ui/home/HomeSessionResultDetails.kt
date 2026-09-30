package com.stressdetector.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.ui.StatusDot
import com.stressdetector.shared.StressLabel

private const val MIN_PERCENT_VALUE = 0f
private const val MAX_PERCENT_VALUE = 1f
private const val PERCENT_SCALE = 100f

@Composable
internal fun SessionResultSummary(green: HomePredictionUiState, ir: HomePredictionUiState, disagreement: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Hasil sesi",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Green dan IR ditampilkan terpisah. Hasil ini bukan diagnosis.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChannelResultRow(green)
        ChannelResultRow(ir)
        if (disagreement) {
            Text(
                text = "Hasil kanal berbeda dan perlu ditinjau.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun ChannelResultRow(state: HomePredictionUiState) {
    val availability = state.availability
    val accent = resultAccent(availability)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Kanal ${state.channelLabel}, ${availabilityLabel(state)}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusDot(color = accent, size = 8.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = state.channelLabel,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = availabilityLabel(state),
                style = MaterialTheme.typography.bodySmall,
                color = accent
            )
        }
        if (availability is PredictionAvailability.Ready) {
            Text(
                text = "${availability.prediction.quality.qualityScore.asPercent()} kualitas",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun resultAccent(availability: PredictionAvailability) = when (availability) {
    is PredictionAvailability.Ready -> if (availability.prediction.label == StressLabel.STRESS.value) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    is PredictionAvailability.WarmingUp -> MaterialTheme.colorScheme.tertiary
    is PredictionAvailability.Rejected,
    is PredictionAvailability.Unavailable,
    is PredictionAvailability.InferenceError -> MaterialTheme.colorScheme.error
}

private fun availabilityLabel(state: HomePredictionUiState): String = when (val availability = state.availability) {
    is PredictionAvailability.Ready -> StressLabel.displayName(availability.prediction.label)
    is PredictionAvailability.WarmingUp -> "Mengumpulkan data"
    is PredictionAvailability.Rejected -> "Sinyal tidak cukup"
    is PredictionAvailability.Unavailable -> "Model belum siap"
    is PredictionAvailability.InferenceError -> "Analisis gagal"
}

internal fun Float.asPercent(): String = "${(coerceIn(MIN_PERCENT_VALUE, MAX_PERCENT_VALUE) * PERCENT_SCALE).toInt()}%"

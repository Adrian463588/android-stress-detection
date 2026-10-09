package com.stressdetector.mobile.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.stressdetector.mobile.ui.MetricRow
import com.stressdetector.shared.StressPrediction

@Composable
internal fun ModelProvenanceDetails(prediction: StressPrediction) {
    MetricRow("Ambang kanal", prediction.decisionPolicy.probabilityThreshold.asPercent())
    Text(
        text = "Kalibrasi: ${prediction.decisionPolicy.calibrationVersion}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
    prediction.rawStressProbability?.let { raw ->
        MetricRow("Probabilitas ONNX mentah", raw.asPercent())
    }
    prediction.modelVersion?.let { version ->
        Text(
            text = "Model: $version",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

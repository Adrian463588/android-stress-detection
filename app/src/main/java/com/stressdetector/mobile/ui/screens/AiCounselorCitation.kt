package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier

private const val MAX_CITATION_PREVIEW_CHARS = 420

@Composable
internal fun citationEvidence(
    citation: com.stressdetector.mobile.domain.engine.KnowledgeCitation,
    webCitationNumber: Int,
    onOpenCitation: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isWeb = citation.sourceType == com.stressdetector.mobile.domain.engine.CitationSource.WEB
    val sourceLabel = citationSourceLabel(citation)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val sourceName = citation.sourceFileName ?: citation.sourceTitle
        Text(
            text = if (isWeb) {
                "[W$webCitationNumber] $sourceLabel · ${citation.sourceTitle}"
            } else {
                "• $sourceLabel · $sourceName"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().semantics {
                stateDescription = if (expanded) "Sumber ditampilkan" else "Sumber disembunyikan"
            }
        ) { Text(if (expanded) "Sembunyikan sumber" else "Lihat sumber") }
        if (expanded) {
            citation.sourcePage?.let { page ->
                Text(
                    "Halaman $page",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            citation.sourceUrl?.let { url ->
                TextButton(onClick = { onOpenCitation(url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(url, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            }
            CitationDetails(citation)
        }
    }
}

private fun citationSourceLabel(citation: com.stressdetector.mobile.domain.engine.KnowledgeCitation): String = when (
    citation.sourceTier
) {
    EvidenceSourceTier.APP_RAG -> "Dokumen lokal"
    EvidenceSourceTier.BUNDLED_LOCAL -> "Dataset bawaan"
    EvidenceSourceTier.WEB -> "Web"
    EvidenceSourceTier.NONE -> "Sumber belum terverifikasi"
}

@Composable
private fun CitationDetails(citation: com.stressdetector.mobile.domain.engine.KnowledgeCitation) {
    if (citation.text.isNotBlank()) {
        Text(
            text = "\"${citation.text.take(MAX_CITATION_PREVIEW_CHARS)}\"",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4
        )
    }
    Text(
        text = "Skor relevansi: ${(citation.relevanceScore.coerceIn(0f, 1f) * 100).toInt()}%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    citation.retrievedAtMillis?.let { retrievedAt ->
        Text(
            text = "Diambil: ${formatSessionDate(retrievedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.R
import com.stressdetector.mobile.domain.engine.CitationSource
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.ui.AppSpacing

private const val MAX_CITATION_PREVIEW_CHARS = 420

@Composable
internal fun CitationList(citations: List<KnowledgeCitation>, onOpenCitation: (String) -> Unit) {
    if (citations.isEmpty()) return
    var expanded by remember(citations) { mutableStateOf(false) }
    val disclosureState = pluralStringResource(
        if (expanded) R.plurals.counselor_sources_expanded else R.plurals.counselor_sources_collapsed,
        citations.size,
        citations.size
    )
    Column(modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.Compact)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                stateDescription = disclosureState
            }
        ) {
            Text(
                stringResource(if (expanded) R.string.counselor_sources_hide else R.string.counselor_sources_show),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(AppSpacing.Compact))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                citations.forEachIndexed { index, citation ->
                    if (index > 0) Divider()
                    CitationEvidence(
                        citation = citation,
                        webCitationNumber = citations.take(index + 1).count { it.sourceType == CitationSource.WEB },
                        onOpenCitation = onOpenCitation
                    )
                }
            }
        }
    }
}

@Composable
private fun CitationEvidence(citation: KnowledgeCitation, webCitationNumber: Int, onOpenCitation: (String) -> Unit) {
    val isWeb = citation.sourceType == CitationSource.WEB
    val sourceLabel = citationSourceLabel(citation)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
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
        citation.sourcePage?.let { page ->
            Text(
                "Halaman $page",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        citation.sourceUrl?.let { url ->
            TextButton(onClick = { onOpenCitation(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(url, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
        }
        CitationDetails(citation)
    }
}

private fun citationSourceLabel(citation: KnowledgeCitation): String = when (
    citation.sourceTier
) {
    EvidenceSourceTier.APP_RAG -> "Dokumen lokal"
    EvidenceSourceTier.BUNDLED_LOCAL -> "Dataset bawaan"
    EvidenceSourceTier.WEB -> "Web"
    EvidenceSourceTier.NONE -> "Sumber belum terverifikasi"
}

@Composable
private fun CitationDetails(citation: KnowledgeCitation) {
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

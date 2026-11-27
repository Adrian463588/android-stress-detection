package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.assessment.CounselingKnowledgeModule
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.AppSpacing

internal val counselingPillarFilters = listOf(
    "Semua",
    "Etika & Regulasi",
    "Bidang Layanan",
    "Pendekatan Klinis",
    "Metode Layanan",
    "Teknik Komunikasi",
    "Asesmen & Tindak Lanjut",
    "Psikodiagnostik Proyektif"
)

internal data class StructuredMarkdownSection(
    val heading: String,
    val body: String
)

internal fun parseCounselingMarkdownSections(markdown: String): List<StructuredMarkdownSection> {
    val lines = markdown.lines()
    val sections = mutableListOf<StructuredMarkdownSection>()
    var currentHeading = "Ringkasan Materi"
    val buffer = mutableListOf<String>()

    fun flush() {
        val text = buffer.joinToString("\n").trim()
        if (text.isNotBlank()) {
            sections += StructuredMarkdownSection(currentHeading, text)
        }
        buffer.clear()
    }

    lines.forEach { raw ->
        val line = raw.trimEnd()
        if (line.startsWith("## ") || line.startsWith("# ")) {
            flush()
            currentHeading = line.removePrefix("## ").removePrefix("# ").trim()
        } else {
            buffer += line
        }
    }
    flush()
    return sections
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
internal fun CounselingPillarFilterRow(
    selectedCategory: String,
    onSelectCategory: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(counselingPillarFilters) { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onSelectCategory(category) },
                label = { Text(category, maxLines = 1, softWrap = false) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun StructuredCounselingModuleReaderSheet(
    module: CounselingKnowledgeModule,
    content: String,
    explanation: String?,
    busy: Boolean,
    onExplain: () -> Unit,
    onDiscussWithAi: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sections = remember(content) { parseCounselingMarkdownSections(content) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${module.pillar} · ${module.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppPalette.Primary
                    )
                    Text(
                        text = module.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup")
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Provenance Pustaka: BankMateriKonseling / ${module.assetFileName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Topik kunci: ${module.keyTopics.joinToString(" · ")}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Divider()

            sections.forEach { section ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = section.heading,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppPalette.Primary
                        )
                        Text(
                            text = section.body,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Terapkan dalam Konseling dengan AI+",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Diskusikan teknik, batasan, atau langkah penerapan modul ini secara offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onExplain,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(AppSpacing.Compact))
                        Text("Ringkasan Terpandu AI Lokal")
                    }
                    if (onDiscussWithAi != null) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onDiscussWithAi(
                                    "Mari bahas penerapan modul ${module.title} (${module.pillar}) pada studi kasus konseling."
                                )
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(AppSpacing.Compact))
                            Text("Buka Diskusi di AI Counselor")
                        }
                    }

                    if (explanation != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(AppSpacing.Medium)) {
                                Text(
                                    text = "Rekomendasi Klinis AI Lokal",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AppPalette.Primary
                                )
                                Spacer(Modifier.height(AppSpacing.Micro))
                                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text("Tutup Pembaca Modul")
            }
            Spacer(Modifier.height(AppSpacing.Small))
        }
    }
}

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.AssessmentRecommendationBubblePayload
import com.stressdetector.mobile.domain.engine.AssessmentResultBubblePayload
import com.stressdetector.mobile.domain.engine.GoalSuggestionPayload
import com.stressdetector.mobile.domain.engine.JournalBubblePayload
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.AppSpacing

@Composable
@Suppress("FunctionNaming")
internal fun AdaptiveJournalPromptCard(
    payload: JournalBubblePayload,
    onStartJournal: (JournalBubblePayload) -> Unit
) {
    Spacer(Modifier.height(AppSpacing.Small))
    Card(
        colors = CardDefaults.cardColors(containerColor = AppPalette.SurfaceElevated),
        border = BorderStroke(1.dp, AppPalette.Primary.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                Icon(
                    imageVector = Icons.Default.EditNote,
                    contentDescription = null,
                    tint = AppPalette.Primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = payload.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppPalette.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = payload.estimatedMinutes,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppPalette.TextSecondary
                )
            }
            Text(
                text = payload.description,
                style = MaterialTheme.typography.bodySmall,
                color = AppPalette.TextSecondary
            )
            if (payload.completedEntryId == null) {
                Button(
                    onClick = { onStartJournal(payload) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Mulai Journaling")
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AppPalette.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Refleksi harian tersimpan",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppPalette.Primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun AdaptiveGoalSuggestionCard(
    payload: GoalSuggestionPayload,
    onAcceptGoal: (GoalSuggestionPayload) -> Unit
) {
    Spacer(Modifier.height(AppSpacing.Small))
    Card(
        colors = CardDefaults.cardColors(containerColor = AppPalette.SurfaceElevated),
        border = BorderStroke(1.dp, AppPalette.Secondary.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = null,
                    tint = AppPalette.Secondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = payload.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppPalette.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "${payload.category} · ${payload.cadence} (${payload.targetCount}x)",
                style = MaterialTheme.typography.labelSmall,
                color = AppPalette.TextSecondary
            )
            if (payload.rationale.isNotBlank()) {
                Text(
                    text = payload.rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppPalette.TextSecondary
                )
            }
            if (!payload.accepted) {
                OutlinedButton(
                    onClick = { onAcceptGoal(payload) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Tambahkan ke Rencana Kebiasaan")
                }
            } else {
                Text(
                    text = "Aktif di target kebiasaan harian",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppPalette.Primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun AdaptiveAssessmentRecommendationCard(
    payload: AssessmentRecommendationBubblePayload,
    onStartAssessment: (AssessmentRecommendationBubblePayload) -> Unit,
    onDefer: (AssessmentRecommendationBubblePayload) -> Unit,
    onDecline: (AssessmentRecommendationBubblePayload) -> Unit
) {
    Spacer(Modifier.height(AppSpacing.Small))
    Card(
        colors = CardDefaults.cardColors(containerColor = AppPalette.SurfaceElevated),
        border = BorderStroke(1.dp, AppPalette.Primary.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
        ) {
            Text(
                text = payload.instrumentTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AppPalette.TextPrimary
            )
            Text(
                text = payload.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = AppPalette.TextSecondary
            )
            Surface(
                color = AppPalette.Background.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Alasan: ${payload.rationale}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppPalette.TextPrimary
                    )
                    Text(
                        text = "Kelayakan: ${payload.eligibilityNote}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppPalette.TextSecondary
                    )
                    Text(
                        text = "Estimasi durasi: ${payload.estimatedDuration}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppPalette.TextSecondary
                    )
                }
            }

            when (payload.status) {
                "DEFERRED" -> Text(
                    text = "Ditunda sementara. Anda dapat membukanya kapan saja melalui Bank Psikotes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppPalette.TextSecondary
                )
                "DECLINED" -> Text(
                    text = "Dilewati atas pilihan Anda.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppPalette.TextSecondary
                )
                else -> {
                    Button(
                        onClick = { onStartAssessment(payload) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(if (payload.requiresDedicatedScreen) "Buka Layar Asesmen" else "Mulai Asesmen")
                        Spacer(Modifier.width(AppSpacing.Compact))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
                    ) {
                        TextButton(
                            onClick = { onDefer(payload) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Nanti saja")
                        }
                        TextButton(
                            onClick = { onDecline(payload) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Tidak perlu")
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun AdaptiveAssessmentResultCard(
    payload: AssessmentResultBubblePayload,
    onOpenDetail: (String) -> Unit
) {
    Spacer(Modifier.height(AppSpacing.Small))
    Card(
        colors = CardDefaults.cardColors(containerColor = AppPalette.SurfaceElevated),
        border = BorderStroke(1.dp, AppPalette.Primary.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = AppPalette.Primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "${payload.instrumentTitle} · Hasil Tersimpan",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppPalette.TextPrimary
                )
            }
            Text(
                text = "Skor deterministik: ${payload.primaryScoreSummary}",
                style = MaterialTheme.typography.bodySmall,
                color = AppPalette.TextPrimary
            )
            Text(
                text = "${payload.validityLabel} · ${payload.reviewLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = AppPalette.TextSecondary
            )
            OutlinedButton(
                onClick = { onOpenDetail(payload.instrumentId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("Lihat Grafik & Rincian Skor")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "MagicNumber")
internal fun QuickJournalBottomSheet(
    prompt: JournalBubblePayload,
    onSave: (moodScore: Int, moodLabel: String, reflectionText: String, allowAiContext: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var moodScore by rememberSaveable { mutableIntStateOf(3) }
    var reflectionText by rememberSaveable { mutableStateOf("") }
    var allowAiContext by rememberSaveable { mutableStateOf(true) }
    val moodLabels = mapOf(
        1 to "Sangat Berat",
        2 to "Kurang Nyaman",
        3 to "Netral",
        4 to "Cukup Tenang",
        5 to "Baik"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Text(
                text = prompt.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = prompt.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Suasana hati saat ini (1–5)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                moodLabels.forEach { (score, label) ->
                    FilterChip(
                        selected = moodScore == score,
                        onClick = { moodScore = score },
                        label = { Text("$score · $label") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            OutlinedTextField(
                value = reflectionText,
                onValueChange = { reflectionText = it },
                label = { Text("Catatan refleksi singkat") },
                placeholder = { Text("Apa hal utama yang memengaruhi hari Anda?") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Izinkan sebagai konteks AI Counselor",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = allowAiContext,
                    onCheckedChange = { allowAiContext = it }
                )
            }
            Button(
                onClick = {
                    val clean = reflectionText.trim().ifBlank { "Check-in suasana hati: ${moodLabels[moodScore]}" }
                    onSave(moodScore, moodLabels[moodScore] ?: "Netral", clean, allowAiContext)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Text("Simpan Refleksi Harian")
            }
            Spacer(Modifier.height(AppSpacing.Medium))
        }
    }
}

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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.assessment.AssessmentDiscussionRequest
import com.stressdetector.mobile.domain.assessment.AssessmentInterpretationProvider
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentValidityStatus
import com.stressdetector.mobile.domain.assessment.AssessmentVisualizationMapper
import com.stressdetector.mobile.domain.assessment.ExaminerReviewStatus
import com.stressdetector.mobile.domain.assessment.InstrumentImplementationStatus
import com.stressdetector.mobile.domain.assessment.toCounselorDiscussionPrompt
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.components.AssessmentVisualizationCard
import com.stressdetector.mobile.ui.viewmodels.AssessmentUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
internal fun AssessmentResultPanel(
    result: AssessmentResult,
    state: AssessmentUiState,
    onExplain: () -> Unit,
    onDelete: () -> Unit,
    onSubmitExaminerReview: (ExaminerReviewStatus, String, String, Map<String, String>, AssessmentValidityStatus?) -> Unit,
    onUpdateConsentFlags: (Boolean, Boolean) -> Unit,
    onDiscussWithAi: (String) -> Unit = {},
    onDiscussResult: (AssessmentDiscussionRequest) -> Unit = { req -> onDiscussWithAi(req.prompt) }
) {
    var confirmDelete by remember(result.resultId) { mutableStateOf(false) }
    var showTable by rememberSaveable(result.resultId) { mutableStateOf(false) }
    var showReviewSheet by remember(result.resultId) { mutableStateOf(false) }

    val vizModel = remember(result) { AssessmentVisualizationMapper.map(result) }
    val baselineReport = remember(result) { AssessmentInterpretationProvider.build(result) }
    val aiEligible = result.isAiContextEligible()
    val ineligibilityReason = result.aiIneligibilityReason()
    val formattedTime = remember(result.createdAtMillis) {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(result.createdAtMillis))
    }

    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AppPalette.Primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(AppSpacing.Compact))
                    Text(
                        text = "Hasil Asesmen Tersimpan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            val inputLabel = if (result.fromMarkedSheet) {
                "Rekap Lembar Jawaban"
            } else {
                "${result.answered}/${result.itemCount} unit tercatat"
            }
            Text(
                text = "${result.version} · $inputLabel · $formattedTime · ID ${result.resultId.take(8)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                StatusTag("Skoring: ${result.resolvedScoringStatus().displayLabel}", AppPalette.Primary)
                StatusTag(
                    "Validitas: ${result.resolvedValidityStatus().displayLabel}",
                    when (result.resolvedValidityStatus()) {
                        AssessmentValidityStatus.VALID -> AppPalette.Primary
                        AssessmentValidityStatus.INVALID -> AppPalette.Error
                        else -> AppPalette.Warning
                    }
                )
                StatusTag(
                    "Review: ${result.resolvedReviewStatus().displayLabel}",
                    if (result.resolvedReviewStatus() == ExaminerReviewStatus.APPROVED) AppPalette.Primary else AppPalette.Secondary
                )
            }

            if (result.validityWarnings.isNotEmpty() || result.isLegacyUnverified()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppPalette.Warning.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AppPalette.Warning.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AppPalette.Warning, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Pemeriksaan Validitas & Keselamatan",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = AppPalette.Warning
                            )
                        }
                        if (result.isLegacyUnverified()) {
                            Text(
                                "• Hasil lama sebelum pembaruan skema versi; tidak diteruskan ke AI+ sebagai skor tervalidasi.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        result.validityWarnings.forEach { warning ->
                            Text("• $warning", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Divider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                FilterChip(
                    selected = !showTable,
                    onClick = { showTable = false },
                    label = { Text("Grafik") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = showTable,
                    onClick = { showTable = true },
                    label = { Text("Tabel Skor") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }

            AssessmentVisualizationCard(model = vizModel, showTable = showTable)

            result.multimodalSummary?.takeIf(String::isNotBlank)?.let { multimodal ->
                Spacer(Modifier.height(AppSpacing.Micro))
                Text("Analisis Multimodal & Telemetri Proyektif", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = multimodal,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(AppSpacing.Medium)
                    )
                }
            }

            Spacer(Modifier.height(AppSpacing.Micro))
            Text("Pemetaan Konstruk & Interpretasi Dasar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = baselineReport.constructMeaning,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppPalette.Primary
                    )
                    Text(baselineReport.resultInterpretation, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Pemetaan profil: ${baselineReport.profileMapping}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Kecenderungan relatif: ${baselineReport.relativeTendencies}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Perhitungan: ${baselineReport.scoreDescription}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Batasan & norma: ${baselineReport.limitations}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Referensi: ${baselineReport.references}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedCard(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AppPalette.Secondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Review Psikolog & Persetujuan Data", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (!result.reviewNotes.isNullOrBlank()) {
                        Text(
                            text = "Catatan (${result.reviewerId ?: "Pemeriksa"}): ${result.reviewNotes}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            text = "Status: ${result.resolvedReviewStatus().displayLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showReviewSheet = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (result.resolvedScoringStatus() == InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED) {
                                "Isi Coding & Verifikasi Psikolog"
                            } else {
                                "Catatan Psikolog & Pengaturan Izin AI"
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Micro))
            Button(
                onClick = {
                    onDiscussResult(
                        AssessmentDiscussionRequest(
                            resultId = result.resultId,
                            instrumentId = result.instrumentId,
                            participantId = result.participantId,
                            prompt = result.toCounselorDiscussionPrompt()
                        )
                    )
                },
                enabled = aiEligible && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (aiEligible) Icons.AutoMirrored.Outlined.Chat else Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(AppSpacing.Compact))
                Text("Interpretasikan dengan AI+", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            if (ineligibilityReason != null) {
                Text(
                    text = ineligibilityReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppPalette.Warning
                )
            }

            OutlinedButton(
                onClick = onExplain,
                enabled = aiEligible && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(AppSpacing.Compact))
                    Text("Menyusun ringkasan AI...")
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(AppSpacing.Compact))
                    Text(if (state.resultExplanation == null) "Ringkasan Pendukung AI Lokal" else "Perbarui Ringkasan AI")
                }
            }

            state.error?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(AppSpacing.Small)
                    )
                }
            }

            state.resultExplanation?.let { explanation ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Ulasan Pendukung AI Lokal (Bukan Diagnosis)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(explanation, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Text(
                text = "Purwarupa penelitian psikologi — hasil eksploratif dan AI+ bersifat pendukung tinjauan psikolog, bukan diagnosis klinis tunggal.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(
                onClick = { confirmDelete = true },
                enabled = !state.busy,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(AppSpacing.Micro))
                Text("Hapus Hasil Tersimpan", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showReviewSheet) {
        ExaminerReviewBottomSheet(
            result = result,
            onDismiss = { showReviewSheet = false },
            onSave = { reviewStatus, reviewerId, notes, codes, validityOverride, aiAllowed, researchAllowed ->
                onSubmitExaminerReview(reviewStatus, reviewerId, notes, codes, validityOverride)
                onUpdateConsentFlags(aiAllowed, researchAllowed)
                showReviewSheet = false
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus hasil asesmen?") },
            text = { Text("Skor dan interpretasi ini akan dihapus secara permanen dari perangkat.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Hapus hasil", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun StatusTag(text: String, accent: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
private fun ExaminerReviewBottomSheet(
    result: AssessmentResult,
    onDismiss: () -> Unit,
    onSave: (
        ExaminerReviewStatus,
        String,
        String,
        Map<String, String>,
        AssessmentValidityStatus?,
        Boolean,
        Boolean
    ) -> Unit
) {
    var reviewerId by remember { mutableStateOf(result.reviewerId.orEmpty()) }
    var reviewNotes by remember { mutableStateOf(result.reviewNotes.orEmpty()) }
    var selectedReview by remember { mutableStateOf(result.resolvedReviewStatus()) }
    var selectedValidity by remember { mutableStateOf(result.resolvedValidityStatus()) }
    var codeKey by remember { mutableStateOf("") }
    var codeValue by remember { mutableStateOf("") }
    var aiAllowed by remember { mutableStateOf(result.aiContextAllowed) }
    var researchAllowed by remember { mutableStateOf(result.researchExportAllowed) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Text(
                text = "Review Psikolog & Persetujuan (${result.title})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = reviewerId,
                onValueChange = { reviewerId = it },
                label = { Text("ID / Inisial Pemeriksa") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = reviewNotes,
                onValueChange = { reviewNotes = it },
                label = { Text("Catatan Klinis / Verifikasi") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Keputusan Review", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ExaminerReviewStatus.PENDING,
                    ExaminerReviewStatus.APPROVED,
                    ExaminerReviewStatus.NEEDS_REVISION
                ).forEach { statusOption ->
                    FilterChip(
                        selected = selectedReview == statusOption,
                        onClick = { selectedReview = statusOption },
                        label = { Text(statusOption.displayLabel) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            Text("Status Validitas Protokol", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AssessmentValidityStatus.VALID,
                    AssessmentValidityStatus.QUESTIONABLE,
                    AssessmentValidityStatus.INVALID
                ).forEach { validityOption ->
                    FilterChip(
                        selected = selectedValidity == validityOption,
                        onClick = { selectedValidity = validityOption },
                        label = { Text(validityOption.displayLabel) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            Text("Tambah Kode Pemeriksa (Opsional)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = codeKey,
                    onValueChange = { codeKey = it },
                    label = { Text("Variabel") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = codeValue,
                    onValueChange = { codeValue = it },
                    label = { Text("Kode") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Izinkan untuk konteks AI Counselor", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = aiAllowed, onCheckedChange = { aiAllowed = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Izinkan ekspor penelitian teranonim", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = researchAllowed, onCheckedChange = { researchAllowed = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Batal")
                }
                Button(
                    onClick = {
                        val newCodes = if (codeKey.isNotBlank() && codeValue.isNotBlank()) {
                            mapOf(codeKey.trim() to codeValue.trim())
                        } else {
                            emptyMap()
                        }
                        onSave(selectedReview, reviewerId, reviewNotes, newCodes, selectedValidity, aiAllowed, researchAllowed)
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Simpan Review")
                }
            }
        }
    }
}

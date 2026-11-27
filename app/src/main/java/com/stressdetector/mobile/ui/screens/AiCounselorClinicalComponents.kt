package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.CounselingInteractionMode
import com.stressdetector.mobile.domain.engine.PlanReviewStatus
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.viewmodels.UiChatMessage

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageClinicalActionRow(
    message: UiChatMessage,
    onContinueResponse: (String) -> Unit,
    onReviewPlan: (String, PlanReviewStatus) -> Unit,
    onRequestPlanRevision: (String) -> Unit
) {
    val showPlanReview = message.interactionMode == CounselingInteractionMode.COUNSELOR_PLANNING ||
        message.planReviewStatus != null

    if (!message.isTruncated && !showPlanReview) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.Compact),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
    ) {
        message.planReviewStatus?.let { status ->
            val noteSuffix = message.planReviewNote?.let { " · $it" }.orEmpty()
            Text(
                text = "Status Rencana: ${status.label}$noteSuffix",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.secondary
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
        ) {
            if (message.isTruncated) {
                OutlinedButton(
                    onClick = { onContinueResponse(message.id) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(AppSpacing.Micro))
                    Text("Lanjutkan jawaban")
                }
            }
            if (showPlanReview) {
                TextButton(
                    onClick = { onReviewPlan(message.id, PlanReviewStatus.ACCEPTED) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Setujui") }
                TextButton(
                    onClick = { onRequestPlanRevision(message.id) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Revisi") }
                TextButton(
                    onClick = { onReviewPlan(message.id, PlanReviewStatus.REJECTED) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Tolak") }
            }
        }
    }
}

@Composable
internal fun PlanRevisionDialog(messageId: String, onSubmit: (String, String) -> Unit, onDismiss: () -> Unit) {
    var note by rememberSaveable(messageId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catatan Revisi Rencana", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                Text(
                    "Tuliskan bagian rencana konseling yang perlu disesuaikan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan revisi") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(messageId, note) },
                enabled = note.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Simpan Revisi") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Batal") }
        }
    )
}

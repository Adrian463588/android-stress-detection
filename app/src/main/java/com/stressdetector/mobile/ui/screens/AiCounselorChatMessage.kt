package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.assessment.AssessmentRecommendation
import com.stressdetector.mobile.domain.engine.CrisisSupportAction
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.RagMode
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.components.CounselorMessageText
import com.stressdetector.mobile.ui.viewmodels.UiChatMessage

private const val CHAT_BUBBLE_WIDTH_FRACTION = 0.85f

internal data class ChatMessageActions(
    val isSpeaking: Boolean,
    val isVoiceStopping: Boolean,
    val onSpeak: (String, String) -> Unit,
    val onStopSpeaking: () -> Unit,
    val onOpenCitation: (String) -> Unit,
    val onOpenCrisisSupport: (CrisisSupportAction) -> Unit,
    val onOpenAssessment: (String) -> Unit = {}
)

@Composable
internal fun ChatMessageBubble(message: UiChatMessage, actions: ChatMessageActions) {
    val isUser = message.sender == "user"
    val isCrisis = message.sender == "crisis_system"
    val background = when {
        isCrisis -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = background,
            shape = messageBubbleShape(isUser),
            modifier = Modifier.fillMaxWidth(CHAT_BUBBLE_WIDTH_FRACTION)
        ) {
            MessageBubbleContent(message, isUser, isCrisis, actions)
        }
    }
}

@Composable
private fun MessageBubbleContent(message: UiChatMessage, isUser: Boolean, isCrisis: Boolean, actions: ChatMessageActions) {
    Column(modifier = Modifier.padding(14.dp)) {
        MessageSenderLabel(message, isUser, isCrisis)
        MessageBody(message, isUser)
        if (isCrisis && message.crisisSupportActions.isNotEmpty()) {
            CrisisSupportButtons(message.crisisSupportActions, actions.onOpenCrisisSupport)
        }
        if (message.citations.isNotEmpty()) {
            CitationList(message.citations, actions.onOpenCitation)
        }
        if (!isUser && !isCrisis && message.assessmentRecommendations.isNotEmpty()) {
            AssessmentRecommendationCards(message.assessmentRecommendations, actions.onOpenAssessment)
        }
        if (!isUser && !isCrisis && message.content.isNotBlank()) VoiceReplyButton(message, actions)
    }
}

@Composable
private fun MessageSenderLabel(message: UiChatMessage, isUser: Boolean, isCrisis: Boolean) {
    Text(
        text = messageSenderLabel(message, isUser, isCrisis),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = if (isCrisis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    )
}

private fun messageSenderLabel(message: UiChatMessage, isUser: Boolean, isCrisis: Boolean): String = when {
    isUser -> "Anda"
    isCrisis -> "Sistem Keselamatan"
    message.assessmentRecommendations.isNotEmpty() -> "Rekomendasi asesmen"
    message.retrievalMode == RagMode.BUNDLED_GROUNDED ||
        message.citations.any { it.sourceTier == EvidenceSourceTier.BUNDLED_LOCAL } ->
        "AI Counselor · Dataset bawaan"
    message.retrievalMode == RagMode.WEB_GROUNDED ||
        message.citations.any { it.sourceTier == EvidenceSourceTier.WEB } ->
        "AI Counselor · Web"
    message.retrievalMode == RagMode.GROUNDED ||
        message.citations.any { it.sourceTier == EvidenceSourceTier.APP_RAG } ->
        "AI Counselor · Dokumen lokal"
    else -> "AI Counselor"
}

@Composable
private fun MessageBody(message: UiChatMessage, isUser: Boolean) {
    if (message.content.isBlank() && !isUser) {
        typingIndicator()
    } else {
        Spacer(modifier = Modifier.height(AppSpacing.Micro))
        CounselorMessageText(text = message.content)
    }
}

@Composable
private fun CrisisSupportButtons(actions: List<CrisisSupportAction>, onOpen: (CrisisSupportAction) -> Unit) {
    Spacer(modifier = Modifier.height(10.dp))
    actions.forEach { action ->
        Button(onClick = { onOpen(action) }, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
    }
}

@Composable
private fun VoiceReplyButton(message: UiChatMessage, actions: ChatMessageActions) {
    val onVoiceClick = if (actions.isSpeaking) {
        actions.onStopSpeaking
    } else {
        { actions.onSpeak(message.id, message.content) }
    }
    IconButton(onClick = onVoiceClick, enabled = !actions.isVoiceStopping) {
        Icon(
            if (actions.isSpeaking) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (actions.isSpeaking) "Hentikan suara" else "Bacakan jawaban"
        )
    }
}

private fun messageBubbleShape(isUser: Boolean): RoundedCornerShape = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomStart = if (isUser) 16.dp else 4.dp,
    bottomEnd = if (isUser) 4.dp else 16.dp
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
private fun AssessmentRecommendationCards(recommendations: List<AssessmentRecommendation>, onOpenAssessment: (String) -> Unit) {
    Spacer(modifier = Modifier.height(AppSpacing.Small))
    Text(
        text = "Pilih instrumen untuk membuka halaman asesmen:",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        recommendations.forEach { recommendation ->
            Card(
                onClick = { onOpenAssessment(recommendation.instrumentId) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = recommendation.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Buka Asesmen · Rekap & Skoring",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Buka ${recommendation.title}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

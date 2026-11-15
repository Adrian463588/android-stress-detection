@file:Suppress("TooManyFunctions")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput


import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stressdetector.mobile.domain.assessment.AssessmentForm
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.AssessmentProtocol
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentScoreEntry
import com.stressdetector.mobile.domain.assessment.CounselingKnowledgeModule
import com.stressdetector.mobile.domain.assessment.ScoreOperation
import com.stressdetector.mobile.domain.assessment.toCounselorDiscussionPrompt
import com.stressdetector.mobile.domain.assessment.toGuideDiscussionPrompt
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.viewmodels.AssessmentUiState
import com.stressdetector.mobile.ui.viewmodels.AssessmentViewModel

private val ASSESSMENT_CATEGORIES = listOf(
    "Semua",
    "Kognitif & IQ",
    "Kepribadian",
    "Minat Karier",
    "Skrining & Klinis",
    "Refleksi"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
internal fun AssessmentScreen(
    isCatalog: Boolean,
    viewModel: AssessmentViewModel,
    onOpenInstrument: (String) -> Unit,
    onDiscussWithAi: (String) -> Unit = {},
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isCatalog) "Bank Asesmen & Materi Konseling" else (state.instrument?.title ?: "Detail Asesmen"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (!isCatalog && state.instrument != null) {
                            Text(
                                text = state.instrument?.category.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = AppSpacing.Screen),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            if (state.busy) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            state.error?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(AppSpacing.Medium)) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                            if (state.instruments.isEmpty()) {
                                TextButton(onClick = viewModel::reload) {
                                    Text("Muat ulang")
                                }
                            }
                        }
                    }
                }
            }
            if (state.loading) return@LazyColumn

            if (isCatalog) {
                catalogContent(state, viewModel, onOpenInstrument)
            } else if (state.instrument == null) {
                if (state.error == null) {
                    item { Text("Instrumen tidak ditemukan di katalog.") }
                }
            } else {
                assessmentDetailItems(state, viewModel, onDiscussWithAi)
            }
        }
    }

    state.selectedModule?.let { module ->
        CounselingModuleReaderSheet(
            module = module,
            content = state.selectedModuleContent.orEmpty(),
            explanation = state.moduleExplanation,
            busy = state.busy,
            onExplain = { viewModel.explainModule(module) },
            onDismiss = { viewModel.openModule(null) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
private fun LazyListScope.catalogContent(state: AssessmentUiState, viewModel: AssessmentViewModel, onOpenInstrument: (String) -> Unit) {
    item {
        TabRow(
            selectedTabIndex = state.selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = state.selectedTab == 0,
                onClick = { viewModel.selectTab(0) },
                icon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                text = { Text("Bank Psikotes (${state.instruments.size})") }
            )
            Tab(
                selected = state.selectedTab == 1,
                onClick = { viewModel.selectTab(1) },
                icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                text = { Text("Materi Konseling (${state.counselingModules.size})") }
            )
        }
    }

    if (state.selectedTab == 0) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = AppSpacing.Micro),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                ASSESSMENT_CATEGORIES.forEach { category ->
                    FilterChip(
                        selected = state.selectedCategory == category,
                        onClick = { viewModel.selectCategory(category) },
                        label = { Text(category) },
                        modifier = Modifier.heightIn(min = 40.dp)
                    )
                }
            }
        }

        val filtered = when (state.selectedCategory) {
            "Semua" -> state.instruments
            "Kognitif & IQ" -> state.instruments.filter { it.category in setOf("Kognitif", "Kompetensi") }
            "Kepribadian" -> state.instruments.filter {
                it.category in setOf("Kepribadian", "Kepribadian kerja", "Sikap kerja")
            }
            "Minat Karier" -> state.instruments.filter { it.category == "Minat" }
            "Skrining & Klinis" -> state.instruments.filter {
                it.category in setOf("Skrining", "Klinis", "Kelelahan")
            }
            "Refleksi" -> state.instruments.filter { it.category in setOf("Refleksi", "Proyektif") }
            else -> state.instruments.filter { it.category.contains(state.selectedCategory, ignoreCase = true) }
        }

        if (filtered.isEmpty()) {
            item {
                Text(
                    text = "Tidak ada instrumen untuk kategori ini.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AppSpacing.Medium)
                )
            }
        } else {
            items(filtered, key = { it.id }) { instrument ->
                InstrumentCatalogCard(
                    instrument = instrument,
                    onClick = { onOpenInstrument(instrument.id) }
                )
            }
        }
    } else {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Medium)) {
                    Text(
                        text = "Bank Pengetahuan & Kurikulum Konseling",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(AppSpacing.Micro))
                    Text(
                        text = "6 pilar intervensi psikologis standar Bimbingan & Konseling " +
                            "(CBT, REBT, Rogers, Gross, Mindfulness, Psikoedukasi). " +
                            "Tersedia untuk studi kasus klinis dan konsultasi AI lokal offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(state.counselingModules, key = { it.id }) { module ->
            CounselingModuleCard(
                module = module,
                onClick = { viewModel.openModule(module) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
private fun InstrumentCatalogCard(instrument: AssessmentInstrument, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    ) {
        Column(Modifier.padding(AppSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = instrument.category,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                val availability = when {
                    instrument.form != null -> "Pengerjaan Langsung"
                    instrument.protocol?.entry != null -> "Kalkulator Rekap"
                    else -> "Panduan Klinis"
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = availability,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(AppSpacing.Compact))
            Text(
                text = instrument.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(AppSpacing.Micro))
            Text(
                text = instrument.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(AppSpacing.Small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val detailInfo = when {
                    instrument.form != null -> "${instrument.form.items.size} butir pertanyaan"
                    instrument.protocol?.entry != null -> "${instrument.protocol.entry.fields.size} subtes rekap"
                    else -> "Norma & interpretasi manual"
                }
                Text(
                    text = detailInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Buka",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(AppSpacing.Micro))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod")
private fun CounselingModuleCard(module: CounselingKnowledgeModule, onClick: () -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = module.pillar,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = module.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(AppSpacing.Compact))
            Text(
                text = module.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(AppSpacing.Micro))
            Text(
                text = module.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(AppSpacing.Small))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Micro),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                module.keyTopics.forEach { topic ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = topic,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Medium))
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(AppSpacing.Compact))
                Text("Baca Modul & Diskusi AI")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "LongParameterList")
private fun CounselingModuleReaderSheet(
    module: CounselingKnowledgeModule,
    content: String,
    explanation: String?,
    busy: Boolean,
    onExplain: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
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
                        color = MaterialTheme.colorScheme.primary
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

            Spacer(Modifier.height(AppSpacing.Small))
            Divider()
            Spacer(Modifier.height(AppSpacing.Small))

            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(AppSpacing.Medium))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Medium)) {
                    Text(
                        text = "Konsultasikan Modul Ini dengan AI",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(AppSpacing.Micro))
                    Text(
                        text = "Diskusikan penerapan modul ini pada kasus klinis Anda menggunakan model bahasa offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AppSpacing.Small))

                    Button(
                        onClick = onExplain,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(AppSpacing.Compact))
                        Text("Minta Rekomendasi Klinis AI")
                    }

                    if (explanation != null) {
                        Spacer(Modifier.height(AppSpacing.Small))
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
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(AppSpacing.Micro))
                                Text(
                                    text = explanation,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Medium))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("Tutup Pembaca Modul")
            }
            Spacer(Modifier.height(AppSpacing.Medium))
        }
    }
}

private fun LazyListScope.assessmentDetailItems(
    state: AssessmentUiState,
    viewModel: AssessmentViewModel,
    onDiscussWithAi: (String) -> Unit
) {
    val entry = state.instrument?.protocol?.entry
    if (state.answering && state.isMarkedSheetEntry && entry != null) {
        markedScoreForm(entry, state, viewModel)
    } else {
        item { AssessmentDetail(state, viewModel, onDiscussWithAi) }
    }
}

@Composable
private fun AssessmentDetail(
    state: AssessmentUiState,
    viewModel: AssessmentViewModel,
    onDiscussWithAi: (String) -> Unit
) {
    val instrument = state.instrument ?: return
    val form = instrument.form
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = AppSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
    ) {
        if (state.answering && !state.isMarkedSheetEntry && form != null) {
            when (instrument.id) {
                "disc" -> DiscQuestion(state, form, viewModel)
                "kuder" -> KuderTriadQuestion(state, form, viewModel)
                "kraepelin" -> KraepelinTestScreen(state, form, viewModel)
                "pauli" -> PauliTestScreen(state, form, viewModel)
                "wartegg" -> WarteggDrawingScreen(state, form, viewModel)
                "rmib" -> RmibRankingScreen(state, form, viewModel)
                "rorschach" -> RorschachTestScreen(state, form, viewModel)
                else -> AssessmentQuestion(state, form, viewModel)
            }
        } else {
            AssessmentOverview(state, viewModel, onDiscussWithAi)
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun AssessmentOverview(
    state: AssessmentUiState,
    viewModel: AssessmentViewModel,
    onDiscussWithAi: (String) -> Unit
) {
    val instrument = requireNotNull(state.instrument)
    val form = instrument.form
    val entry = instrument.protocol?.entry

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = instrument.category,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                instrument.protocol?.let {
                    Text(
                        text = it.edition,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.Compact))
            Text(
                text = instrument.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(AppSpacing.Micro))
            Text(
                text = instrument.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (form != null || entry != null) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AppSpacing.Medium)) {
                Text(
                    text = "Pelaksanaan Asesmen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(AppSpacing.Micro))
                val description = when {
                    form != null && entry != null ->
                        "Instrumen ini dapat dikerjakan secara langsung di aplikasi butir per butir, " +
                            "atau dihitung melalui kalkulator rekap lembar jawaban fisik."
                    entry != null ->
                        "Instrumen ini menggunakan kalkulator rekap subtes dari lembar " +
                            "jawaban aktual untuk menghasilkan profil skor dan interpretasi klinis."
                    else -> form?.instructions.orEmpty()
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AppSpacing.Medium))

                if (form != null) {
                    val formLabel = if (state.result != null) "Kerjakan Ulang Soal di Aplikasi" else "Mulai Kerjakan Soal di Aplikasi"
                    Button(
                        onClick = viewModel::startInteractiveForm,
                        enabled = !state.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(AppSpacing.Compact))
                        Text(formLabel)
                    }
                }

                if (entry != null) {
                    if (form != null) Spacer(Modifier.height(AppSpacing.Small))
                    if (form != null) {
                        OutlinedButton(
                            onClick = viewModel::startMarkedSheetEntry,
                            enabled = !state.busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(AppSpacing.Compact))
                            Text("Masukkan Rekap Lembar Jawaban")
                        }
                    } else {
                        Button(
                            onClick = viewModel::startMarkedSheetEntry,
                            enabled = !state.busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                        ) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(AppSpacing.Compact))
                            Text("Masukkan Rekap Lembar Jawaban")
                        }
                    }
                }
            }
        }
    }

    state.result?.let { AssessmentResultContent(it, state, viewModel, onDiscussWithAi) }

    instrument.protocol?.let { protocol ->
        ScoringGuideCard(protocol, instrument, state, viewModel, onDiscussWithAi)
    }

    val url = form?.sourceUrl ?: instrument.referenceUrl
    if (!url.isNullOrBlank()) {
        AssessmentSource(form?.sourceTitle ?: instrument.title, url)
    }
}

@Composable
@Suppress("LongMethod")
private fun ScoringGuideCard(
    protocol: AssessmentProtocol,
    instrument: AssessmentInstrument,
    state: AssessmentUiState,
    viewModel: AssessmentViewModel,
    onDiscussWithAi: (String) -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.Medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(AppSpacing.Compact))
                Text("Panduan Skoring & Norma Klinis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(AppSpacing.Micro))
            Text(protocol.edition, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

            Spacer(Modifier.height(AppSpacing.Compact))
            Text("Metode Skoring", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(protocol.method, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(AppSpacing.Compact))
            Text("Cara Membaca Hasil", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(protocol.interpretation, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(AppSpacing.Compact))
            Text("Batasan Klinis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(protocol.limitation, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(AppSpacing.Compact))
            Text(
                "Acuan: ${instrument.source.substringAfterLast('/')} · §${protocol.section}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(Modifier.height(AppSpacing.Medium))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                Button(
                    onClick = { onDiscussWithAi(instrument.toGuideDiscussionPrompt()) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(AppSpacing.Micro))
                    Text("Bahas di AI+", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = viewModel::explainGuide,
                    enabled = !state.busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(AppSpacing.Micro))
                        Text("Ringkas di Sini", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            state.guideExplanation?.let { explanation ->
                Spacer(Modifier.height(AppSpacing.Small))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(AppSpacing.Medium)) {
                        Text(
                            text = "Penjelasan AI Lokal",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(AppSpacing.Micro))
                        Text(
                            text = explanation,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod")
private fun LazyListScope.markedScoreForm(entry: AssessmentScoreEntry, state: AssessmentUiState, viewModel: AssessmentViewModel) {
    item {
        val parsedScores = entry.fields.indices.mapNotNull { state.markedScores[it]?.toIntOrNull() }
        val filledCount = parsedScores.size
        val totalSum = parsedScores.sum()
        val progress = if (entry.fields.isNotEmpty()) filledCount.toFloat() / entry.fields.size else 0f

        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AppSpacing.Medium)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Calculate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(AppSpacing.Compact))
                    Text(
                        text = "Formulir Rekap Lembar Jawaban",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(AppSpacing.Micro))
                Text(
                    text = entry.version,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(AppSpacing.Compact))
                Text(
                    text = entry.instructions,
                    style = MaterialTheme.typography.bodyMedium
                )

                entry.requiredTotal?.takeUnless { entry.operation == ScoreOperation.EPPS }?.let { reqTotal ->
                    Spacer(Modifier.height(AppSpacing.Small))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        val ruleText = if (entry.groupSize == null) {
                            "Aturan: Total seluruh subtes harus berjumlah $reqTotal."
                        } else {
                            "Aturan: Jumlah tiap ${entry.groupSize} subtes harus berjumlah $reqTotal."
                        }
                        Text(
                            text = ruleText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(AppSpacing.Medium))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Terisi: $filledCount dari ${entry.fields.size} subtes",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "Akumulasi Sementara: $totalSum",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(AppSpacing.Micro))
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
            }
        }
    }

    itemsIndexed(entry.fields, key = { index, _ -> index }) { index, field ->
        val value = state.markedScores[index].orEmpty()
        val intValue = value.toIntOrNull()
        val isEntered = value.isNotBlank()
        val isValid = isEntered && intValue != null && intValue in field.minimum..field.maximum
        val isError = isEntered && !isValid

        OutlinedCard(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AppSpacing.Medium)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { viewModel.enterMarkedScore(index, it) },
                    label = { Text(field.label) },
                    placeholder = { Text("${field.minimum} - ${field.maximum}") },
                    trailingIcon = {
                        if (isValid) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Valid", tint = AppPalette.Primary)
                        } else if (isError) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = "Di luar rentang", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    supportingText = {
                        val helpText = if (isError) {
                            "Nilai harus di antara ${field.minimum} sampai ${field.maximum}"
                        } else {
                            "Rentang skor yang diizinkan: ${field.minimum}–${field.maximum}"
                        }
                        Text(helpText)
                    },
                    isError = isError,
                    enabled = !state.busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    item {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                if (state.canScore) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AppPalette.Primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(AppSpacing.Compact))
                        Text(
                            text = "Semua subtes valid! Siap untuk dikalkulasi dan dianalisis.",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AppPalette.Primary
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(AppSpacing.Compact))
                        Text(
                            text = "Harap lengkapi semua nilai subtes sesuai batas rentang.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state.result != null) {
                    Text(
                        "Menyimpan rekap baru akan mengganti hasil sebelumnya.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Button(
                    onClick = viewModel::submit,
                    enabled = !state.busy && state.canScore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(AppSpacing.Compact))
                    Text("Hitung dan Simpan Hasil")
                }

                OutlinedButton(
                    onClick = viewModel::stop,
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Batal")
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
private fun AssessmentQuestion(state: AssessmentUiState, form: AssessmentForm, viewModel: AssessmentViewModel) {
    val index = state.question
    val currentAnswer = state.answers[index]
    val progress = if (form.items.isNotEmpty()) (state.answers.size).toFloat() / form.items.size else 0f
    var showJumpDialog by remember { mutableStateOf(false) }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showJumpDialog = true }) {
                    Text(
                        text = "Butir ${index + 1} dari ${form.items.size} ▾",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${state.answers.size} terjawab",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(AppSpacing.Micro))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )

            Spacer(Modifier.height(AppSpacing.Medium))
            val currentItem = form.items[index]
            Text(
                text = currentItem.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            val context = LocalContext.current
            val stimulusBitmap = remember(currentItem.imageAssetPath) {
                currentItem.imageAssetPath?.let { path ->
                    runCatching {
                        context.assets.open(path).use { stream ->
                            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                        }
                    }.getOrNull()
                }
            }
            val referenceAssetPath = when (currentItem.scale) {
                "ist_fa" -> "ai/psychometrics/images/ist/fa_ref_1.png"
                "ist_wu" -> "ai/psychometrics/images/ist/wu_ref.png"
                else -> null
            }
            val referenceBitmap = remember(referenceAssetPath) {
                referenceAssetPath?.let { path ->
                    runCatching {
                        context.assets.open(path).use { stream ->
                            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                        }
                    }.getOrNull()
                }
            }
            var showZoomDialog by remember { mutableStateOf(false) }
            var zoomDialogTab by remember { mutableStateOf(0) }

            if (stimulusBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = CardDefaults.outlinedCardBorder(false),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.Small)
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.Small),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp, max = 280.dp)
                        ) {
                            Image(
                                bitmap = stimulusBitmap,
                                contentDescription = "Stimulus Figural Butir ${index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AppSpacing.Compact)
                            )
                        }
                        Spacer(Modifier.height(AppSpacing.Compact))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    zoomDialogTab = 0
                                    showZoomDialog = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(AppSpacing.Micro))
                                Text("Analisis Soal", maxLines = 1)
                            }
                            if (referenceBitmap != null) {
                                Button(
                                    onClick = {
                                        zoomDialogTab = 1
                                        showZoomDialog = true
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(AppSpacing.Micro))
                                    Text("Acuan A–E", maxLines = 1)
                                }
                            }
                        }
                    }
                }

                if (showZoomDialog) {
                    Dialog(
                        onDismissRequest = { showZoomDialog = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(AppSpacing.Medium),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(AppSpacing.Medium)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Analisis Gambar Stimulus (Butir ${index + 1})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { showZoomDialog = false },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Tutup")
                                    }
                                }

                                if (referenceBitmap != null) {
                                    Spacer(Modifier.height(AppSpacing.Compact))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
                                    ) {
                                        FilterChip(
                                            selected = zoomDialogTab == 0,
                                            onClick = { zoomDialogTab = 0 },
                                            label = { Text("Soal", maxLines = 1) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilterChip(
                                            selected = zoomDialogTab == 1,
                                            onClick = { zoomDialogTab = 1 },
                                            label = { Text("Acuan", maxLines = 1) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilterChip(
                                            selected = zoomDialogTab == 2,
                                            onClick = { zoomDialogTab = 2 },
                                            label = { Text("Bandingkan", maxLines = 1) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(AppSpacing.Small))
                                Text(
                                    text = currentItem.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(AppSpacing.Small))

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(AppSpacing.Small)
                                            .verticalScroll(rememberScrollState()),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        when (zoomDialogTab) {
                                            0 -> {
                                                Image(
                                                    bitmap = stimulusBitmap,
                                                    contentDescription = "Detail Stimulus Butir ${index + 1}",
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                            1 -> {
                                                if (referenceBitmap != null) {
                                                    Text(
                                                        text = "Lembar Bangun Acuan Pilihan (A, B, C, D, E)",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.DarkGray,
                                                        modifier = Modifier.padding(bottom = AppSpacing.Small)
                                                    )
                                                    Image(
                                                        bitmap = referenceBitmap,
                                                        contentDescription = "Lembar Bangun Acuan A-E",
                                                        contentScale = ContentScale.Fit,
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                }
                                            }
                                            else -> {
                                                Text(
                                                    text = "1. Potongan Soal Butir ${index + 1}:",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.DarkGray,
                                                    modifier = Modifier.padding(bottom = AppSpacing.Micro)
                                                )
                                                Image(
                                                    bitmap = stimulusBitmap,
                                                    contentDescription = "Stimulus Soal",
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 240.dp)
                                                )
                                                if (referenceBitmap != null) {
                                                    Spacer(Modifier.height(AppSpacing.Medium))
                                                    Text(
                                                        text = "2. Bangun Acuan Jawaban (A, B, C, D, E):",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.DarkGray,
                                                        modifier = Modifier.padding(bottom = AppSpacing.Micro)
                                                    )
                                                    Image(
                                                        bitmap = referenceBitmap,
                                                        contentDescription = "Bangun Acuan",
                                                        contentScale = ContentScale.Fit,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .heightIn(max = 240.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(AppSpacing.Medium))
                                Button(
                                    onClick = { showZoomDialog = false },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text("Kembali ke Pilihan Jawaban")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Medium))
            val choices = currentItem.choices ?: form.choices

            if (currentItem.multiSelect) {
                val currentMask = currentAnswer ?: 0
                val selectedCount = (0 until choices.size).count { (currentMask and (1 shl it)) != 0 }
                Text(
                    text = "Pilihlah ${currentItem.maxSelections} gambar (terpilih: $selectedCount dari ${currentItem.maxSelections})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(AppSpacing.Micro))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    choices.forEachIndexed { choice, label ->
                        val isSelected = (currentMask and (1 shl choice)) != 0
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.toggleMultiOption(choice, currentItem.maxSelections) },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            } else if (choices.size > 5) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    choices.forEachIndexed { choice, label ->
                        val selected = currentAnswer == choice
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.answer(choice) },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            } else {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
                    choices.forEachIndexed { choice, label ->
                        val selected = currentAnswer == choice
                        val surfaceColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = surfaceColor,
                            border = CardDefaults.outlinedCardBorder(selected),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected,
                                    enabled = !state.busy,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.answer(choice) }
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Compact),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = null, enabled = !state.busy)
                                Spacer(Modifier.width(AppSpacing.Compact))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            if (form.minimumAnswers < form.items.size && currentAnswer != null) {
                Spacer(Modifier.height(AppSpacing.Compact))
                TextButton(onClick = { viewModel.answer(null) }, enabled = !state.busy) {
                    Text("Kosongkan jawaban butir ini")
                }
            }

            Spacer(Modifier.height(AppSpacing.Medium))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                OutlinedButton(
                    onClick = { viewModel.move(-1) },
                    enabled = !state.busy && index > 0,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(AppSpacing.Micro))
                    Text("Sebelumnya")
                }
                if (index < form.items.lastIndex) {
                    val canAdvance = currentAnswer != null || form.minimumAnswers < form.items.size
                    Button(
                        onClick = { viewModel.move(1) },
                        enabled = !state.busy && canAdvance,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Berikutnya")
                        Spacer(Modifier.width(AppSpacing.Micro))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Button(
                        onClick = viewModel::submit,
                        enabled = !state.busy && state.canScore,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(AppSpacing.Micro))
                        Text("Selesai & Simpan")
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Small))
            TextButton(
                onClick = viewModel::stop,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Batalkan Pengerjaan")
            }
        }
    }

    if (showJumpDialog) {
        QuestionJumpDialog(
            total = form.items.size,
            current = index,
            isAnswered = { state.answers.containsKey(it) },
            onSelect = {
                viewModel.jumpTo(it)
                showJumpDialog = false
            },
            onDismiss = { showJumpDialog = false }
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
private fun DiscQuestion(state: AssessmentUiState, form: AssessmentForm, viewModel: AssessmentViewModel) {
    val groupIdx = state.question
    val mostKey = groupIdx * 2
    val leastKey = groupIdx * 2 + 1
    val currentMost = state.answers[mostKey]
    val currentLeast = state.answers[leastKey]
    val answeredCount = (0 until form.items.size).count {
        state.answers.containsKey(it * 2) && state.answers.containsKey(it * 2 + 1)
    }
    val progress = if (form.items.isNotEmpty()) answeredCount.toFloat() / form.items.size else 0f
    var showJumpDialog by remember { mutableStateOf(false) }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showJumpDialog = true }) {
                    Text(
                        text = "Kelompok ${groupIdx + 1} dari ${form.items.size} ▾",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "$answeredCount dari ${form.items.size} lengkap",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(AppSpacing.Micro))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )

            Spacer(Modifier.height(AppSpacing.Medium))
            Text(
                text = "Pilih 1 pernyataan yang PALING menggambarkan [P] dan 1 pernyataan yang " +
                    "PALING TIDAK menggambarkan [K] diri Anda saat bekerja.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(AppSpacing.Medium))
            val stmts = form.items[groupIdx].choices ?: form.choices
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                stmts.forEachIndexed { sIdx, stmtText ->
                    val isMost = currentMost == sIdx
                    val isLeast = currentLeast == sIdx
                    val surfaceColor = when {
                        isMost -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        isLeast -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = surfaceColor,
                        border = CardDefaults.outlinedCardBorder(isMost || isLeast),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Compact),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stmtText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isMost || isLeast) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(AppSpacing.Compact))
                            FilterChip(
                                selected = isMost,
                                onClick = { viewModel.answerDisc(sIdx, isMost = true) },
                                label = { Text("P", fontWeight = FontWeight.Bold) },
                                modifier = Modifier.heightIn(min = 44.dp)
                            )
                            Spacer(Modifier.width(AppSpacing.Micro))
                            FilterChip(
                                selected = isLeast,
                                onClick = { viewModel.answerDisc(sIdx, isMost = false) },
                                label = { Text("K", fontWeight = FontWeight.Bold) },
                                modifier = Modifier.heightIn(min = 44.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Small))
            val isComplete = currentMost != null && currentLeast != null
            val statusText = when {
                isComplete -> "✓ Lengkap (P & K dipilih)"
                currentMost != null -> "ℹ️ [P] dipilih. Sekarang pilih 1 pernyataan untuk [K]."
                currentLeast != null -> "ℹ️ [K] dipilih. Sekarang pilih 1 pernyataan untuk [P]."
                else -> "⚠️ Pilih 1 [P] (Paling) dan 1 [K] (Kurang)"
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isComplete) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(AppSpacing.Medium))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                OutlinedButton(
                    onClick = { viewModel.move(-1) },
                    enabled = !state.busy && groupIdx > 0,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(AppSpacing.Micro))
                    Text("Sebelumnya")
                }
                if (groupIdx < form.items.lastIndex) {
                    Button(
                        onClick = { viewModel.move(1) },
                        enabled = !state.busy && isComplete,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Berikutnya")
                        Spacer(Modifier.width(AppSpacing.Micro))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Button(
                        onClick = viewModel::submit,
                        enabled = !state.busy && state.canScore,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(AppSpacing.Micro))
                        Text("Selesai & Simpan")
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Small))
            TextButton(
                onClick = viewModel::stop,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Batalkan Pengerjaan")
            }
        }
    }

    if (showJumpDialog) {
        QuestionJumpDialog(
            total = form.items.size,
            current = groupIdx,
            isAnswered = { g -> state.answers.containsKey(g * 2) && state.answers.containsKey(g * 2 + 1) },
            onSelect = {
                viewModel.jumpTo(it)
                showJumpDialog = false
            },
            onDismiss = { showJumpDialog = false }
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
private fun KuderTriadQuestion(state: AssessmentUiState, form: AssessmentForm, viewModel: AssessmentViewModel) {
    val groupIdx = state.question
    val mostKey = groupIdx * 2
    val leastKey = groupIdx * 2 + 1
    val currentMost = state.answers[mostKey]
    val currentLeast = state.answers[leastKey]
    val answeredCount = (0 until form.items.size).count {
        state.answers.containsKey(it * 2) && state.answers.containsKey(it * 2 + 1)
    }
    val progress = if (form.items.isNotEmpty()) answeredCount.toFloat() / form.items.size else 0f
    var showJumpDialog by remember { mutableStateOf(false) }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showJumpDialog = true }) {
                    Text(
                        text = "Kelompok ${groupIdx + 1} dari ${form.items.size} ▾",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "$answeredCount dari ${form.items.size} lengkap",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(AppSpacing.Micro))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )

            Spacer(Modifier.height(AppSpacing.Medium))
            Text(
                text = "Pilih 1 kegiatan yang PALING Anda sukai [+] dan 1 kegiatan yang " +
                    "PALING TIDAK Anda sukai [−].",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(AppSpacing.Medium))
            val stmts = form.items[groupIdx].choices ?: form.choices
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                stmts.forEachIndexed { sIdx, stmtText ->
                    val isMost = currentMost == sIdx
                    val isLeast = currentLeast == sIdx
                    val surfaceColor = when {
                        isMost -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        isLeast -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = surfaceColor,
                        border = CardDefaults.outlinedCardBorder(isMost || isLeast),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Compact),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stmtText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isMost || isLeast) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(AppSpacing.Compact))
                            FilterChip(
                                selected = isMost,
                                onClick = { viewModel.answerTriad(sIdx, isMost = true) },
                                label = { Text("+", fontWeight = FontWeight.Bold) },
                                modifier = Modifier.heightIn(min = 44.dp)
                            )
                            Spacer(Modifier.width(AppSpacing.Micro))
                            FilterChip(
                                selected = isLeast,
                                onClick = { viewModel.answerTriad(sIdx, isMost = false) },
                                label = { Text("−", fontWeight = FontWeight.Bold) },
                                modifier = Modifier.heightIn(min = 44.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Small))
            val isComplete = currentMost != null && currentLeast != null
            val statusText = when {
                isComplete -> "✓ Lengkap (+ dan − dipilih)"
                currentMost != null -> "ℹ️ [+] dipilih. Sekarang pilih 1 kegiatan untuk [−]."
                currentLeast != null -> "ℹ️ [−] dipilih. Sekarang pilih 1 kegiatan untuk [+]."
                else -> "⚠️ Pilih 1 [+] (Paling Disukai) dan 1 [−] (Paling Tidak Disukai)"
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isComplete) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(AppSpacing.Medium))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                OutlinedButton(
                    onClick = { viewModel.move(-1) },
                    enabled = !state.busy && groupIdx > 0,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(AppSpacing.Micro))
                    Text("Sebelumnya")
                }
                if (groupIdx < form.items.lastIndex) {
                    Button(
                        onClick = { viewModel.move(1) },
                        enabled = !state.busy && isComplete,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Berikutnya")
                        Spacer(Modifier.width(AppSpacing.Micro))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Button(
                        onClick = viewModel::submit,
                        enabled = !state.busy && state.canScore,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(AppSpacing.Micro))
                        Text("Selesai & Simpan")
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Small))
            TextButton(
                onClick = viewModel::stop,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Batalkan Pengerjaan")
            }
        }
    }

    if (showJumpDialog) {
        QuestionJumpDialog(
            total = form.items.size,
            current = groupIdx,
            isAnswered = { g -> state.answers.containsKey(g * 2) && state.answers.containsKey(g * 2 + 1) },
            onSelect = {
                viewModel.jumpTo(it)
                showJumpDialog = false
            },
            onDismiss = { showJumpDialog = false }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun PauliTestScreen(
    state: AssessmentUiState,
    form: AssessmentForm,
    viewModel: AssessmentViewModel
) {
    var isStarted by remember { mutableStateOf(false) }
    var totalIntervals by remember { mutableStateOf(5) } // Default 5 interval untuk demonstrasi & tes cepat
    var currentIntervalIndex by remember { mutableStateOf(0) }
    var currentRowIndex by remember { mutableStateOf(0) }
    var secondsLeft by remember { mutableStateOf(15) }
    var showGarisBanner by remember { mutableStateOf(false) }
    var showConfirmFinish by remember { mutableStateOf(false) }

    val results = remember { mutableStateMapOf<Int, Triple<Int, Int, Int>>() }
    var colCorrect by remember { mutableStateOf(0) }
    var colError by remember { mutableStateOf(0) }
    var colSkip by remember { mutableStateOf(0) }

    val digits = remember(currentIntervalIndex) {
        val random = java.util.Random((currentIntervalIndex + 1) * 6271L)
        (0 until 40).map { random.nextInt(9) + 1 }
    }

    LaunchedEffect(isStarted, currentIntervalIndex) {
        if (!isStarted) return@LaunchedEffect
        secondsLeft = 15
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft--
        }
        results[currentIntervalIndex] = Triple(colCorrect, colError, colSkip)
        showGarisBanner = true
        delay(800L)
        showGarisBanner = false
        if (currentIntervalIndex + 1 < totalIntervals) {
            currentIntervalIndex++
            currentRowIndex = 0
            colCorrect = 0
            colError = 0
            colSkip = 0
        } else {
            viewModel.submitPauliAll(results)
        }
    }

    if (!isStarted) {
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
            ) {
                Text(
                    text = "Tes Pauli (Hitung Cepat Sikap Kerja Atas ke Bawah)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Tes Pauli mengukur daya tahan kerja, konsistensi kemauan (willpower), dan stabilitas emosi. " +
                        "Berbeda dengan Kraepelin, penjumlahan Pauli dilakukan dari ATAS ke BAWAH. " +
                        "Saat waktu interval habis, aba-aba 'GARIS!' akan muncul sebagai penanda batas interval.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                        Text(
                            text = "Petunjuk Pengerjaan:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "1. Jumlahkan angka ATAS dan angka BAWAH.\n" +
                                "2. Ketik angka SATUAN hasil penjumlahan pada keypad (contoh: 8 + 7 = 15 -> ketik 5).\n" +
                                "3. Kerjakan secepat dan seteliti mungkin.\n" +
                                "4. Saat tanda 'GARIS!' muncul, batas interval tercatat otomatis dan lanjutkan penjumlahan ke bawah.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "Pilih Mode Durasi:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    FilterChip(
                        selected = totalIntervals == 5,
                        onClick = { totalIntervals = 5 },
                        label = { Text("Simulasi Kilat (5 Interval)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = totalIntervals == 20,
                        onClick = { totalIntervals = 20 },
                        label = { Text("Standar (20 Interval)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(
                    onClick = {
                        isStarted = true
                        currentIntervalIndex = 0
                        currentRowIndex = 0
                        colCorrect = 0
                        colError = 0
                        colSkip = 0
                        results.clear()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(AppSpacing.Compact))
                    Text("Mulai Tes Pauli Sekarang")
                }
            }
        }
    } else {
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Interval ${currentIntervalIndex + 1} dari $totalIntervals",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (secondsLeft <= 3) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "⏱️ ${secondsLeft}s",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (secondsLeft <= 3) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = secondsLeft.toFloat() / 15f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = if (secondsLeft <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Benar: $colCorrect · Salah: $colError · Lewati: $colSkip",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Selesai: ${results.size}/$totalIntervals",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (showGarisBanner) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(AppSpacing.Medium),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "✍️ GARIS!",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Batas interval tercatat. Lanjutkan ke bawah...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    } else {
                        val topNum = digits.getOrElse(currentRowIndex) { 5 }
                        val bottomNum = digits.getOrElse(currentRowIndex + 1) { 3 }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AppSpacing.Medium),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "$topNum",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "➕",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "$bottomNum",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Jumlahkan (Atas + Bawah):",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "$topNum + $bottomNum = ?",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Ketik digit satuan",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Keypad
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val keyRows = listOf(
                        listOf(1, 2, 3),
                        listOf(4, 5, 6),
                        listOf(7, 8, 9)
                    )
                    for (row in keyRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (digit in row) {
                                Button(
                                    onClick = {
                                        if (!showGarisBanner) {
                                            val tNum = digits.getOrElse(currentRowIndex) { 0 }
                                            val bNum = digits.getOrElse(currentRowIndex + 1) { 0 }
                                            val expected = (tNum + bNum) % 10
                                            if (digit == expected) {
                                                colCorrect++
                                            } else {
                                                colError++
                                            }
                                            if (currentRowIndex + 2 < digits.size) {
                                                currentRowIndex++
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = "$digit",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!showGarisBanner) {
                                    colSkip++
                                    if (currentRowIndex + 2 < digits.size) {
                                        currentRowIndex++
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Lewati", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = {
                                if (!showGarisBanner) {
                                    val tNum = digits.getOrElse(currentRowIndex) { 0 }
                                    val bNum = digits.getOrElse(currentRowIndex + 1) { 0 }
                                    val expected = (tNum + bNum) % 10
                                    if (0 == expected) {
                                        colCorrect++
                                    } else {
                                        colError++
                                    }
                                    if (currentRowIndex + 2 < digits.size) {
                                        currentRowIndex++
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { showConfirmFinish = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text("Selesai", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showConfirmFinish) {
        AlertDialog(
            onDismissRequest = { showConfirmFinish = false },
            title = { Text("Akhiri Tes Pauli?") },
            text = { Text("Interval yang telah dikerjakan akan disimpan dan dianalisis kurva kerjanya.") },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmFinish = false
                        results[currentIntervalIndex] = Triple(colCorrect, colError, colSkip)
                        viewModel.submitPauliAll(results)
                    }
                ) {
                    Text("Ya, Akhiri & Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmFinish = false }) {
                    Text("Batal")
                }
            }
        )
    }
}


@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun KraepelinTestScreen(
    state: AssessmentUiState,
    form: AssessmentForm,
    viewModel: AssessmentViewModel
) {
    var isStarted by remember { mutableStateOf(false) }
    var totalColumns by remember { mutableStateOf(10) }
    var currentColumnIndex by remember { mutableStateOf(0) }
    var currentRowIndex by remember { mutableStateOf(0) }
    var secondsLeft by remember { mutableStateOf(15) }
    var showPindahBanner by remember { mutableStateOf(false) }
    var showConfirmFinish by remember { mutableStateOf(false) }

    val results = remember { mutableStateMapOf<Int, Triple<Int, Int, Int>>() }
    var colCorrect by remember { mutableStateOf(0) }
    var colError by remember { mutableStateOf(0) }
    var colSkip by remember { mutableStateOf(0) }

    val digits = remember(currentColumnIndex) {
        val random = java.util.Random((currentColumnIndex + 1) * 7919L)
        (0 until 35).map { random.nextInt(9) + 1 }
    }

    LaunchedEffect(isStarted, currentColumnIndex) {
        if (!isStarted) return@LaunchedEffect
        secondsLeft = 15
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft--
        }
        results[currentColumnIndex] = Triple(colCorrect, colError, colSkip)
        showPindahBanner = true
        delay(800L)
        showPindahBanner = false
        if (currentColumnIndex + 1 < totalColumns) {
            currentColumnIndex++
            currentRowIndex = 0
            colCorrect = 0
            colError = 0
            colSkip = 0
        } else {
            viewModel.submitKraepelinAll(results)
        }
    }

    if (!isStarted) {
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
            ) {
                Text(
                    text = "Tes Kraepelin (Hitung Cepat & Sikap Kerja)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Tes ini mengukur empat aspek sikap kerja secara objektif di bawah tekanan waktu: " +
                        "PANKER (Kecepatan Kerja), TIANKER (Ketelitian Kerja), JANKER (Keajegan Ritme), " +
                        "dan HANKER (Ketahanan Kerja / Ausdauer).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                        Text(
                            text = "Petunjuk Pengerjaan:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "1. Jumlahkan dua angka yang berdekatan dari BAWAH ke ATAS.\n" +
                                "2. Ketik ANGKA SATUANNYA SAJA pada keypad on-screen (contoh: 8 + 7 = 15, ketik [ 5 ]).\n" +
                                "3. Setiap lajur berdurasi tepat 15 DETIK.\n" +
                                "4. Saat aba-aba 'PINDAH!' muncul, sistem akan otomatis berpindah ke lajur berikutnya.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "Pilih Durasi Tes:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    FilterChip(
                        selected = totalColumns == 10,
                        onClick = { totalColumns = 10 },
                        label = { Text("Simulasi Kilat (10 Lajur)") },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    )
                    FilterChip(
                        selected = totalColumns == 50,
                        onClick = { totalColumns = 50 },
                        label = { Text("Standar Lengkap (50 Lajur)") },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    )
                }

                Button(
                    onClick = {
                        isStarted = true
                        currentColumnIndex = 0
                        currentRowIndex = 0
                        colCorrect = 0
                        colError = 0
                        colSkip = 0
                        results.clear()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(AppSpacing.Small))
                    Text("Mulai Tes Kraepelin", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }

                TextButton(
                    onClick = viewModel::stop,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Kembali")
                }
            }
        }
    } else {
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Lajur ${currentColumnIndex + 1} dari $totalColumns",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (secondsLeft <= 3) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ) {
                        Text(
                            text = "⏱️ ${secondsLeft}s",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (secondsLeft <= 3) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = (15 - secondsLeft) / 15f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = if (secondsLeft <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Benar: $colCorrect · Salah: $colError · Lewati: $colSkip",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Lajur Selesai: ${results.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (showPindahBanner) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(AppSpacing.Medium),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "🔔 PINDAH!",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Beralih ke lajur berikutnya...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    } else {
                        val bottomNum = digits.getOrElse(currentRowIndex) { 5 }
                        val topNum = digits.getOrElse(currentRowIndex + 1) { 3 }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AppSpacing.Medium),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "$topNum",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "➕",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "$bottomNum",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Jumlahkan (Bawah + Atas):",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "$bottomNum + $topNum = ?",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Ketik angka satuan",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val keyRows = listOf(
                        listOf(1, 2, 3),
                        listOf(4, 5, 6),
                        listOf(7, 8, 9)
                    )
                    for (row in keyRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (digit in row) {
                                Button(
                                    onClick = {
                                        if (!showPindahBanner) {
                                            val bNum = digits.getOrElse(currentRowIndex) { 0 }
                                            val tNum = digits.getOrElse(currentRowIndex + 1) { 0 }
                                            val expected = (bNum + tNum) % 10
                                            if (digit == expected) {
                                                colCorrect++
                                            } else {
                                                colError++
                                            }
                                            if (currentRowIndex + 2 < digits.size) {
                                                currentRowIndex++
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = "$digit",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!showPindahBanner) {
                                    colSkip++
                                    if (currentRowIndex + 2 < digits.size) {
                                        currentRowIndex++
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Lewati", style = MaterialTheme.typography.labelMedium)
                        }

                        Button(
                            onClick = {
                                if (!showPindahBanner) {
                                    val bNum = digits.getOrElse(currentRowIndex) { 0 }
                                    val tNum = digits.getOrElse(currentRowIndex + 1) { 0 }
                                    val expected = (bNum + tNum) % 10
                                    if (expected == 0) {
                                        colCorrect++
                                    } else {
                                        colError++
                                    }
                                    if (currentRowIndex + 2 < digits.size) {
                                        currentRowIndex++
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        FilledTonalButton(
                            onClick = { showConfirmFinish = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Selesai", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (showConfirmFinish) {
        AlertDialog(
            onDismissRequest = { showConfirmFinish = false },
            title = { Text("Akhiri Tes Kraepelin?") },
            text = {
                Text(
                    "Anda telah menyelesaikan ${results.size + 1} lajur. " +
                        "Apakah Anda ingin mengakhiri tes sekarang dan memproses hasil evaluasi sikap kerja?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmFinish = false
                        results[currentColumnIndex] = Triple(colCorrect, colError, colSkip)
                        viewModel.submitKraepelinAll(results)
                    }
                ) {
                    Text("Ya, Proses Hasil")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmFinish = false }) {
                    Text("Lanjutkan Tes")
                }
            }
        )
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun WarteggDrawingScreen(
    state: AssessmentUiState,
    form: AssessmentForm,
    viewModel: AssessmentViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedBox by remember { mutableStateOf(0) }
    val allStrokes = remember { mutableStateMapOf<Int, MutableList<List<Offset>>>() }
    val currentStroke = remember { mutableStateListOf<Offset>() }
    val drawOrder = remember { mutableStateMapOf<Int, Int>() }
    var nextOrder by remember { mutableStateOf(1) }
    val titles = remember { mutableStateMapOf<Int, String>() }
    val evaluations = remember { mutableStateMapOf<Int, Int>() }
    var showStimulusZoom by remember { mutableStateOf(false) }

    val stimulusBitmaps = remember {
        (1..8).map { boxNum ->
            try {
                context.assets.open("ai/psychometrics/images/wartegg/box_$boxNum.png").use {
                    BitmapFactory.decodeStream(it)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tes Wartegg (Kanvas Gambar Digital)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val currentOrder = drawOrder[selectedBox]
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = if (currentOrder != null) "Urutan: #$currentOrder" else "Belum Digambar",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "Pilih kotak yang ingin digambar terlebih dahulu. Teruskan stimulus menjadi gambar utuh dengan jari/pen. Beri judul gambar dan tandai evaluasi subjektif (+ / - / M / S).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Box selector chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(8) { boxIdx ->
                    val isSelected = selectedBox == boxIdx
                    val order = drawOrder[boxIdx]
                    val eval = evaluations[boxIdx]
                    val evalTag = when (eval) {
                        0 -> " [+]"
                        1 -> " [-]"
                        2 -> " [M]"
                        3 -> " [S]"
                        else -> ""
                    }
                    val orderTag = if (order != null) " (#$order)" else ""
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedBox = boxIdx },
                        label = {
                            Text(
                                text = "K${boxIdx + 1}$orderTag$evalTag",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Canvas Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .border(
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(12.dp)
                    )
                    .pointerInput(selectedBox) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                if (!drawOrder.containsKey(selectedBox)) {
                                    drawOrder[selectedBox] = nextOrder++
                                }
                                currentStroke.clear()
                                currentStroke.add(offset)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                currentStroke.add(change.position)
                            },
                            onDragEnd = {
                                if (currentStroke.isNotEmpty()) {
                                    val boxList = allStrokes.getOrPut(selectedBox) { mutableListOf() }
                                    boxList.add(currentStroke.toList())
                                    currentStroke.clear()
                                }
                            }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    stimulusBitmaps.getOrNull(selectedBox)?.let { bmp ->
                        drawImage(
                            image = bmp,
                            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                            alpha = 0.85f
                        )
                    }
                    val boxStrokes = allStrokes[selectedBox] ?: emptyList()
                    for (stroke in boxStrokes) {
                        if (stroke.size > 1) {
                            val path = Path()
                            path.moveTo(stroke[0].x, stroke[0].y)
                            for (i in 1 until stroke.size) {
                                path.lineTo(stroke[i].x, stroke[i].y)
                            }
                            drawPath(
                                path = path,
                                color = androidx.compose.ui.graphics.Color(0xFF0F172A),
                                style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                    if (currentStroke.size > 1) {
                        val path = Path()
                        path.moveTo(currentStroke[0].x, currentStroke[0].y)
                        for (i in 1 until currentStroke.size) {
                            path.lineTo(currentStroke[i].x, currentStroke[i].y)
                        }
                        drawPath(
                            path = path,
                            color = androidx.compose.ui.graphics.Color(0xFF0F172A),
                            style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }
            }

            // Canvas action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                    OutlinedButton(
                        onClick = {
                            val boxList = allStrokes[selectedBox]
                            if (!boxList.isNullOrEmpty()) {
                                boxList.removeAt(boxList.lastIndex)
                            }
                        },
                        enabled = (allStrokes[selectedBox]?.isNotEmpty() == true)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Undo")
                    }
                    OutlinedButton(
                        onClick = {
                            allStrokes[selectedBox]?.clear()
                        },
                        enabled = (allStrokes[selectedBox]?.isNotEmpty() == true)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Hapus", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Hapus")
                    }
                }
                OutlinedButton(onClick = { showStimulusZoom = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Lihat Stimulus", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stimulus Asli")
                }
            }

            // Input Title
            OutlinedTextField(
                value = titles[selectedBox] ?: "",
                onValueChange = { titles[selectedBox] = it },
                label = { Text("Judul / Keterangan Gambar (Kotak ${selectedBox + 1})") },
                placeholder = { Text("Contoh: Jam Dinding, Burung Terbang, Bunga...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Evaluation Selector Chips
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Evaluasi Subjektif untuk Kotak ${selectedBox + 1}:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val currentEval = evaluations[selectedBox] ?: -1
                    val evalOptions = listOf(
                        0 to "+ Disukai",
                        1 to "- Kurang",
                        2 to "M Mudah",
                        3 to "S Sukar"
                    )
                    evalOptions.forEach { (evalCode, evalLabel) ->
                        val isSelected = currentEval == evalCode
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    evaluations.remove(selectedBox)
                                } else {
                                    evaluations.entries.removeAll { it.value == evalCode }
                                    evaluations[selectedBox] = evalCode
                                }
                            },
                            label = { Text(evalLabel) }
                        )
                    }
                }
            }

            // Box Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { if (selectedBox > 0) selectedBox-- },
                    enabled = selectedBox > 0
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Kotak Seb.")
                }
                Button(
                    onClick = { if (selectedBox < 7) selectedBox++ },
                    enabled = selectedBox < 7
                ) {
                    Text("Kotak Sel.")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            // Submit Button
            val drawnBoxesCount = allStrokes.count { it.value.isNotEmpty() }
            Button(
                onClick = {
                    val encodedAnswers = mutableMapOf<Int, Int>()
                    for (b in 0 until 8) {
                        val order = drawOrder[b] ?: (b + 1)
                        val eval = evaluations[b] ?: -1
                        val encoded = order * 10 + (if (eval >= 0) eval else 4)
                        encodedAnswers[b] = encoded
                    }
                    viewModel.submitWarteggAll(encodedAnswers)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Simpan & Analisis Wartegg ($drawnBoxesCount/8 Selesai)")
            }
        }
    }

    // Stimulus Zoom Dialog
    if (showStimulusZoom) {
        AlertDialog(
            onDismissRequest = { showStimulusZoom = false },
            title = {
                Text(
                    text = "Stimulus Asli Kotak ${selectedBox + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
                ) {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .background(androidx.compose.ui.graphics.Color.White)
                            .border(BorderStroke(1.dp, androidx.compose.ui.graphics.Color.LightGray))
                    ) {
                        stimulusBitmaps.getOrNull(selectedBox)?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = "Stimulus Asli Kotak ${selectedBox + 1}",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Text(
                        text = form.items.getOrNull(selectedBox)?.text ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStimulusZoom = false }) {
                    Text("Tutup")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun RmibRankingScreen(
    state: AssessmentUiState,
    form: AssessmentForm,
    viewModel: AssessmentViewModel
) {
    var selectedGroup by remember { mutableStateOf(0) }
    val rankings = remember {
        val map = mutableStateMapOf<Int, Int>()
        state.answers.forEach { (k, v) -> map[k] = v }
        map
    }
    var pickerTargetProfIdx by remember { mutableStateOf<Int?>(null) }

    fun setRank(profIdx: Int, newRank: Int) {
        val key = selectedGroup * 12 + profIdx
        val otherProfIdx = (0 until 12).firstOrNull { it != profIdx && rankings[selectedGroup * 12 + it] == newRank }
        if (otherProfIdx != null) {
            val oldRank = rankings[key]
            if (oldRank != null) {
                rankings[selectedGroup * 12 + otherProfIdx] = oldRank
            } else {
                rankings.remove(selectedGroup * 12 + otherProfIdx)
            }
        }
        rankings[key] = newRank
    }

    val completedGroups = (0 until 9).count { g ->
        val ranks = (0 until 12).mapNotNull { p -> rankings[g * 12 + p] }
        ranks.size == 12 && ranks.toSet().size == 12
    }

    val currentGroupItem = form.items.getOrNull(selectedGroup)
    val professions = currentGroupItem?.choices ?: emptyList()

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val groupLetter = ('A'.code + selectedGroup).toChar()
                Text(
                    text = "RMIB — Kelompok $groupLetter",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val isGroupComplete = (0 until 12).mapNotNull { rankings[selectedGroup * 12 + it] }.toSet().size == 12
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isGroupComplete) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (isGroupComplete) "Lengkap ✓" else "Belum Lengkap",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isGroupComplete) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "Urutkan 12 profesi di bawah dari peringkat 1 (paling disukai) hingga 12 (paling tidak disukai). Sentuh peringkat untuk mengubah.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Group Chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(9) { g ->
                    val letter = ('A'.code + g).toChar()
                    val isSelected = selectedGroup == g
                    val complete = (0 until 12).mapNotNull { rankings[g * 12 + it] }.toSet().size == 12
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedGroup = g },
                        label = {
                            Text(
                                text = "Grup $letter${if (complete) " ✓" else ""}",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Quick default button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        for (p in 0 until 12) {
                            rankings[selectedGroup * 12 + p] = p + 1
                        }
                    }
                ) {
                    Text("Urutkan 1–12 Cepat", style = MaterialTheme.typography.labelSmall)
                }
            }

            // 12 Profession Cards
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                professions.forEachIndexed { pIdx, profName ->
                    val rank = rankings[selectedGroup * 12 + pIdx]
                    OutlinedCard(
                        onClick = { pickerTargetProfIdx = pIdx },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (rank != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = rank?.toString() ?: "-",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (rank != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { if (selectedGroup > 0) selectedGroup-- },
                    enabled = selectedGroup > 0
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Grup Seb.")
                }
                Button(
                    onClick = { if (selectedGroup < 8) selectedGroup++ },
                    enabled = selectedGroup < 8
                ) {
                    Text("Grup Sel.")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            // Submit Button
            Button(
                onClick = {
                    for (g in 0 until 9) {
                        val ranks = (0 until 12).mapNotNull { p -> rankings[g * 12 + p] }
                        if (ranks.toSet().size < 12) {
                            for (p in 0 until 12) {
                                rankings[g * 12 + p] = p + 1
                            }
                        }
                    }
                    viewModel.submitRmibAll(rankings)
                },
                enabled = (completedGroups >= 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (completedGroups == 9) "Simpan & Hitung Matriks Minat RMIB (9/9 Selesai)"
                    else "Simpan & Hitung Minat RMIB ($completedGroups/9 Selesai)"
                )
            }
        }
    }

    // Rank Picker Dialog
    pickerTargetProfIdx?.let { profIdx ->
        val profName = professions.getOrNull(profIdx) ?: ""
        AlertDialog(
            onDismissRequest = { pickerTargetProfIdx = null },
            title = {
                Text(
                    text = "Pilih Peringkat (1–12)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Profesi: $profName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "1 = Paling Disukai, 12 = Paling Tidak Disukai",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    // Grid of 12 buttons
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (row in 0 until 3) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (col in 0 until 4) {
                                    val r = row * 4 + col + 1
                                    val isCurrent = rankings[selectedGroup * 12 + profIdx] == r
                                    FilledTonalButton(
                                        onClick = {
                                            setRank(profIdx, r)
                                            pickerTargetProfIdx = null
                                        },
                                        colors = if (isCurrent) {
                                            ButtonDefaults.filledTonalButtonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            ButtonDefaults.filledTonalButtonColors()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("$r", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerTargetProfIdx = null }) {
                    Text("Batal")
                }
            }
        )
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun RorschachTestScreen(
    state: AssessmentUiState,
    form: AssessmentForm,
    viewModel: AssessmentViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentCard by remember { mutableStateOf(0) }
    var showZoomDialog by remember { mutableStateOf(false) }

    val associationTexts = remember { mutableStateMapOf<Int, String>() }
    val locations = remember { mutableStateMapOf<Int, Int>() }
    val determinants = remember { mutableStateMapOf<Int, Int>() }
    val formQualities = remember { mutableStateMapOf<Int, Int>() }
    val populars = remember { mutableStateMapOf<Int, Boolean>() }
    val contents = remember { mutableStateMapOf<Int, Int>() }
    val answered = remember { mutableStateMapOf<Int, Boolean>() }

    // Pre-decode 10 WebP cards
    val cardBitmaps = remember {
        (1..10).map { num ->
            try {
                val path = "ai/psychometrics/rorschach/card_${String.format(java.util.Locale.US, "%02d", num)}.webp"
                context.assets.open(path).use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    data class CardMeta(
        val roman: String,
        val plateType: String,
        val stimulusTheme: String,
        val suggestions: List<String>,
        val defaultDet: Int,
        val defaultPopIdx: Int
    )

    val cardMetas = remember {
        listOf(
            CardMeta("Kartu I", "Hitam/Abu-abu", "Adaptasi situasi baru & orientasi diri", listOf("Kelelawar (W)", "Kupu-kupu (W)", "Sosok wanita tengah (D)", "Topeng misterius (W/S)"), 0, 0),
            CardMeta("Kartu II", "Hitam + Merah Terang", "Emosi hangat/agresi & integrasi afek", listOf("Dua beruang / anjing (W)", "Kupu-kupu merah (D)", "Dua sosok menari (W)", "Topi / mahkota (D)"), 4, 0),
            CardMeta("Kartu III", "Hitam + Merah Terpisah", "Relasi interpersonal & identifikasi peran", listOf("Dua sosok manusia (D/W)", "Pita kupu-kupu merah (D)", "Dua burung / monyet (D)", "Panggul / tulang (D)"), 1, 0),
            CardMeta("Kartu IV", "Achromatik Masif (Father Card)", "Figur otoritas & kecemasan beban berat", listOf("Kulit binatang / karpet (W)", "Raksasa / monster (W)", "Sepatu bot besar (D)", "Pohon / tunggul (W)"), 0, 0),
            CardMeta("Kartu V", "Hitam Pekat (Reality Card)", "Uji realitas paling langsung & konvensional", listOf("Kelelawar terbang (W)", "Ngengat malam (W)", "Dua kelinci (D)", "Kepiting / capit (D)"), 0, 0),
            CardMeta("Kartu VI", "Achromatik Bertekstur (Sex Card)", "Kehangatan taktil, keintiman & tekstur", listOf("Kulit binatang terbentang (W)", "Tiang / totem (D)", "Sayap bulu halus (D)", "Alat musik petik (D)"), 8, 0),
            CardMeta("Kartu VII", "Abu-abu Lembut (Mother Card)", "Figur maternal, kelembutan & kedekatan awal", listOf("Dua kepala wanita / anak (W/D)", "Kelinci menari (W)", "Bongkahan awan (W)", "Pemandangan teluk (S/D)"), 1, 0),
            CardMeta("Kartu VIII", "Multicolor Pastel", "Responsivitas emosional & adaptasi afektif", listOf("Dua binatang memanjat (D1)", "Pohon / kristal es (D3)", "Kerangka dada (D)", "Bunga mekar (W)"), 4, 0),
            CardMeta("Kartu IX", "Multicolor Difus", "Toleransi ambiguitas emosional & stres", listOf("Dua sosok penyihir / manusia (D3)", "Rusa / kijang (D)", "Asap / semburan lava (W/D)", "Bunga lily (D)"), 5, 0),
            CardMeta("Kartu X", "Multicolor Terpecah", "Kapasitas sintesis kognitif & integrasi", listOf("Kepiting / laba-laba biru (D1)", "Ulat hijau (D4)", "Dua kuda laut (D)", "Telinga kelinci (D)"), 4, 0)
        )
    }

    val currentMeta = cardMetas[currentCard]
    val currentLoc = locations[currentCard] ?: 0
    val currentDet = determinants[currentCard] ?: currentMeta.defaultDet
    val currentFq = formQualities[currentCard] ?: 1
    val currentPop = populars[currentCard] ?: (currentCard in listOf(0, 1, 2, 4, 7))
    val currentCnt = contents[currentCard] ?: 0
    val currentText = associationTexts[currentCard] ?: ""

    fun recordCardAnswer(
        loc: Int = currentLoc,
        det: Int = currentDet,
        fq: Int = currentFq,
        pop: Boolean = currentPop,
        cnt: Int = currentCnt,
        text: String = currentText
    ) {
        locations[currentCard] = loc
        determinants[currentCard] = det
        formQualities[currentCard] = fq
        populars[currentCard] = pop
        contents[currentCard] = cnt
        associationTexts[currentCard] = text
        answered[currentCard] = true
    }

    val answeredTotal = (0 until 10).count { answered[it] == true }
    val allCompleted = answeredTotal >= 10

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            // Header: Title & Progress Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tes Rorschach (Exner CS & R-PAS)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${currentMeta.roman}: ${currentMeta.plateType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (allCompleted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "Kemajuan: $answeredTotal / 10",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (allCompleted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Card Navigation Chips (I - X)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(10) { idx ->
                    val isCur = idx == currentCard
                    val isDone = answered[idx] == true
                    Surface(
                        onClick = { currentCard = idx },
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            isCur -> MaterialTheme.colorScheme.primary
                            isDone -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.heightIn(min = 36.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cardMetas[idx].roman.replace("Kartu ", ""),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isCur -> MaterialTheme.colorScheme.onPrimary
                                    isDone -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (isDone && !isCur) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // High-Resolution Plate Canvas Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFFFAFAFA))
                    .border(BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFE0E0E0)), RoundedCornerShape(12.dp))
            ) {
                val bmp = cardBitmaps.getOrNull(currentCard)
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "Plat Rorschach ${currentMeta.roman}",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Memuat plat gambar...", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Zoom Button in top right
                IconButton(
                    onClick = { showZoomDialog = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = "Perbesar Plat",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Stimulus Theme Bar at bottom
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    Text(
                        text = "Stimulus: ${currentMeta.stimulusTheme}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1
                    )
                }
            }

            // Phase 1: Free Association Section
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                Text(
                    text = "Fase 1: Asosiasi Bebas (Apa yang Anda lihat?)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    currentMeta.suggestions.forEach { sugg ->
                        val isSelected = currentText.contains(sugg, ignoreCase = true)
                        Surface(
                            onClick = {
                                val newLoc = when {
                                    sugg.contains("(W)") -> 0
                                    sugg.contains("(D)") || sugg.contains("(D1)") || sugg.contains("(D3)") || sugg.contains("(D4)") -> 1
                                    sugg.contains("(S)") -> 3
                                    else -> currentLoc
                                }
                                val newDet = when {
                                    sugg.contains("manusia", ignoreCase = true) || sugg.contains("wanita", ignoreCase = true) -> 1 // M
                                    sugg.contains("beruang", ignoreCase = true) || sugg.contains("binatang memanjat", ignoreCase = true) -> 2 // FM
                                    sugg.contains("kulit binatang", ignoreCase = true) -> 8 // T (texture)
                                    sugg.contains("merah", ignoreCase = true) || sugg.contains("biru", ignoreCase = true) -> 4 // FC
                                    else -> currentMeta.defaultDet
                                }
                                val isPop = sugg.contains("Kelelawar", ignoreCase = true) ||
                                    sugg.contains("Kupu-kupu", ignoreCase = true) ||
                                    sugg.contains("manusia", ignoreCase = true) ||
                                    sugg.contains("beruang", ignoreCase = true) ||
                                    sugg.contains("kulit binatang", ignoreCase = true) ||
                                    sugg.contains("binatang memanjat", ignoreCase = true) ||
                                    sugg.contains("kepiting", ignoreCase = true)
                                val newCnt = when {
                                    sugg.contains("manusia", ignoreCase = true) || sugg.contains("wanita", ignoreCase = true) -> 0 // H
                                    sugg.contains("kelelawar", ignoreCase = true) || sugg.contains("kupu", ignoreCase = true) ||
                                        sugg.contains("anjing", ignoreCase = true) || sugg.contains("binatang", ignoreCase = true) ||
                                        sugg.contains("kepiting", ignoreCase = true) -> 2 // A
                                    sugg.contains("tulang", ignoreCase = true) || sugg.contains("panggul", ignoreCase = true) -> 3 // An
                                    else -> 4
                                }
                                recordCardAnswer(
                                    loc = newLoc,
                                    det = newDet,
                                    pop = isPop,
                                    cnt = newCnt,
                                    text = sugg
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Text(
                                text = sugg,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                androidx.compose.material3.OutlinedTextField(
                    value = currentText,
                    onValueChange = { newTxt ->
                        recordCardAnswer(text = newTxt)
                    },
                    label = { Text("Deskripsi persepsi / asosiasi bebas Anda") },
                    placeholder = { Text("Contoh: Dua figur manusia sedang berdansa...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp)
                )
            }

            // Phase 2: Inquiry / Clinical Coding Helper
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                Text(
                    text = "Fase 2: Bantuan Pengkodean Klinis (Exner Inquiry)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                // Sub-section: Lokasi (W, D, Dd, S)
                Text("Lokasi (Area Bercak):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("W (Whole)", "D (Detail)", "Dd (Unusual)", "S (Space)").forEachIndexed { idx, lbl ->
                        val sel = currentLoc == idx
                        Surface(
                            onClick = { recordCardAnswer(loc = idx) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = lbl,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Sub-section: Faktor Penentu (Determinant)
                Text("Faktor Penentu Utama (Determinant):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                val detOptions = listOf(
                    0 to "F (Bentuk)",
                    1 to "M (Gerak Manusia)",
                    2 to "FM (Gerak Hewan)",
                    3 to "m (Stres/Gaya Luar)",
                    4 to "FC (Warna Matang)",
                    5 to "CF (Warna Spontan)",
                    6 to "C (Warna Murni)",
                    7 to "C' (Kelam/Disforia)",
                    8 to "T (Tekstur Raba)",
                    9 to "V (Kedalaman Vista)",
                    10 to "Y (Bayangan Difus)"
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    detOptions.forEach { (code, label) ->
                        val sel = currentDet == code
                        Surface(
                            onClick = { recordCardAnswer(det = code) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                color = if (sel) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Sub-section: Form Quality (FQ) & Popular (P)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Kualitas Bentuk (FQ):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("FQ+", "FQo", "FQu", "FQ-").forEachIndexed { idx, label ->
                                val sel = currentFq == idx
                                Surface(
                                    onClick = { recordCardAnswer(fq = idx) },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    border = if (sel) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(0.7f)) {
                        Text("Respons Populer:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Surface(
                            onClick = { recordCardAnswer(pop = !currentPop) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (currentPop) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (currentPop) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (currentPop) "Populer (P)" else "Bukan P",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (currentPop) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Compact))

            // Navigation Row: Prev / Next / Submit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { if (currentCard > 0) currentCard-- },
                    enabled = currentCard > 0,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sebelumnya")
                }

                if (currentCard < 9) {
                    Button(
                        onClick = {
                            recordCardAnswer() // ensure marked
                            currentCard++
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Berikutnya")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Button(
                        onClick = {
                            recordCardAnswer()
                            val encodedAnswers = (0 until 10).associateWith { idx ->
                                val loc = locations[idx] ?: 0
                                val det = determinants[idx] ?: cardMetas[idx].defaultDet
                                val fq = formQualities[idx] ?: 1
                                val pop = if (populars[idx] == true) 1 else 0
                                val cnt = contents[idx] ?: 0
                                (loc * 100000) + (det * 1000) + (fq * 100) + (pop * 10) + cnt
                            }
                            viewModel.submitRorschachAll(encodedAnswers)
                        },
                        enabled = allCompleted,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Hitung Psikogram", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // High-Resolution Zoom Dialog
    if (showZoomDialog) {
        AlertDialog(
            onDismissRequest = { showZoomDialog = false },
            title = {
                Text(
                    text = "${currentMeta.roman} (Bercak Pembesar)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.ui.graphics.Color(0xFFFAFAFA))
                            .border(BorderStroke(1.dp, androidx.compose.ui.graphics.Color.LightGray), RoundedCornerShape(8.dp))
                    ) {
                        cardBitmaps.getOrNull(currentCard)?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = "Plat Gambar ${currentMeta.roman}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            )
                        }
                    }
                    Text(
                        text = "Amati detail kontur, bayangan, ruang putih, dan gradasi warna untuk menentukan area persepsi Anda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showZoomDialog = false }) {
                    Text("Tutup")
                }
            }
        )
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionJumpDialog(total: Int, current: Int, isAnswered: (Int) -> Boolean, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Pilih Nomor Butir (1–$total)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (i in 0 until total) {
                        val answered = isAnswered(i)
                        val isCurr = i == current
                        val containerColor = when {
                            isCurr -> MaterialTheme.colorScheme.primary
                            answered -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        val contentColor = when {
                            isCurr -> MaterialTheme.colorScheme.onPrimary
                            answered -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = containerColor,
                            modifier = Modifier
                                .size(40.dp)
                                .selectable(
                                    selected = isCurr,
                                    role = Role.Button,
                                    onClick = { onSelect(i) }
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${i + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isCurr || answered) FontWeight.Bold else FontWeight.Normal,
                                    color = contentColor
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}

@Composable
@Suppress("LongMethod")
private fun AssessmentResultContent(
    result: AssessmentResult,
    state: AssessmentUiState,
    viewModel: AssessmentViewModel,
    onDiscussWithAi: (String) -> Unit
) {
    var confirmDelete by remember(result.resultId) { mutableStateOf(false) }

    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AppPalette.Primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(AppSpacing.Compact))
                    Text(
                        "Hasil Asesmen Tersimpan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            val input = if (result.fromMarkedSheet) "Rekap Lembar Jawaban" else "${result.answered}/${result.itemCount} butir dijawab"
            Text(
                text = "${result.version} · $input",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(Modifier.height(AppSpacing.Medium))
            Divider()
            Spacer(Modifier.height(AppSpacing.Medium))

            Text("Rincian Skor & Skala", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(AppSpacing.Compact))
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                result.scores.forEach { score ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Compact)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    score.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                ) {
                                    Text(
                                        "${score.displayValue()} (${score.displayRange()})",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (score.meaning.isNotBlank()) {
                                Spacer(Modifier.height(AppSpacing.Micro))
                                Text(
                                    score.meaning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Large))
            Text("Interpretasi Klinis Baku", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(AppSpacing.Micro))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = result.interpretation,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(AppSpacing.Medium)
                )
            }

            if (result.version != state.instrument?.form?.version && result.sourceUrl.isNotBlank()) {
                Spacer(Modifier.height(AppSpacing.Compact))
                AssessmentSource(result.sourceTitle, result.sourceUrl)
            }

            Spacer(Modifier.height(AppSpacing.Large))
            Text(
                "Konsultasi & Ulasan AI",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(AppSpacing.Micro))
            Text(
                "Diskusikan hasil asesmen ini secara interaktif dengan konselor AI atau lihat ulasan kilat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(AppSpacing.Medium))
            Button(
                onClick = { onDiscussWithAi(result.toCounselorDiscussionPrompt()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(AppSpacing.Compact))
                Text(
                    "Bahas Hasil di AI Counselor (AI+)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(AppSpacing.Small))
            OutlinedButton(
                onClick = viewModel::explain,
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(AppSpacing.Compact))
                    Text("Menganalisis hasil...")
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(AppSpacing.Compact))
                    Text(if (state.resultExplanation == null) "Dapatkan Ringkasan Kilat AI" else "Perbarui Ringkasan Kilat AI")
                }
            }

            state.error?.let { err ->
                Spacer(Modifier.height(AppSpacing.Small))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(AppSpacing.Medium)) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            state.resultExplanation?.let { explanation ->
                Spacer(Modifier.height(AppSpacing.Small))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(AppSpacing.Medium)) {
                        Text(
                            text = "Ulasan Klinis AI Lokal",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(AppSpacing.Micro))
                        Text(
                            text = explanation,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Medium))
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !state.busy,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(AppSpacing.Micro))
                Text("Hapus Hasil Tersimpan", color = MaterialTheme.colorScheme.error)
            }
        }
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
                        viewModel.delete()
                    }
                ) {
                    Text("Hapus hasil", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun AssessmentSource(title: String, url: String) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { openCitation(context, url) },
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(AppSpacing.Micro))
                Text("Buka Sumber")
            }
        }
    }
}

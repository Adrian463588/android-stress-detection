package com.stressdetector.mobile.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stressdetector.mobile.domain.assessment.AssessmentForm
import com.stressdetector.mobile.domain.assessment.WarteggBoxDraft
import com.stressdetector.mobile.domain.assessment.WarteggNormalizedPoint
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.AppSpacing
import java.util.Locale
import kotlinx.coroutines.delay

private val WARTEGG_DRAFT_GSON = Gson()
private val WARTEGG_DRAFT_LIST_TYPE = object : TypeToken<List<WarteggBoxDraft>>() {}.type

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "MagicNumber", "UNUSED_PARAMETER")
internal fun WarteggAssessmentScreen(
    form: AssessmentForm,
    busy: Boolean,
    initialDrafts: List<WarteggBoxDraft> = emptyList(),
    onSaveDrafts: (List<WarteggBoxDraft>) -> Unit
) {
    val context = LocalContext.current
    var selectedBox by rememberSaveable { mutableIntStateOf(0) }
    var nextOrder by rememberSaveable { mutableIntStateOf(1) }
    var inputFilterName by rememberSaveable { mutableStateOf(DrawingInputFilter.AUTO.name) }
    var eraserActive by rememberSaveable { mutableStateOf(false) }
    var capabilities by remember { mutableStateOf(InputDeviceCapabilities()) }
    var savedDraftsJson by rememberSaveable { mutableStateOf("") }
    var showStimulusDialog by remember { mutableStateOf(false) }
    val redoStacks = remember { mutableStateMapOf<Int, List<List<WarteggNormalizedPoint>>>() }

    val restoredSeed = remember(savedDraftsJson, initialDrafts) {
        if (savedDraftsJson.isNotBlank()) {
            runCatching {
                WARTEGG_DRAFT_GSON.fromJson<List<WarteggBoxDraft>>(savedDraftsJson, WARTEGG_DRAFT_LIST_TYPE)
            }.getOrNull().orEmpty()
        } else {
            initialDrafts
        }
    }

    val drafts = remember {
        mutableStateMapOf<Int, WarteggBoxDraft>().apply {
            (0 until 8).forEach { idx ->
                val existing = restoredSeed.firstOrNull { it.boxNumber == idx + 1 }
                put(idx, existing ?: WarteggBoxDraft(boxNumber = idx + 1))
            }
            val maxExistingOrder = values.mapNotNull { it.drawOrder }.maxOrNull() ?: 0
            nextOrder = maxOf(nextOrder, maxExistingOrder + 1)
        }
    }
    fun updateBoxDraft(idx: Int, updated: WarteggBoxDraft, persistJson: Boolean = true) {
        drafts[idx] = updated
        if (persistJson) {
            val snapshot = (0 until 8).map { i -> drafts[i] ?: WarteggBoxDraft(boxNumber = i + 1) }
            savedDraftsJson = WARTEGG_DRAFT_GSON.toJson(snapshot)
        }
    }
    val liveStroke = remember { mutableStateListOf<WarteggNormalizedPoint>() }

    val stimulusBitmaps = remember {
        (1..8).map { boxNum ->
            runCatching {
                context.assets.open("ai/psychometrics/images/wartegg/box_$boxNum.png").use {
                    BitmapFactory.decodeStream(it)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    LaunchedEffect(selectedBox) {
        while (true) {
            delay(1_000L)
            val current = drafts[selectedBox] ?: WarteggBoxDraft(boxNumber = selectedBox + 1)
            val nextDuration = current.durationSeconds + 1
            updateBoxDraft(
                selectedBox,
                current.copy(durationSeconds = nextDuration),
                persistJson = nextDuration % 5 == 0
            )
        }
    }

    val activeDraft = drafts[selectedBox] ?: WarteggBoxDraft(boxNumber = selectedBox + 1)
    val completedCount = (0 until 8).count { idx -> drafts[idx]?.hasContent() == true }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wartegg · Pelaksanaan Asesmen",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Kotak ${selectedBox + 1} dari 8 · ${capabilities.badgeText()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "$completedCount / 8",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppPalette.Primary
                )
            }

            LinearProgressIndicator(
                progress = completedCount / 8f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            // Responsive 4x2 box selector grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0 until 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (col in 0 until 4) {
                            val idx = row * 4 + col
                            val boxDraft = drafts[idx] ?: WarteggBoxDraft(boxNumber = idx + 1)
                            val isSelected = selectedBox == idx
                            val hasContent = boxDraft.hasContent()
                            Surface(
                                selected = isSelected,
                                onClick = { selectedBox = idx },
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    isSelected -> AppPalette.Primary.copy(alpha = 0.22f)
                                    hasContent -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) AppPalette.Primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .semantics {
                                        contentDescription = "Kotak ${idx + 1}" +
                                            if (hasContent) ", sudah terisi" else ", belum diisi"
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val sub = boxDraft.drawOrder?.let { "#$it" }
                                        ?: if (hasContent) "✓" else "·"
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hasContent) AppPalette.Primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Active Box Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kotak ${selectedBox + 1}" +
                        (activeDraft.drawOrder?.let { " · Urutan #$it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                val mins = activeDraft.durationSeconds / 60
                val secs = activeDraft.durationSeconds % 60
                Text(
                    text = String.format(Locale.US, "%02d:%02d", mins, secs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val activeRedo = redoStacks[selectedBox].orEmpty()
            val inputFilter = DrawingInputFilter.valueOf(inputFilterName)

            // Square Normalized Drawing Canvas (stimulus layer separated from user strokes + MotionEvent stylus capture)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .captureProjectiveMotionStrokes(
                        key = selectedBox,
                        liveStroke = liveStroke,
                        inputFilter = inputFilter,
                        eraserActive = eraserActive,
                        onCapabilitiesDetected = { capabilities = it },
                        onStylusDetected = {},
                        onEraseAt = { nx, ny ->
                            val current = drafts[selectedBox] ?: return@captureProjectiveMotionStrokes
                            val filtered = removeHitStrokes(current.strokes, nx, ny)
                            if (filtered.size != current.strokes.size) {
                                val removedCount = current.strokes.size - filtered.size
                                updateBoxDraft(
                                    selectedBox,
                                    current.copy(strokes = filtered, revisionCount = current.revisionCount + removedCount)
                                )
                            }
                        },
                        onStrokeStarted = {
                            if ((drafts[selectedBox]?.drawOrder) == null) {
                                val current = drafts[selectedBox] ?: WarteggBoxDraft(boxNumber = selectedBox + 1)
                                updateBoxDraft(selectedBox, current.copy(drawOrder = nextOrder))
                                nextOrder++
                            }
                        },
                        onStrokeFinished = { stroke ->
                            val current = drafts[selectedBox] ?: WarteggBoxDraft(boxNumber = selectedBox + 1)
                            redoStacks[selectedBox] = emptyList()
                            updateBoxDraft(
                                selectedBox,
                                current.copy(strokes = current.strokes + listOf(stroke))
                            )
                        }
                    )
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Kanvas gambar Kotak ${selectedBox + 1}" }
                ) {
                    stimulusBitmaps.getOrNull(selectedBox)?.let { bmp ->
                        drawImage(
                            image = bmp,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            alpha = 0.9f
                        )
                    }
                    activeDraft.strokes.forEach { stroke ->
                        drawNormalizedStroke(stroke, size.width, size.height)
                    }
                    if (liveStroke.size > 1) {
                        drawNormalizedStroke(liveStroke, size.width, size.height)
                    }
                }
            }

            ProjectiveCanvasToolbar(
                inputFilter = inputFilter,
                onInputFilterChange = { inputFilterName = it.name },
                eraserActive = eraserActive,
                onEraserToggle = { eraserActive = it },
                canUndo = activeDraft.strokes.isNotEmpty(),
                canRedo = activeRedo.isNotEmpty(),
                canClear = activeDraft.strokes.isNotEmpty(),
                onUndo = {
                    val current = drafts[selectedBox] ?: return@ProjectiveCanvasToolbar
                    val popped = current.strokes.lastOrNull() ?: return@ProjectiveCanvasToolbar
                    redoStacks[selectedBox] = activeRedo + listOf(popped)
                    updateBoxDraft(
                        selectedBox,
                        current.copy(strokes = current.strokes.dropLast(1), revisionCount = current.revisionCount + 1)
                    )
                },
                onRedo = {
                    val current = drafts[selectedBox] ?: return@ProjectiveCanvasToolbar
                    val restored = activeRedo.lastOrNull() ?: return@ProjectiveCanvasToolbar
                    redoStacks[selectedBox] = activeRedo.dropLast(1)
                    updateBoxDraft(selectedBox, current.copy(strokes = current.strokes + listOf(restored)))
                },
                onClear = {
                    val current = drafts[selectedBox] ?: return@ProjectiveCanvasToolbar
                    redoStacks[selectedBox] = emptyList()
                    updateBoxDraft(
                        selectedBox,
                        current.copy(strokes = emptyList(), revisionCount = current.revisionCount + current.strokes.size)
                    )
                },
                extraActionLabel = "Stimulus",
                onExtraAction = { showStimulusDialog = true }
            )

            OutlinedTextField(
                value = activeDraft.title,
                onValueChange = { newTitle ->
                    val current = drafts[selectedBox] ?: WarteggBoxDraft(boxNumber = selectedBox + 1)
                    updateBoxDraft(selectedBox, current.copy(title = newTitle))
                },
                label = { Text("Judul atau keterangan gambar") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 2x2 Independent Evaluation Grid: Valence (+ / -) and Difficulty (M / S)
            Text(
                text = "Evaluasi subjektif",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WarteggEvalChip(
                        label = "Disukai (+)",
                        selected = activeDraft.valence == "+",
                        onClick = {
                            val current = drafts[selectedBox] ?: WarteggBoxDraft(boxNumber = selectedBox + 1)
                            updateBoxDraft(selectedBox, current.copy(valence = if (current.valence == "+") null else "+"))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    WarteggEvalChip(
                        label = "Kurang disukai (-)",
                        selected = activeDraft.valence == "-",
                        onClick = {
                            val current = drafts[selectedBox] ?: WarteggBoxDraft(boxNumber = selectedBox + 1)
                            updateBoxDraft(selectedBox, current.copy(valence = if (current.valence == "-") null else "-"))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WarteggEvalChip(
                        label = "Mudah (M)",
                        selected = activeDraft.difficulty == "M",
                        onClick = {
                            val current = drafts[selectedBox] ?: WarteggBoxDraft(boxNumber = selectedBox + 1)
                            updateBoxDraft(selectedBox, current.copy(difficulty = if (current.difficulty == "M") null else "M"))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    WarteggEvalChip(
                        label = "Sulit (S)",
                        selected = activeDraft.difficulty == "S",
                        onClick = {
                            val current = drafts[selectedBox] ?: WarteggBoxDraft(boxNumber = selectedBox + 1)
                            updateBoxDraft(selectedBox, current.copy(difficulty = if (current.difficulty == "S") null else "S"))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Box navigation & primary submit action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { if (selectedBox > 0) selectedBox-- },
                    enabled = selectedBox > 0,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sebelumnya", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = { if (selectedBox < 7) selectedBox++ },
                    enabled = selectedBox < 7,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Berikutnya", maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }

            Button(
                onClick = {
                    val orderedList = (0 until 8).map { idx -> drafts[idx] ?: WarteggBoxDraft(boxNumber = idx + 1) }
                    onSaveDrafts(orderedList)
                },
                enabled = completedCount >= 1 && !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (completedCount == 8) {
                        "Simpan & Tinjau Hasil (8/8)"
                    } else {
                        "Simpan & Tinjau Hasil ($completedCount/8)"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showStimulusDialog) {
        AlertDialog(
            onDismissRequest = { showStimulusDialog = false },
            title = { Text("Stimulus Asli Kotak ${selectedBox + 1}") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    stimulusBitmaps.getOrNull(selectedBox)?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = "Stimulus Kotak ${selectedBox + 1}",
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        )
                    } ?: Text("Gambar stimulus tidak ditemukan.", color = Color.DarkGray)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showStimulusDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Tutup")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
private fun WarteggEvalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AppPalette.Primary.copy(alpha = 0.22f),
            selectedLabelColor = AppPalette.Primary
        ),
        modifier = modifier.heightIn(min = 48.dp)
    )
}

@Suppress("MagicNumber")
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNormalizedStroke(
    points: List<WarteggNormalizedPoint>,
    canvasWidth: Float,
    canvasHeight: Float
) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x * canvasWidth, points.first().y * canvasHeight)
        points.drop(1).forEach { pt ->
            lineTo(pt.x * canvasWidth, pt.y * canvasHeight)
        }
    }
    drawPath(
        path = path,
        color = Color(0xFF0F172A),
        style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

package com.stressdetector.mobile.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.stressdetector.mobile.domain.assessment.ArmyAlphaItemDrawing
import com.stressdetector.mobile.domain.assessment.ArmyAlphaItemStatus
import com.stressdetector.mobile.domain.assessment.ArmyAlphaPoint
import com.stressdetector.mobile.domain.assessment.ArmyAlphaRecognitionEngine
import com.stressdetector.mobile.domain.assessment.ArmyAlphaSessionSummary
import com.stressdetector.mobile.domain.assessment.AssessmentForm
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.viewmodels.AssessmentUiState
import com.stressdetector.mobile.ui.viewmodels.AssessmentViewModel
import java.util.Locale
import kotlinx.coroutines.delay

enum class ArmyAlphaInputMode(val label: String) {
    HYBRID("Jari & Stylus"),
    STYLUS_ONLY("Hanya Stylus (S Pen)"),
    FINGER_ONLY("Hanya Jari")
}

private data class StimulusPlacement(
    val scale: Float,
    val dstWidth: Float,
    val dstHeight: Float,
    val left: Float,
    val top: Float
)

private fun calculateStimulusPlacement(
    bmpWidth: Int,
    bmpHeight: Int,
    canvasWidth: Int,
    canvasHeight: Int
): StimulusPlacement {
    if (bmpWidth <= 0 || bmpHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
        return StimulusPlacement(1f, canvasWidth.toFloat(), canvasHeight.toFloat(), 0f, 0f)
    }
    val srcW = bmpWidth.toFloat()
    val srcH = bmpHeight.toFloat()
    val scale = minOf(canvasWidth.toFloat() / srcW, canvasHeight.toFloat() / srcH)
    val dstW = srcW * scale
    val dstH = srcH * scale
    val left = (canvasWidth.toFloat() - dstW) / 2f
    val top = (canvasHeight.toFloat() - dstH) / 2f
    return StimulusPlacement(scale, dstW, dstH, left, top)
}

private fun buildSessionDrawings(
    totalItems: Int,
    stimulusBitmaps: List<androidx.compose.ui.graphics.ImageBitmap?>,
    strokesMap: Map<Int, List<List<ArmyAlphaPoint>>>,
    canvasSize: IntSize
): Map<Int, ArmyAlphaItemDrawing> {
    return (0 until totalItems).associateWith { idx ->
        val bmp = stimulusBitmaps.getOrNull(idx)
        val p = if (bmp != null) {
            calculateStimulusPlacement(bmp.width, bmp.height, canvasSize.width, canvasSize.height)
        } else {
            StimulusPlacement(1f, canvasSize.width.toFloat(), canvasSize.height.toFloat(), 0f, 0f)
        }
        val rawStrokes = strokesMap[idx] ?: emptyList()
        val mappedStrokes = rawStrokes.map { stroke ->
            stroke.map { pt ->
                ArmyAlphaPoint(
                    x = pt.x - p.left,
                    y = pt.y - p.top,
                    timeMillis = pt.timeMillis,
                    pressure = pt.pressure,
                    isStylus = pt.isStylus
                )
            }
        }
        ArmyAlphaItemDrawing(
            itemIndex = idx,
            strokes = mappedStrokes,
            canvasWidth = p.dstWidth.coerceAtLeast(1f),
            canvasHeight = p.dstHeight.coerceAtLeast(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun ArmyAlphaDrawingScreen(
    state: AssessmentUiState,
    form: AssessmentForm,
    viewModel: AssessmentViewModel
) {
    val context = LocalContext.current
    var selectedItem by remember { mutableIntStateOf(0) }
    var inputMode by remember { mutableStateOf(ArmyAlphaInputMode.HYBRID) }
    var isStylusDetected by remember { mutableStateOf(false) }
    var isInstructionExpanded by remember { mutableStateOf(false) }

    // Map itemIndex -> List<Stroke> di mana Stroke adalah List<ArmyAlphaPoint>
    val strokesMap = remember { mutableStateMapOf<Int, MutableList<List<ArmyAlphaPoint>>>() }
    val currentStroke = remember { mutableStateListOf<ArmyAlphaPoint>() }

    var canvasSize by remember { mutableStateOf(IntSize(1000, 600)) }
    var showStimulusZoom by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var sessionSummary by remember { mutableStateOf<ArmyAlphaSessionSummary?>(null) }

    // Stepper auto-scroll
    val stepperListState = rememberLazyListState()
    LaunchedEffect(selectedItem) {
        stepperListState.animateScrollToItem(selectedItem)
        isInstructionExpanded = false // Reset expansion on item change
    }

    // Timer per butir (detik)
    var itemSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedItem) {
        itemSeconds = 0
        while (true) {
            delay(1000L)
            itemSeconds++
        }
    }

    // Text to Speech
    val speakInstruction = rememberTtsSpeaker(context)

    // Load 12 stimulus bitmaps dari canonical assets
    val stimulusBitmaps = remember {
        (1..12).map { itemNum ->
            try {
                context.assets.open("ai/psychometrics/images/army_alpha/item_$itemNum.png").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    val currentItemObj = form.items.getOrNull(selectedItem)
    val totalItems = form.items.size.coerceAtLeast(12)
    val currentItemStrokes = strokesMap[selectedItem] ?: emptyList()
    val answeredCount = (0 until totalItems).count { (strokesMap[it]?.size ?: 0) > 0 }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Bar: Title + Timer + S-Pen Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Army Alpha (Kanvas Digital)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Butir ${selectedItem + 1} dari $totalItems · $answeredCount terisi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isStylusDetected) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Draw, contentDescription = "S Pen", modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("S Pen", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Timer, contentDescription = "Waktu", modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = String.format(Locale.getDefault(), "%02d:%02d", itemSeconds / 60, itemSeconds % 60),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Progress bar
            LinearProgressIndicator(
                progress = (selectedItem + 1).toFloat() / totalItems.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Item Selector Chips (B1..B12) with Auto-scroll
            LazyRow(
                state = stepperListState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(totalItems) { idx ->
                    val isSelected = selectedItem == idx
                    val hasAnswer = (strokesMap[idx]?.size ?: 0) > 0
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedItem = idx },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "B${idx + 1}",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                                if (hasAnswer) {
                                    Spacer(Modifier.width(3.dp))
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Sudah dijawab",
                                        modifier = Modifier.size(12.dp),
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.heightIn(min = 36.dp)
                    )
                }
            }

            // Input Mode Segmented Control (Equal 50/50 weights, zero clipping)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = inputMode == ArmyAlphaInputMode.HYBRID,
                    onClick = { inputMode = ArmyAlphaInputMode.HYBRID },
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Gesture, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Jari & Stylus", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                )

                FilterChip(
                    selected = inputMode == ArmyAlphaInputMode.STYLUS_ONLY,
                    onClick = { inputMode = ArmyAlphaInputMode.STYLUS_ONLY },
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Hanya S Pen", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                )
            }

            // Instruction Card (Collapsible for long text, with TTS button)
            val fullInstruction = currentItemObj?.text ?: "Perhatikan lembar stimulus dan ikuti instruksi."
            val isLongInstruction = fullInstruction.length > 80

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Instruksi Butir ${selectedItem + 1}:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { speakInstruction(fullInstruction) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.heightIn(min = 32.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Dengarkan", modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Dengarkan", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            if (isLongInstruction) {
                                TextButton(
                                    onClick = { isInstructionExpanded = !isInstructionExpanded },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.heightIn(min = 32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isInstructionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (isInstructionExpanded) "Ciutkan" else "Detail",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isInstructionExpanded) "Tutup" else "Detail",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = fullInstruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (isInstructionExpanded || !isLongInstruction) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Drawing Canvas Box with Aspect Ratio Preserved Stimulus & Gesture Isolation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(165.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(12.dp)
                    )
                    .onSizeChanged { size ->
                        if (size.width > 0 && size.height > 0) {
                            canvasSize = size
                        }
                    }
                    .pointerInput(selectedItem, inputMode) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val isStylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                            if (isStylus) isStylusDetected = true

                            val allowed = when (inputMode) {
                                ArmyAlphaInputMode.HYBRID -> true
                                ArmyAlphaInputMode.STYLUS_ONLY -> isStylus
                                ArmyAlphaInputMode.FINGER_ONLY -> !isStylus
                            }

                            if (allowed) {
                                down.consume()
                                currentStroke.clear()
                                currentStroke.add(
                                    ArmyAlphaPoint(
                                        x = down.position.x,
                                        y = down.position.y,
                                        timeMillis = System.currentTimeMillis(),
                                        pressure = down.pressure,
                                        isStylus = isStylus
                                    )
                                )

                                val pointerId = down.id
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                    if (!change.pressed) {
                                        if (currentStroke.isNotEmpty()) {
                                            val list = strokesMap.getOrPut(selectedItem) { mutableListOf() }
                                            list.add(currentStroke.toList())
                                            currentStroke.clear()
                                        }
                                        break
                                    }

                                    change.consume()
                                    currentStroke.add(
                                        ArmyAlphaPoint(
                                            x = change.position.x,
                                            y = change.position.y,
                                            timeMillis = System.currentTimeMillis(),
                                            pressure = change.pressure,
                                            isStylus = isStylus
                                        )
                                    )
                                }
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Draw Stimulus with Authentically Preserved Aspect Ratio
                    stimulusBitmaps.getOrNull(selectedItem)?.let { bmp ->
                        val p = calculateStimulusPlacement(bmp.width, bmp.height, size.width.toInt(), size.height.toInt())
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(p.left.toInt(), p.top.toInt()),
                            dstSize = IntSize(p.dstWidth.toInt(), p.dstHeight.toInt()),
                            alpha = 0.92f
                        )
                    }

                    // Draw Existing User Strokes
                    val boxStrokes = strokesMap[selectedItem] ?: emptyList()
                    val strokeStyle = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val strokeColor = Color(0xFF0F172A) // Dark Navy ink

                    for (stroke in boxStrokes) {
                        if (stroke.size > 1) {
                            val path = Path()
                            path.moveTo(stroke[0].x, stroke[0].y)
                            for (i in 1 until stroke.size) {
                                path.lineTo(stroke[i].x, stroke[i].y)
                            }
                            drawPath(path = path, color = strokeColor, style = strokeStyle)
                        } else if (stroke.size == 1) {
                            drawCircle(color = strokeColor, radius = 3.5f, center = Offset(stroke[0].x, stroke[0].y))
                        }
                    }

                    // Draw Current Live Stroke
                    if (currentStroke.size > 1) {
                        val path = Path()
                        path.moveTo(currentStroke[0].x, currentStroke[0].y)
                        for (i in 1 until currentStroke.size) {
                            path.lineTo(currentStroke[i].x, currentStroke[i].y)
                        }
                        drawPath(path = path, color = strokeColor, style = strokeStyle)
                    }
                }
            }

            // Toolbar Canvas Actions: Undo, Clear, Stroke Count, Zoom
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val list = strokesMap[selectedItem]
                            if (!list.isNullOrEmpty()) {
                                list.removeAt(list.lastIndex)
                            }
                        },
                        enabled = currentItemStrokes.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Undo", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = { strokesMap[selectedItem]?.clear() },
                        enabled = currentItemStrokes.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Hapus", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Hapus", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (currentItemStrokes.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = "${currentItemStrokes.size} coretan",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (currentItemStrokes.isNotEmpty()) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    OutlinedButton(
                        onClick = { showStimulusZoom = true },
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Perbesar Stimulus", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Zoom", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Bottom Navigation Stepper (Equal 50/50 weights, minimum 48dp height)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { if (selectedItem > 0) selectedItem-- },
                    enabled = selectedItem > 0,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Sebelumnya", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sebelumnya", maxLines = 1, softWrap = false)
                }

                if (selectedItem < totalItems - 1) {
                    Button(
                        onClick = { selectedItem++ },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Berikutnya", maxLines = 1, softWrap = false)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Berikutnya", modifier = Modifier.size(16.dp))
                    }
                } else {
                    Button(
                        onClick = {
                            val drawings = buildSessionDrawings(totalItems, stimulusBitmaps, strokesMap, canvasSize)
                            sessionSummary = ArmyAlphaRecognitionEngine.evaluateSession(drawings)
                            showReviewDialog = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Psychology, contentDescription = "Periksa & Analisis", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Selesai & Analisis", maxLines = 1, softWrap = false)
                    }
                }
            }

            // Tombol Pintas Selesai Kapan Saja jika sudah terisi minimal 1 butir
            if (selectedItem < totalItems - 1 && answeredCount > 0) {
                TextButton(
                    onClick = {
                        val drawings = buildSessionDrawings(totalItems, stimulusBitmaps, strokesMap, canvasSize)
                        sessionSummary = ArmyAlphaRecognitionEngine.evaluateSession(drawings)
                        showReviewDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                ) {
                    Text("Selesaikan & Nilai Sekarang ($answeredCount/$totalItems terisi)", maxLines = 1, softWrap = false)
                }
            }
        }
    }

    // Modal Dialog Stimulus Zoom
    if (showStimulusZoom) {
        Dialog(onDismissRequest = { showStimulusZoom = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Medium)
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
                ) {
                    Text(
                        text = "Lembar Stimulus Asli · Butir ${selectedItem + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            stimulusBitmaps.getOrNull(selectedItem)?.let { bmp ->
                                val p = calculateStimulusPlacement(bmp.width, bmp.height, size.width.toInt(), size.height.toInt())
                                drawImage(
                                    image = bmp,
                                    dstOffset = IntOffset(p.left.toInt(), p.top.toInt()),
                                    dstSize = IntSize(p.dstWidth.toInt(), p.dstHeight.toInt()),
                                    alpha = 1.0f
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { showStimulusZoom = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Tutup")
                    }
                }
            }
        }
    }

    // Review & Transkripsi AI Dialog
    if (showReviewDialog && sessionSummary != null) {
        val summary = sessionSummary!!
        AlertDialog(
            onDismissRequest = { showReviewDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = "Analisis",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = "Hasil Pengenalan Coretan Army Alpha")
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Total Skor Benar: ${summary.totalCorrect} / 12",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Skala Weighted LPSP3 UI: ${summary.scale}/5 (${summary.scaleLabel})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Metode Masukan: ${summary.inputMethod}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "• Following Commands: ${summary.dirCorrect}/7 benar\n• Speed & Accuracy: ${summary.speedCorrect}/5 benar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = "Rincian Transkripsi AI Multimodal per Butir:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(summary.evaluations) { eval ->
                        val isCorrect = eval.status == ArmyAlphaItemStatus.CORRECT
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isCorrect) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            border = BorderStroke(
                                1.dp,
                                if (isCorrect) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Butir ${eval.itemNumber}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isCorrect) "BENAR (1)" else "SALAH (0)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    text = eval.transcription,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReviewDialog = false
                        // Encode answers: 0 = CORRECT, 1 = INCORRECT (sesuai contract enrichArmyAlpha)
                        val encodedAnswers = summary.evaluations.associate { eval ->
                            eval.itemIndex to (if (eval.status == ArmyAlphaItemStatus.CORRECT) 0 else 1)
                        }
                        viewModel.submitArmyAlphaAll(encodedAnswers)
                    }
                ) {
                    Text("Simpan & Bahas dengan AI")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showReviewDialog = false }) {
                    Text("Kembali Mengoreksi")
                }
            }
        )
    }
}

@Composable
private fun rememberTtsSpeaker(context: Context): (String) -> Unit {
    var ttsInstance by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        val tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInstance?.language = Locale("id", "ID")
            }
        }
        ttsInstance = tts
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    return { text ->
        ttsInstance?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "army_alpha_item_tts")
    }
}

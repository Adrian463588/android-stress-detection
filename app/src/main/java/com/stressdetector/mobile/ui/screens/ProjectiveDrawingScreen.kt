package com.stressdetector.mobile.ui.screens

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.ProjectivePanelDraft
import com.stressdetector.mobile.domain.assessment.WarteggNormalizedPoint
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.AppSpacing
import java.util.Locale
import kotlin.math.hypot
import kotlinx.coroutines.delay

internal enum class DrawingInputFilter(val label: String) {
    AUTO("Auto"),
    FINGER("Jari"),
    STYLUS("Stylus")
}

internal data class InputDeviceCapabilities(
    val supportsPressure: Boolean = false,
    val supportsTilt: Boolean = false,
    val supportsOrientation: Boolean = false,
    val supportsHover: Boolean = false,
    val activeToolLabel: String = "Sentuh / Stylus"
) {
    fun badgeText(): String {
        val axes = buildList {
            if (supportsPressure) add("Pressure")
            if (supportsTilt) add("Tilt")
            if (supportsOrientation) add("Orientasi")
            if (supportsHover) add("Hover")
        }
        return if (axes.isEmpty()) {
            "$activeToolLabel · Sensor tekanan tidak tersedia"
        } else {
            "$activeToolLabel · ${axes.joinToString(" + ")}"
        }
    }
}

private val PANEL_DRAFT_GSON = Gson()
private val PANEL_DRAFT_LIST_TYPE = object : TypeToken<List<ProjectivePanelDraft>>() {}.type

/**
 * Shared MotionEvent + Compose pointer capture for projective drawing canvases.
 * Captures historical samples, stylus tool type, hardware pressure (null if unsupported), tilt, orientation, and timestamps.
 */
@Suppress("CyclomaticComplexMethod", "LongParameterList")
internal fun Modifier.captureProjectiveMotionStrokes(
    key: Any?,
    liveStroke: MutableList<WarteggNormalizedPoint>,
    inputFilter: DrawingInputFilter = DrawingInputFilter.AUTO,
    eraserActive: Boolean = false,
    onCapabilitiesDetected: (InputDeviceCapabilities) -> Unit = {},
    onStylusDetected: () -> Unit,
    onEraseAt: ((Float, Float) -> Unit)? = null,
    onStrokeStarted: () -> Unit,
    onStrokeFinished: (List<WarteggNormalizedPoint>) -> Unit
): Modifier = pointerInput(key, inputFilter, eraserActive) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val w = size.width.toFloat().coerceAtLeast(1f)
        val h = size.height.toFloat().coerceAtLeast(1f)
        val initialEvent = currentEvent
        val motion0 = initialEvent.motionEvent
        val idx0 = motion0?.findPointerIndex(down.id.value.toInt())?.takeIf { it >= 0 } ?: 0
        val toolType0 = motion0?.getToolType(idx0)
        val isStylusOrEraser = down.type == PointerType.Stylus ||
            down.type == PointerType.Eraser ||
            toolType0 == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType0 == MotionEvent.TOOL_TYPE_ERASER

        if (inputFilter == DrawingInputFilter.STYLUS && !isStylusOrEraser) return@awaitEachGesture
        if (inputFilter == DrawingInputFilter.FINGER && isStylusOrEraser) return@awaitEachGesture

        down.consume()
        detectDeviceCapabilities(motion0, isStylusOrEraser)?.let(onCapabilitiesDetected)
        val hardwareEraser = down.type == PointerType.Eraser || toolType0 == MotionEvent.TOOL_TYPE_ERASER
        val erasing = eraserActive || hardwareEraser

        liveStroke.clear()
        val downPoints = extractPointsWithHistory(down, initialEvent, w, h)
        if (downPoints.any { it.isStylus }) onStylusDetected()
        if (erasing && onEraseAt != null) {
            downPoints.lastOrNull()?.let { onEraseAt(it.x, it.y) }
        } else {
            liveStroke.addAll(downPoints)
            onStrokeStarted()
        }

        val pointerId = down.id
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
                if (!erasing && liveStroke.isNotEmpty()) {
                    onStrokeFinished(liveStroke.toList())
                }
                liveStroke.clear()
                break
            }
            change.consume()
            val pts = extractPointsWithHistory(change, event, w, h)
            if (pts.any { it.isStylus }) onStylusDetected()
            if (erasing && onEraseAt != null) {
                pts.forEach { onEraseAt(it.x, it.y) }
            } else {
                liveStroke.addAll(pts)
            }
        }
    }
}

private fun detectDeviceCapabilities(
    motion: MotionEvent?,
    isStylus: Boolean
): InputDeviceCapabilities? {
    val dev = motion?.device ?: return null
    val pRange = dev.getMotionRange(MotionEvent.AXIS_PRESSURE, InputDevice.SOURCE_STYLUS)
        ?: dev.getMotionRange(MotionEvent.AXIS_PRESSURE)
    val tRange = dev.getMotionRange(MotionEvent.AXIS_TILT)
    val oRange = dev.getMotionRange(MotionEvent.AXIS_ORIENTATION)
    val dRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE)
    return InputDeviceCapabilities(
        supportsPressure = isStylus && pRange != null,
        supportsTilt = isStylus && tRange != null,
        supportsOrientation = isStylus && oRange != null,
        supportsHover = isStylus && dRange != null,
        activeToolLabel = if (isStylus) "Stylus Aktif" else "Sentuh / Jari"
    )
}

private fun extractPointsWithHistory(
    change: PointerInputChange,
    event: PointerEvent,
    width: Float,
    height: Float
): List<WarteggNormalizedPoint> {
    val motion = event.motionEvent
    if (motion == null) {
        val isStylus = change.type == PointerType.Stylus || change.type == PointerType.Eraser
        return listOf(
            WarteggNormalizedPoint(
                x = (change.position.x / width).coerceIn(0f, 1f),
                y = (change.position.y / height).coerceIn(0f, 1f),
                pressure = null,
                timeMillis = change.uptimeMillis,
                isStylus = isStylus,
                toolType = if (isStylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
            )
        )
    }

    val pointerIndex = motion.findPointerIndex(change.id.value.toInt()).takeIf { it >= 0 } ?: 0
    val toolType = motion.getToolType(pointerIndex)
    val isStylus = change.type == PointerType.Stylus ||
        change.type == PointerType.Eraser ||
        toolType == MotionEvent.TOOL_TYPE_STYLUS ||
        toolType == MotionEvent.TOOL_TYPE_ERASER
    val dev = motion.device
    val hasPressureAxis = isStylus && (dev?.getMotionRange(MotionEvent.AXIS_PRESSURE) != null)
    val hasTiltAxis = isStylus && (dev?.getMotionRange(MotionEvent.AXIS_TILT) != null)
    val hasOrientAxis = isStylus && (dev?.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null)

    val out = mutableListOf<WarteggNormalizedPoint>()
    if (motion.actionMasked == MotionEvent.ACTION_MOVE && motion.historySize > 0) {
        // MotionEvent coordinates are relative to AndroidComposeView root, whereas change.position is canvas-local.
        val offsetX = motion.getX(pointerIndex) - change.position.x
        val offsetY = motion.getY(pointerIndex) - change.position.y
        for (h in 0 until motion.historySize) {
            val hx = ((motion.getHistoricalX(pointerIndex, h) - offsetX) / width).coerceIn(0f, 1f)
            val hy = ((motion.getHistoricalY(pointerIndex, h) - offsetY) / height).coerceIn(0f, 1f)
            val hp = motion.getHistoricalPressure(pointerIndex, h).takeIf { hasPressureAxis && it.isFinite() && it > 0f }
            val ht = motion.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h)
                .takeIf { hasTiltAxis && it.isFinite() && it > 0f }
            val ho = motion.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, h)
                .takeIf { hasOrientAxis && it.isFinite() }
            out += WarteggNormalizedPoint(
                x = hx,
                y = hy,
                pressure = hp,
                timeMillis = motion.getHistoricalEventTime(h),
                isStylus = isStylus,
                tilt = ht,
                orientation = ho,
                toolType = toolType
            )
        }
    }
    val curPressure = motion.getPressure(pointerIndex).takeIf { hasPressureAxis && it.isFinite() && it > 0f }
    val curTilt = motion.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        .takeIf { hasTiltAxis && it.isFinite() && it > 0f }
    val curOrient = motion.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
        .takeIf { hasOrientAxis && it.isFinite() }
    out += WarteggNormalizedPoint(
        x = (change.position.x / width).coerceIn(0f, 1f),
        y = (change.position.y / height).coerceIn(0f, 1f),
        pressure = curPressure,
        timeMillis = motion.eventTime,
        isStylus = isStylus,
        tilt = curTilt,
        orientation = curOrient,
        toolType = toolType
    )
    return out
}

internal fun removeHitStrokes(
    strokes: List<List<WarteggNormalizedPoint>>,
    normX: Float,
    normY: Float,
    hitRadius: Float = 0.045f
): List<List<WarteggNormalizedPoint>> = strokes.filterNot { stroke ->
    stroke.any { pt -> hypot(pt.x - normX, pt.y - normY) <= hitRadius }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongParameterList", "MagicNumber")
internal fun ProjectiveCanvasToolbar(
    inputFilter: DrawingInputFilter,
    onInputFilterChange: (DrawingInputFilter) -> Unit,
    eraserActive: Boolean,
    onEraserToggle: (Boolean) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    canClear: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    extraActionLabel: String? = null,
    onExtraAction: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DrawingInputFilter.entries.forEach { mode ->
                FilterChip(
                    selected = inputFilter == mode,
                    onClick = { onInputFilterChange(mode) },
                    label = { Text(mode.label, maxLines = 1, softWrap = false) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppPalette.Primary.copy(alpha = 0.2f),
                        selectedLabelColor = AppPalette.Primary
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
            FilterChip(
                selected = eraserActive,
                onClick = { onEraserToggle(!eraserActive) },
                label = { Text(if (eraserActive) "Penghapus Aktif" else "Penghapus", maxLines = 1, softWrap = false) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppPalette.Warning.copy(alpha = 0.2f),
                    selectedLabelColor = AppPalette.Warning
                ),
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onUndo,
                enabled = canUndo,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Undo", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onRedo,
                enabled = canRedo,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Redo", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onClear,
                enabled = canClear,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Hapus", maxLines = 1, softWrap = false)
            }
            if (extraActionLabel != null && onExtraAction != null) {
                OutlinedButton(
                    onClick = onExtraAction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(extraActionLabel, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "MagicNumber", "CyclomaticComplexMethod")
internal fun ProjectiveDrawingScreen(
    instrument: AssessmentInstrument,
    busy: Boolean,
    onSavePanels: (List<ProjectivePanelDraft>) -> Unit
) {
    val panelLabels = remember(instrument.id) {
        when (instrument.id) {
            "htp" -> listOf("Rumah", "Pohon", "Orang")
            "dap" -> listOf("Figur Utama", "Figur Pembanding")
            "baum" -> listOf("Pohon")
            else -> instrument.form?.scales?.map { it.label }?.takeIf { it.isNotEmpty() } ?: listOf("Panel 1")
        }
    }
    var selectedPanel by rememberSaveable(instrument.id) { mutableIntStateOf(0) }
    var nextOrder by rememberSaveable(instrument.id) { mutableIntStateOf(1) }
    var inputFilterName by rememberSaveable(instrument.id) { mutableStateOf(DrawingInputFilter.AUTO.name) }
    var eraserActive by rememberSaveable(instrument.id) { mutableStateOf(false) }
    var stylusDetected by rememberSaveable(instrument.id) { mutableStateOf(false) }
    var capabilities by remember { mutableStateOf(InputDeviceCapabilities()) }
    var savedPanelsJson by rememberSaveable(instrument.id) { mutableStateOf("") }

    val restoredSeed = remember(savedPanelsJson) {
        if (savedPanelsJson.isNotBlank()) {
            runCatching {
                PANEL_DRAFT_GSON.fromJson<List<ProjectivePanelDraft>>(savedPanelsJson, PANEL_DRAFT_LIST_TYPE)
            }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
    }

    val panels = remember(instrument.id) {
        mutableStateMapOf<Int, ProjectivePanelDraft>().apply {
            panelLabels.forEachIndexed { idx, label ->
                val existing = restoredSeed.firstOrNull { it.panelIndex == idx }
                put(idx, existing ?: ProjectivePanelDraft(panelIndex = idx, panelLabel = label))
            }
            val maxExistingOrder = values.mapNotNull { it.drawOrder }.maxOrNull() ?: 0
            nextOrder = maxOf(nextOrder, maxExistingOrder + 1)
        }
    }
    val redoStacks = remember(instrument.id) {
        mutableStateMapOf<Int, List<List<WarteggNormalizedPoint>>>()
    }
    fun updatePanelDraft(idx: Int, updated: ProjectivePanelDraft, persistJson: Boolean = true) {
        panels[idx] = updated
        if (persistJson) {
            val snapshot = panelLabels.indices.map { i -> panels[i] ?: ProjectivePanelDraft(i, panelLabels[i]) }
            savedPanelsJson = PANEL_DRAFT_GSON.toJson(snapshot)
        }
    }
    val liveStroke = remember { mutableStateListOf<WarteggNormalizedPoint>() }

    LaunchedEffect(selectedPanel) {
        while (true) {
            delay(1_000L)
            val current = panels[selectedPanel] ?: ProjectivePanelDraft(selectedPanel, panelLabels[selectedPanel])
            val nextDur = current.durationSeconds + 1
            updatePanelDraft(selectedPanel, current.copy(durationSeconds = nextDur), persistJson = nextDur % 5 == 0)
        }
    }

    val active = panels[selectedPanel] ?: ProjectivePanelDraft(selectedPanel, panelLabels[selectedPanel])
    val activeRedo = redoStacks[selectedPanel].orEmpty()
    val completedCount = panelLabels.indices.count { panels[it]?.hasContent() == true }
    val inputFilter = DrawingInputFilter.valueOf(inputFilterName)

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
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${instrument.title} · Kanvas Proyektif",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = capabilities.badgeText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stylusDetected) AppPalette.Primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "$completedCount / ${panelLabels.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppPalette.Primary
                )
            }

            LinearProgressIndicator(
                progress = completedCount.toFloat() / panelLabels.size.coerceAtLeast(1),
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                panelLabels.forEachIndexed { idx, label ->
                    val draft = panels[idx]
                    val isSelected = selectedPanel == idx
                    val filled = draft?.hasContent() == true
                    Surface(
                        selected = isSelected,
                        onClick = { selectedPanel = idx },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) AppPalette.Primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) AppPalette.Primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = draft?.drawOrder?.let { "#$it" } ?: if (filled) "✓" else "Kosong",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (filled) AppPalette.Primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Panel: ${active.panelLabel}" + (active.drawOrder?.let { " · Urutan #$it" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format(Locale.US, "%02d:%02d", active.durationSeconds / 60, active.durationSeconds % 60),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .captureProjectiveMotionStrokes(
                        key = selectedPanel,
                        liveStroke = liveStroke,
                        inputFilter = inputFilter,
                        eraserActive = eraserActive,
                        onCapabilitiesDetected = { capabilities = it },
                        onStylusDetected = { stylusDetected = true },
                        onEraseAt = { nx, ny ->
                            val current = panels[selectedPanel] ?: return@captureProjectiveMotionStrokes
                            val filtered = removeHitStrokes(current.strokes, nx, ny)
                            if (filtered.size != current.strokes.size) {
                                val removedCount = current.strokes.size - filtered.size
                                updatePanelDraft(
                                    selectedPanel,
                                    current.copy(strokes = filtered, revisionCount = current.revisionCount + removedCount)
                                )
                            }
                        },
                        onStrokeStarted = {
                            if (panels[selectedPanel]?.drawOrder == null) {
                                val current = panels[selectedPanel] ?: ProjectivePanelDraft(selectedPanel, panelLabels[selectedPanel])
                                updatePanelDraft(selectedPanel, current.copy(drawOrder = nextOrder))
                                nextOrder++
                            }
                        },
                        onStrokeFinished = { completedStroke ->
                            val current = panels[selectedPanel] ?: ProjectivePanelDraft(selectedPanel, panelLabels[selectedPanel])
                            redoStacks[selectedPanel] = emptyList()
                            updatePanelDraft(selectedPanel, current.copy(strokes = current.strokes + listOf(completedStroke)))
                        }
                    )
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Kanvas gambar ${active.panelLabel}" }
                ) {
                    fun drawPts(pts: List<WarteggNormalizedPoint>) {
                        if (pts.size <= 1) return
                        val path = Path().apply {
                            moveTo(pts.first().x * size.width, pts.first().y * size.height)
                            pts.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                        }
                        val avgP = pts.mapNotNull { it.pressure }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                        val widthPx = if (avgP != null) (3f + avgP * 4f).coerceIn(2.5f, 9f) else 4.5f
                        drawPath(
                            path = path,
                            color = Color(0xFF0F172A),
                            style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                    active.strokes.forEach(::drawPts)
                    drawPts(liveStroke)
                }
            }

            ProjectiveCanvasToolbar(
                inputFilter = inputFilter,
                onInputFilterChange = { inputFilterName = it.name },
                eraserActive = eraserActive,
                onEraserToggle = { eraserActive = it },
                canUndo = active.strokes.isNotEmpty(),
                canRedo = activeRedo.isNotEmpty(),
                canClear = active.strokes.isNotEmpty(),
                onUndo = {
                    val current = panels[selectedPanel] ?: return@ProjectiveCanvasToolbar
                    val popped = current.strokes.lastOrNull() ?: return@ProjectiveCanvasToolbar
                    redoStacks[selectedPanel] = activeRedo + listOf(popped)
                    updatePanelDraft(
                        selectedPanel,
                        current.copy(strokes = current.strokes.dropLast(1), revisionCount = current.revisionCount + 1)
                    )
                },
                onRedo = {
                    val current = panels[selectedPanel] ?: return@ProjectiveCanvasToolbar
                    val restored = activeRedo.lastOrNull() ?: return@ProjectiveCanvasToolbar
                    redoStacks[selectedPanel] = activeRedo.dropLast(1)
                    updatePanelDraft(selectedPanel, current.copy(strokes = current.strokes + listOf(restored)))
                },
                onClear = {
                    val current = panels[selectedPanel] ?: return@ProjectiveCanvasToolbar
                    redoStacks[selectedPanel] = emptyList()
                    updatePanelDraft(
                        selectedPanel,
                        current.copy(strokes = emptyList(), revisionCount = current.revisionCount + current.strokes.size)
                    )
                }
            )

            OutlinedTextField(
                value = active.title,
                onValueChange = { text ->
                    val current = panels[selectedPanel] ?: ProjectivePanelDraft(selectedPanel, panelLabels[selectedPanel])
                    updatePanelDraft(selectedPanel, current.copy(title = text))
                },
                label = { Text("Judul / objek gambar (${active.panelLabel})") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = active.notes,
                onValueChange = { text ->
                    val current = panels[selectedPanel] ?: ProjectivePanelDraft(selectedPanel, panelLabels[selectedPanel])
                    updatePanelDraft(selectedPanel, current.copy(notes = text))
                },
                label = { Text("Asosiasi / catatan verbal pemeriksa (opsional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val ordered = panelLabels.indices.map { idx ->
                        panels[idx] ?: ProjectivePanelDraft(idx, panelLabels[idx])
                    }
                    onSavePanels(ordered)
                },
                enabled = completedCount > 0 && !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Simpan & Analisis Multimodal ($completedCount/${panelLabels.size})", fontWeight = FontWeight.Bold)
            }
        }
    }
}

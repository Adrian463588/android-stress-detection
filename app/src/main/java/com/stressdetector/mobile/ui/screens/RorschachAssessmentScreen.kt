package com.stressdetector.mobile.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.mutableStateMapOf
import com.stressdetector.mobile.domain.assessment.ProjectiveTelemetryExtractor
import com.stressdetector.mobile.domain.assessment.WarteggNormalizedPoint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stressdetector.mobile.domain.assessment.AssessmentForm
import com.stressdetector.mobile.domain.assessment.RorschachResponseDraft
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.AppSpacing
import java.util.Locale

private val RORSCHACH_GSON = Gson()
private val RORSCHACH_LIST_TYPE = object : TypeToken<List<RorschachResponseDraft>>() {}.type

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "MagicNumber", "UNUSED_PARAMETER")
internal fun RorschachAssessmentScreen(
    form: AssessmentForm,
    busy: Boolean,
    initialResponses: List<RorschachResponseDraft> = emptyList(),
    onSaveResponses: (List<RorschachResponseDraft>, String) -> Unit
) {
    val context = LocalContext.current
    var currentCard by rememberSaveable { mutableIntStateOf(0) }
    var selectedSystem by rememberSaveable { mutableStateOf("EXNER_CS") }
    var savedResponsesJson by rememberSaveable { mutableStateOf("") }
    var freeAssocInput by rememberSaveable { mutableStateOf("") }
    var inquiryInput by rememberSaveable { mutableStateOf("") }
    var showExaminerCoding by rememberSaveable { mutableStateOf(false) }
    var selectedLocation by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDet by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFq by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPopular by rememberSaveable { mutableStateOf(false) }
    var annotationEnabled by rememberSaveable { mutableStateOf(false) }
    val cardAnnotations = remember { mutableStateMapOf<Int, List<List<WarteggNormalizedPoint>>>() }
    val liveAnnotationStroke = remember { mutableStateListOf<WarteggNormalizedPoint>() }

    val restoredSeed = remember(savedResponsesJson, initialResponses) {
        if (savedResponsesJson.isNotBlank()) {
            runCatching {
                RORSCHACH_GSON.fromJson<List<RorschachResponseDraft>>(savedResponsesJson, RORSCHACH_LIST_TYPE)
            }.getOrNull().orEmpty()
        } else {
            initialResponses
        }
    }

    val responses = remember {
        mutableStateListOf<RorschachResponseDraft>().apply {
            addAll(restoredSeed)
        }
    }
    fun persistResponses() {
        savedResponsesJson = RORSCHACH_GSON.toJson(responses.toList())
    }

    val cardBitmaps = remember {
        (1..10).map { num ->
            runCatching {
                val path = "ai/psychometrics/rorschach/card_${String.format(Locale.US, "%02d", num)}.webp"
                context.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }

    val cardNumber = currentCard + 1
    val cardResponses = responses.filter { it.cardNumber == cardNumber }
    val totalR = responses.size

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
                        text = "Tes Rorschach · Kartu $cardNumber/10",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Sistem tunggal ($selectedSystem) · Multi-respons per kartu",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (totalR >= 14) AppPalette.Primary.copy(alpha = 0.2f) else AppPalette.Warning.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "R = $totalR",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (totalR >= 14) AppPalette.Primary else AppPalette.Warning,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Locked Single System Selector
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("EXNER_CS" to "Exner CS", "R_PAS" to "R-PAS").forEach { (sysId, label) ->
                    FilterChip(
                        selected = selectedSystem == sysId,
                        onClick = { selectedSystem = sysId },
                        label = { Text(label, maxLines = 1, softWrap = false) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            if (totalR < 14) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AppPalette.Warning.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AppPalette.Warning.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(AppSpacing.Compact),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AppPalette.Warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Minimum standar validitas R >= 14 respons (saat ini R = $totalR). Tambahkan respons di kartu mana pun.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(10) { idx ->
                    val countOnCard = responses.count { it.cardNumber == idx + 1 }
                    FilterChip(
                        selected = currentCard == idx,
                        onClick = { currentCard = idx },
                        label = {
                            Text(
                                text = if (countOnCard > 0) "K${idx + 1} ($countOnCard)" else "K${idx + 1}",
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            val activeAnnotations = cardAnnotations[currentCard].orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Anotasi Lokasi (Opsional)", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = annotationEnabled, onCheckedChange = { annotationEnabled = it })
                }
                if (activeAnnotations.isNotEmpty()) {
                    TextButton(
                        onClick = { cardAnnotations[currentCard] = emptyList() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Hapus Anotasi")
                    }
                }
            }

            val boxModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .let { base ->
                    if (!annotationEnabled) {
                        base
                    } else {
                        base.captureProjectiveMotionStrokes(
                            key = currentCard,
                            liveStroke = liveAnnotationStroke,
                            onStylusDetected = {},
                            onStrokeStarted = {},
                            onStrokeFinished = { stroke ->
                                cardAnnotations[currentCard] = activeAnnotations + listOf(stroke)
                            }
                        )
                    }
                }
            Box(
                modifier = boxModifier,
                contentAlignment = Alignment.Center
            ) {
                cardBitmaps.getOrNull(currentCard)?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Kartu Rorschach $cardNumber",
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                } ?: Text("Gambar Kartu $cardNumber tidak tersedia", color = Color.DarkGray)
                if (activeAnnotations.isNotEmpty() || liveAnnotationStroke.isNotEmpty()) {
                    Canvas(Modifier.fillMaxSize()) {
                        fun drawAnno(pts: List<WarteggNormalizedPoint>) {
                            if (pts.size <= 1) return
                            val path = Path().apply {
                                moveTo(pts.first().x * size.width, pts.first().y * size.height)
                                pts.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                            }
                            drawPath(
                                path = path,
                                color = Color(0xFF10B981),
                                style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        activeAnnotations.forEach(::drawAnno)
                        drawAnno(liveAnnotationStroke)
                    }
                }
            }
            if (activeAnnotations.isNotEmpty()) {
                val annoMetrics = remember(activeAnnotations) {
                    ProjectiveTelemetryExtractor.extractMetrics(listOf(activeAnnotations))
                }
                Text(
                    text = "Anotasi K$cardNumber: ${annoMetrics.strokeCount} goresan" +
                        (annoMetrics.meanPressure?.let { String.format(Locale.US, " · tekanan=%.2f", it) } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppPalette.Primary
                )
            }

            OutlinedTextField(
                value = freeAssocInput,
                onValueChange = { freeAssocInput = it },
                label = { Text("Asosiasi bebas Kartu $cardNumber (apa yang terlihat)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = inquiryInput,
                onValueChange = { inquiryInput = it },
                label = { Text("Inquiry (di mana letaknya & apa yang membuat tampak demikian)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            TextButton(
                onClick = { showExaminerCoding = !showExaminerCoding },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (showExaminerCoding) "Sembunyikan Coding Pemeriksa" else "Tambah Coding Pemeriksa (Opsional)")
            }

            if (showExaminerCoding) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Lokasi", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("W", "D", "Dd", "S").forEach { loc ->
                            FilterChip(
                                selected = selectedLocation == loc,
                                onClick = { selectedLocation = if (selectedLocation == loc) null else loc },
                                label = { Text(loc) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    Text("Determinan ($selectedSystem)", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("F", "M", "FM", "m", "FC", "CF", "C", "C'", "T", "V", "Y").forEach { det ->
                            FilterChip(
                                selected = selectedDet == det,
                                onClick = { selectedDet = if (selectedDet == det) null else det },
                                label = { Text(det) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    Text("Form Quality", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("FQ+", "FQo", "FQu", "FQ-").forEach { fq ->
                            FilterChip(
                                selected = selectedFq == fq,
                                onClick = { selectedFq = if (selectedFq == fq) null else fq },
                                label = { Text(fq) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Popular (P)", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = selectedPopular, onCheckedChange = { selectedPopular = it })
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    val trimmedAssoc = freeAssocInput.trim()
                    if (trimmedAssoc.isNotEmpty() || selectedLocation != null) {
                        val nextIdx = cardResponses.size + 1
                        responses.add(
                            RorschachResponseDraft(
                                cardNumber = cardNumber,
                                responseIndex = nextIdx,
                                freeAssociation = trimmedAssoc,
                                inquiry = inquiryInput.trim(),
                                codingSystem = selectedSystem,
                                location = selectedLocation,
                                determinant = selectedDet,
                                formQuality = selectedFq,
                                popular = selectedPopular
                            )
                        )
                        persistResponses()
                        freeAssocInput = ""
                        inquiryInput = ""
                        selectedLocation = null
                        selectedDet = null
                        selectedFq = null
                        selectedPopular = false
                    }
                },
                enabled = freeAssocInput.isNotBlank() || selectedLocation != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tambah Respons Kartu $cardNumber", maxLines = 1, softWrap = false)
            }

            if (cardResponses.isNotEmpty()) {
                Divider()
                Text("Respons Tercatat di Kartu $cardNumber (${cardResponses.size})", style = MaterialTheme.typography.labelLarge)
                cardResponses.forEach { resp ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(AppSpacing.Compact),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "#${resp.responseIndex}: ${resp.freeAssociation.ifBlank { "(Terkode tanpa teks)" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val codingTag = listOfNotNull(
                                    resp.location,
                                    resp.determinant,
                                    resp.formQuality,
                                    if (resp.popular) "P" else null
                                ).joinToString(" · ")
                                if (codingTag.isNotBlank()) {
                                    Text(codingTag, style = MaterialTheme.typography.labelSmall, color = AppPalette.Primary)
                                }
                            }
                            IconButton(
                                onClick = {
                                    responses.remove(resp)
                                    persistResponses()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Hapus respons", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { onSaveResponses(responses.toList(), selectedSystem) },
                enabled = responses.isNotEmpty() && !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Simpan Protokol Rorschach (R = $totalR)", fontWeight = FontWeight.Bold)
            }
        }
    }
}

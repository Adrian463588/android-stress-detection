package com.stressdetector.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.assessment.AccessibleScoreRow
import com.stressdetector.mobile.domain.assessment.AssessmentVisualizationModel
import com.stressdetector.mobile.domain.assessment.ChartLineStyle
import com.stressdetector.mobile.domain.assessment.ChartPointShape
import com.stressdetector.mobile.domain.assessment.KpiMetricDatum
import com.stressdetector.mobile.domain.assessment.ProjectiveDrawingTelemetry
import com.stressdetector.mobile.domain.assessment.RadarAxisDatum
import com.stressdetector.mobile.domain.assessment.ScaleBarDatum
import com.stressdetector.mobile.domain.assessment.WarteggBoxDraft
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.AppSpacing
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val ChartSeriesColors = listOf(
    AppPalette.Primary,
    AppPalette.Secondary,
    AppPalette.Warning
)

@Composable
@Suppress("FunctionNaming")
fun AssessmentVisualizationCard(
    model: AssessmentVisualizationModel,
    showTable: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        Text(
            text = model.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = model.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (showTable) {
            AccessibleScoreDataTable(rows = model.tableRows)
        } else {
            when (model) {
                is AssessmentVisualizationModel.DiscProfileLineModel -> DiscProfileLineChart(model)
                is AssessmentVisualizationModel.MmpiClinicalProfileModel -> MmpiClinicalProfileChart(model)
                is AssessmentVisualizationModel.HorizontalBarChartModel -> {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        if (model.kpiMetrics.isNotEmpty()) {
                            KpiMetricsRow(model.kpiMetrics)
                        }
                        HorizontalScaleBarChart(model.bars)
                        if (model.radarAxes.size >= 3) {
                            PolygonRadarProfile(
                                axes = model.radarAxes,
                                caption = "Pemetaan profil relatif (bukan persentil norma klinis)."
                            )
                        }
                    }
                }
                is AssessmentVisualizationModel.BipolarPreferenceModel -> BipolarPreferenceChart(model)
                is AssessmentVisualizationModel.RankedBarChartModel -> {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        model.dominantProfileCode?.let { code ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppPalette.Primary.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, AppPalette.Primary.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(AppSpacing.Small)) {
                                    Text("Pemetaan Minat Dominan", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        text = code,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = AppPalette.Primary
                                    )
                                }
                            }
                        }
                        if (model.radarAxes.size >= 3) {
                            PolygonRadarProfile(
                                axes = model.radarAxes,
                                caption = "Pemetaan heksagonal RIASEC relatif dalam sesi ini."
                            )
                        }
                        HorizontalScaleBarChart(
                            bars = model.bars,
                            headerHint = if (model.lowerIsHigherInterest) {
                                "Urutan teratas = jumlah peringkat terkecil (minat tertinggi)"
                            } else {
                                "Urutan dari skor tertinggi"
                            }
                        )
                    }
                }
                is AssessmentVisualizationModel.TimeSeriesCurveModel -> TimeSeriesCurveChart(model)
                is AssessmentVisualizationModel.ScoreIndicatorModel -> ScoreIndicatorView(model)
                is AssessmentVisualizationModel.WarteggEightBoxModel -> WarteggEightBoxGridOverview(model)
                is AssessmentVisualizationModel.ExaminerCodedProfileModel -> ExaminerCodedProfilePanel(model)
                is AssessmentVisualizationModel.ObservationMatrixModel -> ObservationMatrixPanel(model)
                is AssessmentVisualizationModel.StatusOnlyModel -> StatusOnlyPanel(model)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming", "MagicNumber", "LongMethod")
private fun DiscProfileLineChart(model: AssessmentVisualizationModel.DiscProfileLineModel) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
        ) {
            Text(
                text = model.primarySummary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppPalette.Primary
            )

            val allVals = model.series.flatMap { it.values }
            val minY = (allVals.minOrNull()?.coerceAtMost(0.0) ?: 0.0)
            val maxY = (allVals.maxOrNull()?.coerceAtLeast(12.0) ?: 24.0)
            val spanY = (maxY - minY).coerceAtLeast(1.0)
            val a11ySummary = model.series.joinToString("; ") { series ->
                val pts = model.axisLabels.zip(series.values).joinToString(", ") { "${it.first}=${formatNum(it.second)}" }
                "${series.label}: $pts"
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.height(176.dp).width(34.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text(formatNum(maxY), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatNum(minY + spanY / 2.0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatNum(minY), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(6.dp))
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(176.dp)
                        .semantics { contentDescription = "Grafik garis DISC: $a11ySummary" }
                ) {
                    val padH = 16f
                    val padV = 14f
                    val chartW = size.width - padH * 2
                    val chartH = size.height - padV * 2

                    listOf(0f, 0.5f, 1f).forEach { ratio ->
                        val y = padV + chartH * ratio
                        drawLine(
                            color = Color.White.copy(alpha = 0.14f),
                            start = Offset(padH, y),
                            end = Offset(padH + chartW, y),
                            strokeWidth = 1.5f
                        )
                    }

                    model.series.forEachIndexed { idx, series ->
                        val color = ChartSeriesColors[idx % ChartSeriesColors.size]
                        val effect = when (series.lineStyle) {
                            ChartLineStyle.SOLID -> null
                            ChartLineStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                            ChartLineStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(5f, 8f))
                        }
                        val pts = series.values.mapIndexed { xIdx, v ->
                            val xFrac = if (series.values.size > 1) xIdx.toFloat() / (series.values.size - 1) else 0.5f
                            val yFrac = ((v - minY) / spanY).toFloat().coerceIn(0f, 1f)
                            Offset(padH + chartW * xFrac, padV + chartH * (1f - yFrac))
                        }
                        if (pts.size > 1) {
                            val path = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                pts.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(
                                path = path,
                                color = color,
                                style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)
                            )
                        }
                        pts.forEach { p -> drawPointMarker(p, series.pointShape, color) }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                model.axisLabels.forEach { axis ->
                    Text(axis, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                model.series.forEachIndexed { idx, series ->
                    val styleLabel = when (series.lineStyle) {
                        ChartLineStyle.SOLID -> "garis utuh"
                        ChartLineStyle.DASHED -> "putus-putus"
                        ChartLineStyle.DOTTED -> "titik"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(ChartSeriesColors[idx % ChartSeriesColors.size], RoundedCornerShape(3.dp))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${series.label} ($styleLabel)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber", "LongMethod")
private fun MmpiClinicalProfileChart(model: AssessmentVisualizationModel.MmpiClinicalProfileModel) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(
                1.dp,
                if (model.validityVerified) AppPalette.Primary.copy(alpha = 0.5f) else AppPalette.Warning.copy(alpha = 0.6f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Pemeriksaan Skala Validitas (${model.editionLabel})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (model.validityVerified) AppPalette.Primary else AppPalette.Warning
                )
                HorizontalScaleBarChart(bars = model.validityScales)
            }
        }

        if (model.clinicalScales.isNotEmpty()) {
            val minY = model.clinicalMin
            val maxY = model.clinicalMax.coerceAtLeast(minY + 10.0)
            val spanY = maxY - minY
            val a11yText = model.clinicalScales.joinToString(", ") { "${it.code}=${it.valueText}" }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.height(170.dp).width(34.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(formatNum(maxY), style = MaterialTheme.typography.labelSmall)
                            Text(formatNum(minY + spanY / 2.0), style = MaterialTheme.typography.labelSmall)
                            Text(formatNum(minY), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(6.dp))
                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .height(170.dp)
                                .semantics { contentDescription = "Profil skala klinis MMPI: $a11yText" }
                        ) {
                            val padH = 12f
                            val padV = 14f
                            val chartW = size.width - padH * 2
                            val chartH = size.height - padV * 2

                            model.referenceLineValue?.let { refVal ->
                                val refFrac = ((refVal - minY) / spanY).toFloat().coerceIn(0f, 1f)
                                val refY = padV + chartH * (1f - refFrac)
                                drawRect(
                                    color = AppPalette.Warning.copy(alpha = 0.10f),
                                    topLeft = Offset(padH, padV),
                                    size = Size(chartW, (refY - padV).coerceAtLeast(0f))
                                )
                                drawLine(
                                    color = AppPalette.Warning,
                                    start = Offset(padH, refY),
                                    end = Offset(padH + chartW, refY),
                                    strokeWidth = 3f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                                )
                            }

                            val pts = model.clinicalScales.mapIndexed { idx, pt ->
                                val xFrac = if (model.clinicalScales.size > 1) {
                                    idx.toFloat() / (model.clinicalScales.size - 1)
                                } else {
                                    0.5f
                                }
                                val yFrac = ((pt.value - minY) / spanY).toFloat().coerceIn(0f, 1f)
                                Offset(padH + chartW * xFrac, padV + chartH * (1f - yFrac))
                            }
                            if (pts.size > 1) {
                                val path = Path().apply {
                                    moveTo(pts.first().x, pts.first().y)
                                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(path = path, color = AppPalette.Primary, style = Stroke(width = 4.5f, cap = StrokeCap.Round))
                            }
                            pts.forEachIndexed { idx, p ->
                                val elevated = model.clinicalScales[idx].elevated
                                drawPointMarker(
                                    p,
                                    if (elevated) ChartPointShape.DIAMOND else ChartPointShape.CIRCLE,
                                    if (elevated) AppPalette.Warning else AppPalette.Primary
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        model.clinicalScales.forEach { pt ->
                            Text(
                                text = pt.code,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    model.referenceLabel?.let { refLabel ->
                        Text(
                            text = "Garis putus-putus: $refLabel (ilustrasi referensi; bukan batas diagnostik tunggal).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber")
private fun HorizontalScaleBarChart(bars: List<ScaleBarDatum>, headerHint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        headerHint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        bars.forEach { bar ->
            val span = (bar.maximum - bar.minimum).coerceAtLeast(1.0)
            val fraction = ((bar.value - bar.minimum) / span).toFloat().coerceIn(0f, 1f)
            val barColor = if (bar.flagged) AppPalette.Error else AppPalette.Primary

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${bar.label}, skor ${bar.valueText} dari rentang ${formatNum(bar.minimum)} hingga ${formatNum(bar.maximum)}"
                    }
            ) {
                Column(Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Compact)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bar.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(AppSpacing.Compact))
                        Text(
                            text = "${bar.valueText} / ${formatNum(bar.maximum)}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = barColor
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(8.dp)
                                .background(barColor, RoundedCornerShape(4.dp))
                        )
                    }
                    if (bar.note.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = bar.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber", "LongMethod")
private fun TimeSeriesCurveChart(model: AssessmentVisualizationModel.TimeSeriesCurveModel) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        val totalProd = model.points.sumOf { it.value }.toInt()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SummaryStatTile("Total benar", totalProd.toString(), AppPalette.Primary, Modifier.weight(1f))
            SummaryStatTile("Kesalahan", model.totalErrors.toString(), AppPalette.Warning, Modifier.weight(1f))
            SummaryStatTile("Terlewat", model.totalSkips.toString(), AppPalette.Secondary, Modifier.weight(1f))
        }

        val values = model.points.map { it.value }
        val minY = (values.minOrNull() ?: 0.0).coerceAtLeast(0.0)
        val maxY = (values.maxOrNull() ?: 20.0).coerceAtLeast(minY + 4.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .semantics {
                            contentDescription = "Kurva waktu ${model.points.size} interval, total benar $totalProd, salah ${model.totalErrors}, terlewati ${model.totalSkips}"
                        }
                ) {
                    val padH = 12f
                    val padV = 12f
                    val chartW = size.width - padH * 2
                    val chartH = size.height - padV * 2

                    model.meanValue?.let { mean ->
                        val frac = ((mean - minY) / spanY).toFloat().coerceIn(0f, 1f)
                        val y = padV + chartH * (1f - frac)
                        drawLine(
                            color = AppPalette.Secondary,
                            start = Offset(padH, y),
                            end = Offset(padH + chartW, y),
                            strokeWidth = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                        )
                    }

                    val pts = model.points.mapIndexed { idx, pt ->
                        val xFrac = if (model.points.size > 1) idx.toFloat() / (model.points.size - 1) else 0.5f
                        val yFrac = ((pt.value - minY) / spanY).toFloat().coerceIn(0f, 1f)
                        Offset(padH + chartW * xFrac, padV + chartH * (1f - yFrac))
                    }
                    if (pts.size > 1) {
                        val path = Path().apply {
                            moveTo(pts.first().x, pts.first().y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path = path, color = AppPalette.Primary, style = Stroke(width = 4f, cap = StrokeCap.Round))
                    }
                    pts.forEachIndexed { idx, p ->
                        val hasErr = model.points[idx].errorCount > 0 || model.points[idx].skipCount > 0
                        drawPointMarker(
                            p,
                            if (hasErr) ChartPointShape.SQUARE else ChartPointShape.CIRCLE,
                            if (hasErr) AppPalette.Warning else AppPalette.Primary
                        )
                    }
                }
                Text(
                    text = "Garis putus-putus = rerata (${model.meanValue?.let { formatNum(it) } ?: "-"}) · " +
                        "Total salah = ${model.totalErrors}, terlewati = ${model.totalSkips}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (model.points.any { it.errorCount > 0 || it.skipCount > 0 }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Distribusi Kesalahan & Terlewat per Interval",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    val maxErrSkip = model.points.maxOfOrNull { maxOf(it.errorCount, it.skipCount) }?.coerceAtLeast(1) ?: 1
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .semantics {
                                contentDescription = "Grafik batang kesalahan (${model.totalErrors}) dan terlewat (${model.totalSkips})"
                            }
                    ) {
                        val slotW = (size.width / model.points.size.coerceAtLeast(1)).coerceAtLeast(4f)
                        model.points.forEachIndexed { idx, pt ->
                            val baseX = idx * slotW + slotW * 0.15f
                            val barW = (slotW * 0.32f).coerceAtLeast(2f)
                            val errH = (pt.errorCount.toFloat() / maxErrSkip) * (size.height - 8f)
                            val skipH = (pt.skipCount.toFloat() / maxErrSkip) * (size.height - 8f)
                            if (errH > 0f) {
                                drawRect(
                                    color = AppPalette.Warning,
                                    topLeft = Offset(baseX, size.height - errH),
                                    size = Size(barW, errH)
                                )
                            }
                            if (skipH > 0f) {
                                drawRect(
                                    color = AppPalette.Secondary,
                                    topLeft = Offset(baseX + barW + 2f, size.height - skipH),
                                    size = Size(barW, skipH)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                        LegendSwatch("Kesalahan", AppPalette.Warning)
                        LegendSwatch("Terlewat", AppPalette.Secondary)
                    }
                }
            }
        }
        HorizontalScaleBarChart(bars = model.summaryMetrics)
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber")
private fun ScoreIndicatorView(model: AssessmentVisualizationModel.ScoreIndicatorModel) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        HorizontalScaleBarChart(bars = listOf(model.primaryScore))
        if (model.bands.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                model.bands.forEach { band ->
                    val inBand = model.primaryScore.value in band.start..band.end
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (inBand) AppPalette.Primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (inBand) AppPalette.Primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = band.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (inBand) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = "${band.start.toInt()}–${band.end.toInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        if (model.subscales.isNotEmpty()) {
            HorizontalScaleBarChart(bars = model.subscales)
        }
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber")
private fun WarteggEightBoxGridOverview(model: AssessmentVisualizationModel.WarteggEightBoxModel) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        val sequenceText = if (model.sequenceOrder.isNotEmpty()) {
            model.sequenceOrder.joinToString(" -> ") { "K$it" }
        } else {
            "Belum tercatat"
        }
        Text(
            text = "Urutan gambar: $sequenceText · Total durasi: ${model.totalDurationSeconds} dtk",
            style = MaterialTheme.typography.labelMedium,
            color = AppPalette.Primary
        )
        for (row in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (col in 0 until 4) {
                    val box = model.boxes.getOrElse(row * 4 + col) { WarteggBoxDraft(boxNumber = row * 4 + col + 1) }
                    WarteggMiniBoxPreview(box = box, modifier = Modifier.weight(1f))
                }
            }
        }
        if (model.boxes.any { it.durationSeconds > 0 }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Durasi pengerjaan per kotak (observasi non-diagnostik)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    val maxDur = model.boxes.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1) ?: 1
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .semantics {
                                val summary = model.boxes.joinToString(", ") { "K${it.boxNumber}=${it.durationSeconds}d" }
                                contentDescription = "Grafik durasi pengerjaan Wartegg per kotak: $summary"
                            }
                    ) {
                        val slotW = size.width / 8f
                        model.boxes.take(8).forEachIndexed { idx, box ->
                            val frac = (box.durationSeconds.toFloat() / maxDur).coerceIn(0f, 1f)
                            val h = (size.height - 12f) * frac
                            drawRect(
                                color = AppPalette.Secondary,
                                topLeft = Offset(idx * slotW + slotW * 0.2f, size.height - h),
                                size = Size(slotW * 0.6f, h.coerceAtLeast(4f))
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        model.boxes.take(8).forEach { box ->
                            Text("${box.boxNumber}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        if (model.codingStatusRows.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Coding dan Interpretasi", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    model.codingStatusRows.forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.component, style = MaterialTheme.typography.bodySmall)
                            Text(row.status, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = AppPalette.Warning)
                        }
                    }
                }
            }
        }
        model.telemetry?.let { telemetry ->
            ProjectiveTelemetryChartsPanel(telemetry = telemetry)
        }
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber")
private fun WarteggMiniBoxPreview(box: WarteggBoxDraft, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("K${box.boxNumber}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    box.drawOrder?.let { "#$it" } ?: "-",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppPalette.Primary
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(6.dp))
            ) {
                box.strokes.forEach { stroke ->
                    if (stroke.size > 1) {
                        val path = Path().apply {
                            moveTo(stroke.first().x * size.width, stroke.first().y * size.height)
                            stroke.drop(1).forEach { pt -> lineTo(pt.x * size.width, pt.y * size.height) }
                        }
                        drawPath(path = path, color = Color(0xFF0F172A), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
                    }
                }
            }
            Text(
                text = box.title.ifBlank { if (box.strokes.isNotEmpty()) "Digambar" else "Kosong" },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val tags = listOfNotNull(
                box.valence,
                box.difficulty,
                if (box.durationSeconds > 0) "${box.durationSeconds}s" else null
            ).joinToString("·")
            Text(
                text = tags.ifBlank { "-" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ExaminerCodedProfilePanel(model: AssessmentVisualizationModel.ExaminerCodedProfileModel) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                Text(
                    "Sistem: ${model.systemId} · Total Respons (R): ${model.responseCount}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (model.responseCount >= 14) AppPalette.Primary else AppPalette.Warning
                )
                Text(model.locationSummary, style = MaterialTheme.typography.bodySmall)
                if (model.examinerCodes.isNotEmpty()) {
                    Divider()
                    model.examinerCodes.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(k, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text(v, style = MaterialTheme.typography.bodySmall, color = AppPalette.Primary)
                        }
                    }
                }
            }
        }
        if (model.bars.isNotEmpty()) {
            HorizontalScaleBarChart(bars = model.bars)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun StatusOnlyPanel(model: AssessmentVisualizationModel.StatusOnlyModel) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, AppPalette.Warning.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Text(
                text = "Status: ${model.status.displayLabel}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = AppPalette.Warning
            )
            Text(model.reason, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "Referensi norma: ${model.normNote}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
fun AccessibleScoreDataTable(rows: List<AccessibleScoreRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
        Text(
            text = "Belum ada rincian tabel numerik untuk instrumen ini.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { row ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Compact)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(AppSpacing.Compact))
                        Text(
                            text = "${row.valueText} (${row.rangeText})",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppPalette.Primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (row.note.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = row.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Suppress("MagicNumber")
private fun DrawScope.drawPointMarker(center: Offset, shape: ChartPointShape, color: Color) {
    when (shape) {
        ChartPointShape.CIRCLE -> drawCircle(color = color, radius = 8f, center = center)
        ChartPointShape.SQUARE -> drawRect(
            color = color,
            topLeft = Offset(center.x - 7f, center.y - 7f),
            size = Size(14f, 14f)
        )
        ChartPointShape.DIAMOND -> {
            val d = 9f
            val path = Path().apply {
                moveTo(center.x, center.y - d)
                lineTo(center.x + d, center.y)
                lineTo(center.x, center.y + d)
                lineTo(center.x - d, center.y)
                close()
            }
            drawPath(path = path, color = color)
        }
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber")
private fun BipolarPreferenceChart(model: AssessmentVisualizationModel.BipolarPreferenceModel) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = AppPalette.Primary.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, AppPalette.Primary.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(AppSpacing.Small),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Preferensi Dominan Sesi", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = model.primaryTypeCode,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppPalette.Primary
                )
            }
        }
        model.axes.forEach { axis ->
            val leftFrac = (axis.leftValue / axis.totalMax.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
            val rightFrac = (axis.rightValue / axis.totalMax.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${axis.dimensionLabel}: ${axis.leftLabel} ${formatNum(axis.leftValue)} vs ${axis.rightLabel} ${formatNum(axis.rightValue)}, dominan ${axis.dominantCode}"
                    }
            ) {
                Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "${axis.leftCode} · ${axis.leftLabel} (${formatNum(axis.leftValue)})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (axis.dominantCode == axis.leftCode) FontWeight.Bold else FontWeight.Normal,
                            color = if (axis.dominantCode == axis.leftCode) AppPalette.Primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${axis.rightCode} · ${axis.rightLabel} (${formatNum(axis.rightValue)})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (axis.dominantCode == axis.rightCode) FontWeight.Bold else FontWeight.Normal,
                            color = if (axis.dominantCode == axis.rightCode) AppPalette.Primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(leftFrac)
                                    .height(10.dp)
                                    .background(
                                        if (axis.dominantCode == axis.leftCode) AppPalette.Primary else AppPalette.Secondary.copy(alpha = 0.55f),
                                        RoundedCornerShape(5.dp)
                                    )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(rightFrac)
                                    .height(10.dp)
                                    .background(
                                        if (axis.dominantCode == axis.rightCode) AppPalette.Primary else AppPalette.Secondary.copy(alpha = 0.55f),
                                        RoundedCornerShape(5.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber")
private fun PolygonRadarProfile(axes: List<RadarAxisDatum>, caption: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Small),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val summaryDesc = axes.joinToString(", ") { "${it.label}=${it.valueText}" }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .semantics { contentDescription = "Diagram radar profil relatif: $summaryDesc" }
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = (minOf(size.width, size.height) * 0.38f).coerceAtLeast(30f)
                val n = axes.size
                listOf(0.25f, 0.5f, 0.75f, 1f).forEach { ring ->
                    val ringPath = Path()
                    for (i in 0 until n) {
                        val angle = (-PI / 2.0) + (2.0 * PI * i / n)
                        val pt = Offset(
                            center.x + (radius * ring * cos(angle)).toFloat(),
                            center.y + (radius * ring * sin(angle)).toFloat()
                        )
                        if (i == 0) ringPath.moveTo(pt.x, pt.y) else ringPath.lineTo(pt.x, pt.y)
                    }
                    ringPath.close()
                    drawPath(ringPath, color = Color.White.copy(alpha = 0.16f), style = Stroke(width = 1.5f))
                }
                val dataPath = Path()
                axes.forEachIndexed { idx, axis ->
                    val angle = (-PI / 2.0) + (2.0 * PI * idx / n)
                    val pt = Offset(
                        center.x + (radius * axis.normalizedFraction * cos(angle)).toFloat(),
                        center.y + (radius * axis.normalizedFraction * sin(angle)).toFloat()
                    )
                    if (idx == 0) dataPath.moveTo(pt.x, pt.y) else dataPath.lineTo(pt.x, pt.y)
                }
                dataPath.close()
                drawPath(dataPath, color = AppPalette.Primary.copy(alpha = 0.25f))
                drawPath(dataPath, color = AppPalette.Primary, style = Stroke(width = 4f))
            }
            Text(
                text = axes.joinToString(" · ") { "${it.code}: ${it.valueText}" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun KpiMetricsRow(metrics: List<com.stressdetector.mobile.domain.assessment.KpiMetricDatum>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        metrics.forEach { metric ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f)
            ) {
                Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(metric.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(metric.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (metric.subtitle.isNotBlank()) {
                        Text(metric.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun SummaryStatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = modifier
    ) {
        Column(Modifier.padding(AppSpacing.Small)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun LegendSwatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber")
private fun ObservationMatrixPanel(model: AssessmentVisualizationModel.ObservationMatrixModel) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        if (model.panels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                model.panels.take(3).forEach { panel ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = panel.panelLabel + (panel.drawOrder?.let { " #$it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(Color.White, RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(6.dp))
                            ) {
                                panel.strokes.forEach { stroke ->
                                    if (stroke.size > 1) {
                                        val path = Path().apply {
                                            moveTo(stroke.first().x * size.width, stroke.first().y * size.height)
                                            stroke.drop(1).forEach { pt -> lineTo(pt.x * size.width, pt.y * size.height) }
                                        }
                                        drawPath(path = path, color = Color(0xFF0F172A), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
                                    }
                                }
                            }
                            Text(
                                text = panel.title.ifBlank { if (panel.strokes.isNotEmpty()) "Digambar" else "Kosong" },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        if (model.bars.isNotEmpty()) {
            HorizontalScaleBarChart(bars = model.bars)
        }
        model.telemetry?.let { telemetry ->
            ProjectiveTelemetryChartsPanel(telemetry = telemetry)
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Status Review: ${model.reviewStatus.displayLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppPalette.Warning
                )
                if (model.observations.isEmpty()) {
                    Text(
                        "Belum ada catatan observasi grafis; lakukan coding pemeriksa manual sesuai manual proyektif.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    model.observations.forEach { obs ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(obs.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(obs.value, style = MaterialTheme.typography.bodySmall)
                            if (obs.tags.isNotEmpty()) {
                                Text(
                                    obs.tags.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppPalette.Primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber")
private fun ProjectiveTelemetryChartsPanel(telemetry: ProjectiveDrawingTelemetry) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Kinematika & Telemetri Goresan",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            KpiMetricsRow(
                listOf(
                    KpiMetricDatum(
                        "Goresan",
                        "${telemetry.strokeCount}",
                        "revisi ${telemetry.revisionCount}"
                    ),
                    KpiMetricDatum(
                        "Jeda",
                        "${telemetry.pauseCount}",
                        "${telemetry.totalPauseMs} ms"
                    ),
                    KpiMetricDatum(
                        "Roughness",
                        String.format(Locale.US, "%.2f", telemetry.roughnessIndex),
                        "indeks kinematik"
                    )
                )
            )
            if (telemetry.speedSeries.size > 1) {
                TelemetryLineSparkChart(
                    title = "Kecepatan Goresan vs Waktu (unit/dtk)",
                    series = telemetry.speedSeries,
                    lineColor = AppPalette.Primary,
                    summaryText = String.format(
                        Locale.US,
                        "Rerata %.2f · Maks %.2f · Accel %.2f · Jerk %.2f",
                        telemetry.meanSpeedNormPerSec,
                        telemetry.maxSpeedNormPerSec,
                        telemetry.meanAcceleration,
                        telemetry.meanJerk
                    )
                )
            }
            if (telemetry.pressureSeries.size > 1) {
                TelemetryLineSparkChart(
                    title = "Tekanan Ujung Pena vs Waktu",
                    series = telemetry.pressureSeries,
                    lineColor = AppPalette.Secondary,
                    summaryText = String.format(
                        Locale.US,
                        "Rerata %.2f [%.2f..%.2f]",
                        telemetry.meanPressure ?: 0f,
                        telemetry.minPressure ?: 0f,
                        telemetry.maxPressure ?: 0f
                    )
                )
            } else {
                Text(
                    text = "Tekanan ujung pena: tidak tersedia (input sentuh atau perangkat tanpa digitizer aktif).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "MagicNumber")
private fun TelemetryLineSparkChart(
    title: String,
    series: List<Pair<Float, Float>>,
    lineColor: Color,
    summaryText: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
        Text(summaryText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val minT = series.minOf { it.first }
        val maxT = series.maxOf { it.first }
        val tSpan = (maxT - minT).coerceAtLeast(1e-3f)
        val minV = series.minOf { it.second }
        val maxV = series.maxOf { it.second }
        val vSpan = (maxV - minV).coerceAtLeast(1e-3f)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(8.dp)
                .semantics { contentDescription = "$title: $summaryText" }
        ) {
            val path = Path()
            series.forEachIndexed { idx, (t, v) ->
                val x = ((t - minT) / tSpan).coerceIn(0f, 1f) * size.width
                val y = size.height - ((v - minV) / vSpan).coerceIn(0f, 1f) * size.height
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

private fun formatNum(value: Double): String = if (value == value.toInt().toDouble()) {
    value.toInt().toString()
} else {
    String.format(Locale.US, "%.1f", value)
}

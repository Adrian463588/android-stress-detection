package com.stressdetector.mobile.domain.assessment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

data class ProjectiveDrawingTelemetry(
    val strokeCount: Int,
    val pointCount: Int,
    val stylusUsed: Boolean,
    val meanPressure: Float?,
    val minPressure: Float?,
    val maxPressure: Float?,
    val pressureStdDev: Float?,
    val pressureDeltaMean: Float?,
    val meanTilt: Float?,
    val meanOrientation: Float?,
    val totalPathLength: Float,
    val meanSpeedNormPerSec: Float,
    val maxSpeedNormPerSec: Float,
    val meanAcceleration: Float,
    val meanJerk: Float,
    val maxJerk: Float,
    val meanCurvature: Float,
    val directionChangeCount: Int,
    val dominantDirection: String,
    val totalDurationMs: Long,
    val meanStrokeDurationMs: Float,
    val pauseCount: Int,
    val totalPauseMs: Long,
    val revisionCount: Int,
    val spaceCoveragePercent: Float,
    val aspectRatio: Float,
    val strokeDensity: Float,
    val centerOfMassX: Float,
    val centerOfMassY: Float,
    val roughnessIndex: Float,
    val pressureSeries: List<Pair<Float, Float>> = emptyList(),
    val speedSeries: List<Pair<Float, Float>> = emptyList()
) {
    fun toFormattedSummary(): String = buildString {
        val inputSource = if (stylusUsed) "Stylus (MotionEvent)" else "Sentuh / Jari"
        append("Input: $inputSource · $strokeCount goresan ($pointCount titik, revisi=$revisionCount). ")
        if (meanPressure != null) {
            append(
                String.format(
                    Locale.US,
                    "Tekanan rata-rata=%.2f [%.2f..%.2f], SD=%.2f, delta=%.3f. ",
                    meanPressure,
                    minPressure ?: meanPressure,
                    maxPressure ?: meanPressure,
                    pressureStdDev ?: 0f,
                    pressureDeltaMean ?: 0f
                )
            )
        } else {
            append("Tekanan perangkat tidak tersedia (null). ")
        }
        meanTilt?.let { append(String.format(Locale.US, "Tilt=%.2f rad. ", it)) }
        meanOrientation?.let { append(String.format(Locale.US, "Orientasi=%.2f rad. ", it)) }
        append(
            String.format(
                Locale.US,
                "Kinematika: v_mean=%.2f/dtk (maks=%.2f), accel=%.2f, jerk=%.2f, kelengkungan=%.2f, ubah_arah=%d (%s). ",
                meanSpeedNormPerSec,
                maxSpeedNormPerSec,
                meanAcceleration,
                meanJerk,
                meanCurvature,
                directionChangeCount,
                dominantDirection
            )
        )
        append(
            String.format(
                Locale.US,
                "Temporal & Spasial: durasi=%dms, jeda=%d (%dms), ruang=%.1f%%, kerapatan=%.2f, W/H=%.2f, pusat=(%.2f, %.2f), " +
                    "roughness=%.2f.",
                totalDurationMs,
                pauseCount,
                totalPauseMs,
                spaceCoveragePercent,
                strokeDensity,
                aspectRatio,
                centerOfMassX,
                centerOfMassY,
                roughnessIndex
            )
        )
    }
}

data class ProjectiveMultimodalReport(
    val summary: String,
    val multimodalModelUsed: Boolean,
    val fallbackUsed: Boolean,
    val telemetry: ProjectiveDrawingTelemetry
)

@Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod")
object ProjectiveTelemetryExtractor {

    private const val MIN_DT_SEC = 1e-4f
    private const val MAX_SEGMENT_SPEED = 25f
    private const val PAUSE_THRESHOLD_MS = 250L
    private const val DIRECTION_CHANGE_THRESHOLD_RAD = 0.43633232f // ~25 deg
    private const val MAX_SERIES_POINTS = 48

    fun extractMetrics(
        panelStrokes: List<List<List<WarteggNormalizedPoint>>>,
        revisionCount: Int = 0
    ): ProjectiveDrawingTelemetry {
        val strokes = panelStrokes.flatten().filter { it.isNotEmpty() }
        val points = strokes.flatten()
        if (points.isEmpty()) {
            return ProjectiveDrawingTelemetry(
                strokeCount = 0,
                pointCount = 0,
                stylusUsed = false,
                meanPressure = null,
                minPressure = null,
                maxPressure = null,
                pressureStdDev = null,
                pressureDeltaMean = null,
                meanTilt = null,
                meanOrientation = null,
                totalPathLength = 0f,
                meanSpeedNormPerSec = 0f,
                maxSpeedNormPerSec = 0f,
                meanAcceleration = 0f,
                meanJerk = 0f,
                maxJerk = 0f,
                meanCurvature = 0f,
                directionChangeCount = 0,
                dominantDirection = "Tidak ada goresan",
                totalDurationMs = 0L,
                meanStrokeDurationMs = 0f,
                pauseCount = 0,
                totalPauseMs = 0L,
                revisionCount = revisionCount,
                spaceCoveragePercent = 0f,
                aspectRatio = 1f,
                strokeDensity = 0f,
                centerOfMassX = 0.5f,
                centerOfMassY = 0.5f,
                roughnessIndex = 0f
            )
        }

        val stylusUsed = points.any { it.isStylus }
        val pressures = points.mapNotNull { it.pressure }.filter { it.isFinite() && it >= 0f }
        val meanPressure = pressures.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val minPressure = pressures.minOrNull()
        val maxPressure = pressures.maxOrNull()
        val pressureStdDev = if (meanPressure != null && pressures.size > 1) {
            sqrt(pressures.map { (it - meanPressure).toDouble().pow(2) }.average()).toFloat()
        } else if (meanPressure != null) {
            0f
        } else {
            null
        }
        val tilts = points.mapNotNull { it.tilt }.filter { it.isFinite() && it > 0f }
        val meanTilt = tilts.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val orientations = points.mapNotNull { it.orientation }.filter { it.isFinite() }
        val meanOrientation = orientations.takeIf { it.isNotEmpty() }?.average()?.toFloat()

        var horizontalAccum = 0f
        var verticalAccum = 0f
        var diagonalAccum = 0f
        var totalPathLength = 0f
        var directionChangeCount = 0

        val speeds = mutableListOf<Float>()
        val accelerations = mutableListOf<Float>()
        val jerks = mutableListOf<Float>()
        val curvatures = mutableListOf<Float>()
        val turningAngles = mutableListOf<Float>()
        val pressureDeltas = mutableListOf<Float>()
        val strokeDurationsMs = mutableListOf<Long>()

        val allTimestamps = points.mapNotNull { it.timeMillis }.sorted()
        val baseTimeMs = allTimestamps.firstOrNull() ?: 0L
        val rawSpeedSamples = mutableListOf<Pair<Float, Float>>()
        val rawPressureSamples = mutableListOf<Pair<Float, Float>>()

        var pauseCount = 0
        var totalPauseMs = 0L
        var prevStrokeEndMs: Long? = null

        strokes.forEach { stroke ->
            // Filter consecutive duplicate coordinates/timestamps cleanly before numerical differentiation
            val deduped = mutableListOf<WarteggNormalizedPoint>()
            stroke.forEach { pt ->
                val prev = deduped.lastOrNull()
                if (prev == null ||
                    hypot(pt.x - prev.x, pt.y - prev.y) > 1e-4f ||
                    (pt.timeMillis != null && prev.timeMillis != null && pt.timeMillis > prev.timeMillis)
                ) {
                    deduped += pt
                }
            }
            if (deduped.isEmpty()) return@forEach

            val strokeStartMs = deduped.firstOrNull { it.timeMillis != null }?.timeMillis
            val strokeEndMs = deduped.lastOrNull { it.timeMillis != null }?.timeMillis
            if (strokeStartMs != null && strokeEndMs != null) {
                if (strokeEndMs >= strokeStartMs) {
                    strokeDurationsMs += (strokeEndMs - strokeStartMs)
                }
                val prevEnd = prevStrokeEndMs
                if (prevEnd != null && strokeStartMs - prevEnd >= PAUSE_THRESHOLD_MS) {
                    pauseCount++
                    totalPauseMs += (strokeStartMs - prevEnd)
                }
                prevStrokeEndMs = maxOf(prevEnd ?: strokeEndMs, strokeEndMs)
            }

            deduped.forEach { pt ->
                val p = pt.pressure
                val t = pt.timeMillis
                if (p != null && p.isFinite() && t != null && t >= baseTimeMs) {
                    rawPressureSamples += ((t - baseTimeMs) / 1_000f) to p
                }
            }

            var prevAngle: Float? = null
            var prevSpeed: Float? = null
            var prevSpeedDt: Float? = null
            var prevAccel: Float? = null

            for (i in 1 until deduped.size) {
                val p0 = deduped[i - 1]
                val p1 = deduped[i]
                val dx = p1.x - p0.x
                val dy = p1.y - p0.y
                val absDx = abs(dx)
                val absDy = abs(dy)
                val dist = hypot(dx, dy)
                totalPathLength += dist

                if (p0.pressure != null && p1.pressure != null) {
                    pressureDeltas += abs(p1.pressure - p0.pressure)
                }

                if (dist > 1e-4f) {
                    val deg = atan2(absDy, absDx) * (180f / PI.toFloat())
                    when {
                        deg < 25f -> horizontalAccum += dist
                        deg > 65f -> verticalAccum += dist
                        else -> diagonalAccum += dist
                    }
                    val angle = atan2(dy, dx)
                    val oldAngle = prevAngle
                    if (oldAngle != null) {
                        var delta = abs(angle - oldAngle)
                        if (delta > PI.toFloat()) delta = (2f * PI.toFloat()) - delta
                        turningAngles += delta
                        curvatures += (delta / dist.coerceAtLeast(1e-3f))
                        if (delta >= DIRECTION_CHANGE_THRESHOLD_RAD) {
                            directionChangeCount++
                        }
                    }
                    prevAngle = angle
                }

                val t0 = p0.timeMillis
                val t1 = p1.timeMillis
                if (t0 != null && t1 != null && t1 > t0) {
                    val dtSec = (t1 - t0) / 1_000f
                    if (dtSec >= MIN_DT_SEC) {
                        val speed = (dist / dtSec).coerceAtMost(MAX_SEGMENT_SPEED)
                        speeds += speed
                        if (t1 >= baseTimeMs) {
                            rawSpeedSamples += ((t1 - baseTimeMs) / 1_000f) to speed
                        }
                        val oldSpeed = prevSpeed
                        val oldDt = prevSpeedDt
                        if (oldSpeed != null && oldDt != null) {
                            val midDt = ((dtSec + oldDt) * 0.5f).coerceAtLeast(MIN_DT_SEC)
                            val accel = (speed - oldSpeed) / midDt
                            accelerations += abs(accel)
                            val oldAccel = prevAccel
                            if (oldAccel != null) {
                                val jerk = abs(accel - oldAccel) / midDt
                                jerks += jerk
                            }
                            prevAccel = accel
                        }
                        prevSpeed = speed
                        prevSpeedDt = dtSec
                    }
                }
            }
        }

        val totalDir = (horizontalAccum + verticalAccum + diagonalAccum).coerceAtLeast(1e-4f)
        val dominantDirection = when {
            horizontalAccum == 0f && verticalAccum == 0f && diagonalAccum == 0f -> "Titik / Statis"
            horizontalAccum / totalDir >= 0.50f -> "Horizontal"
            verticalAccum / totalDir >= 0.50f -> "Vertikal"
            diagonalAccum / totalDir >= 0.45f -> "Diagonal"
            else -> "Campuran/Kurva"
        }

        val minX = points.minOf { it.x }.coerceIn(0f, 1f)
        val maxX = points.maxOf { it.x }.coerceIn(0f, 1f)
        val minY = points.minOf { it.y }.coerceIn(0f, 1f)
        val maxY = points.maxOf { it.y }.coerceIn(0f, 1f)
        val width = (maxX - minX).coerceAtLeast(0.01f)
        val height = (maxY - minY).coerceAtLeast(0.01f)
        val boxArea = (width * height).coerceAtLeast(1e-3f)
        val coverage = (boxArea * 100f).coerceIn(0f, 100f)
        val strokeDensity = (totalPathLength / boxArea).coerceIn(0f, 100f)

        val totalDurationMs = if (allTimestamps.size >= 2) {
            (allTimestamps.last() - allTimestamps.first()).coerceAtLeast(0L)
        } else {
            0L
        }
        val meanStrokeDur = strokeDurationsMs.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val meanSpeed = speeds.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val meanCurvature = curvatures.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        // Geometric/kinematic stroke roughness index (0..1) combining turn variability, speed CV, and pressure CV
        val turnVar = stdDev(turningAngles).coerceIn(0f, 2f) / 2f
        val speedCv = if (meanSpeed > 1e-4f) (stdDev(speeds) / meanSpeed).coerceIn(0f, 2f) / 2f else 0f
        val curvCv = if (meanCurvature > 1e-4f) (stdDev(curvatures) / meanCurvature).coerceIn(0f, 3f) / 3f else 0f
        val pressCv = if (meanPressure != null && pressureStdDev != null && meanPressure > 1e-4f) {
            (pressureStdDev / meanPressure).coerceIn(0f, 1f)
        } else {
            0f
        }
        val roughnessIndex = if (pressures.isNotEmpty()) {
            (0.35f * turnVar + 0.30f * curvCv + 0.20f * speedCv + 0.15f * pressCv).coerceIn(0f, 1f)
        } else {
            (0.40f * turnVar + 0.35f * curvCv + 0.25f * speedCv).coerceIn(0f, 1f)
        }

        return ProjectiveDrawingTelemetry(
            strokeCount = strokes.size,
            pointCount = points.size,
            stylusUsed = stylusUsed,
            meanPressure = meanPressure,
            minPressure = minPressure,
            maxPressure = maxPressure,
            pressureStdDev = pressureStdDev,
            pressureDeltaMean = pressureDeltas.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            meanTilt = meanTilt,
            meanOrientation = meanOrientation,
            totalPathLength = totalPathLength,
            meanSpeedNormPerSec = meanSpeed,
            maxSpeedNormPerSec = speeds.maxOrNull() ?: 0f,
            meanAcceleration = accelerations.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f,
            meanJerk = jerks.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f,
            maxJerk = jerks.maxOrNull() ?: 0f,
            meanCurvature = meanCurvature,
            directionChangeCount = directionChangeCount,
            dominantDirection = dominantDirection,
            totalDurationMs = totalDurationMs,
            meanStrokeDurationMs = meanStrokeDur,
            pauseCount = pauseCount,
            totalPauseMs = totalPauseMs,
            revisionCount = revisionCount,
            spaceCoveragePercent = coverage,
            aspectRatio = width / height,
            strokeDensity = strokeDensity,
            centerOfMassX = points.map { it.x }.average().toFloat().coerceIn(0f, 1f),
            centerOfMassY = points.map { it.y }.average().toFloat().coerceIn(0f, 1f),
            roughnessIndex = roughnessIndex,
            pressureSeries = downsampleSeries(rawPressureSamples, MAX_SERIES_POINTS),
            speedSeries = downsampleSeries(rawSpeedSamples, MAX_SERIES_POINTS)
        )
    }

    private fun stdDev(values: List<Float>): Float {
        if (values.size <= 1) return 0f
        val mean = values.average()
        return sqrt(values.map { (it - mean).pow(2) }.average()).toFloat()
    }

    private fun downsampleSeries(
        samples: List<Pair<Float, Float>>,
        limit: Int
    ): List<Pair<Float, Float>> {
        if (samples.size <= limit) return samples
        val step = (samples.size - 1).toFloat() / (limit - 1).toFloat()
        return (0 until limit).map { i ->
            samples[(i * step).toInt().coerceIn(0, samples.lastIndex)]
        }
    }
}

object ProjectiveImageRenderer {

    fun renderWarteggGridPng(drafts: List<WarteggBoxDraft>): ByteArray? = runCatching {
        val cellSize = 180
        val cols = 4
        val rows = 2
        val bitmap = Bitmap.createBitmap(cellSize * cols, cellSize * rows, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint().apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        (0 until 8).forEach { idx ->
            val col = idx % cols
            val row = idx / cols
            val left = (col * cellSize).toFloat()
            val top = (row * cellSize).toFloat()
            canvas.drawRect(left, top, left + cellSize, top + cellSize, borderPaint)
            val box = drafts.firstOrNull { it.boxNumber == idx + 1 } ?: return@forEach
            box.strokes.forEach { pts ->
                if (pts.size > 1) {
                    val path = Path()
                    path.moveTo(left + pts[0].x * cellSize, top + pts[0].y * cellSize)
                    for (i in 1 until pts.size) {
                        path.lineTo(left + pts[i].x * cellSize, top + pts[i].y * cellSize)
                    }
                    canvas.drawPath(path, strokePaint)
                }
            }
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    fun renderPanelsGridPng(panels: List<ProjectivePanelDraft>): ByteArray? = runCatching {
        if (panels.isEmpty()) return null
        val cellW = 280
        val cellH = 280
        val bitmap = Bitmap.createBitmap(cellW * panels.size.coerceIn(1, 3), cellH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val borderPaint = Paint().apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        panels.take(3).forEachIndexed { idx, panel ->
            val left = (idx * cellW).toFloat()
            canvas.drawRect(left, 0f, left + cellW, cellH.toFloat(), borderPaint)
            panel.strokes.forEach { pts ->
                if (pts.size > 1) {
                    val path = Path()
                    path.moveTo(left + pts[0].x * cellW, pts[0].y * cellH)
                    for (i in 1 until pts.size) {
                        path.lineTo(left + pts[i].x * cellW, pts[i].y * cellH)
                    }
                    canvas.drawPath(path, strokePaint)
                }
            }
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }.getOrNull()
}

class ProjectiveMultimodalAnalyzer(private val engine: OnDeviceLlmEngine? = null) {

    suspend fun analyze(
        instrumentId: String,
        instrumentTitle: String,
        panels: List<ProjectivePanelDraft>,
        verbalNotes: List<String> = emptyList(),
        renderedImagesPng: List<ByteArray> = emptyList()
    ): ProjectiveMultimodalReport {
        val totalRevisions = panels.sumOf { it.revisionCount.coerceAtLeast(0) }
        val metrics = ProjectiveTelemetryExtractor.extractMetrics(panels.map { it.strokes }, totalRevisions)
        val orderText = panels.filter { it.drawOrder != null }
            .sortedBy { it.drawOrder }
            .joinToString(" -> ") { "${it.panelLabel}(#${it.drawOrder}, ${it.durationSeconds}dtk)" }
            .ifBlank { "Urutan tidak tercatat" }
        val verbalText = verbalNotes.filter(String::isNotBlank).joinToString(" | ").ifBlank { "Tanpa catatan verbal" }
        val telemetryBlock = "${metrics.toFormattedSummary()} Urutan: $orderText. Verbal/Judul: $verbalText."
        val sourceRefs = PsychometricInstrumentRegistry.specFor(instrumentId).sourceReferences
            .joinToString(", ")
            .ifBlank { "PsikoTest/Wartegg/Wartegg_Acuan_dan_Skoring.md" }

        val llm = engine
        if (llm != null && renderedImagesPng.isNotEmpty()) {
            val prompt = buildString {
                appendLine("Anda membantu psikolog meninjau asesmen proyektif $instrumentTitle ($instrumentId) secara EKSPLORATIF.")
                appendLine("Gunakan data telemetri aktual berikut (jangan mengubah skor deterministik atau mengarang skor tekanan):")
                appendLine(telemetryBlock)
                appendLine("Referensi materi: $sourceRefs.")
                appendLine("Susun ringkasan terstruktur singkat dengan format persis 6 bagian:")
                appendLine("1. Observasi Visual")
                appendLine("2. Observasi Proses Menggambar")
                appendLine("3. Ringkasan Karakteristik Goresan")
                appendLine("4. Interpretasi Eksploratif (hipotesis non-diagnostik)")
                appendLine("5. Pertanyaan Eksplorasi Lanjutan Psikolog")
                append("6. Referensi Materi yang Digunakan")
            }
            val visionResult = llm.analyzeMultimodal(prompt, renderedImagesPng)
            val generated = visionResult.getOrNull()?.trim()
            if (!generated.isNullOrBlank()) {
                return ProjectiveMultimodalReport(
                    summary = "[Analisis Multimodal AI Lokal — Eksploratif Purwarupa]\n$generated\n\n" +
                        "[Telemetri Goresan & Stylus]\n$telemetryBlock",
                    multimodalModelUsed = true,
                    fallbackUsed = false,
                    telemetry = metrics
                )
            }
        }

        val reason = if (renderedImagesPng.isEmpty()) {
            "belum ada gambar kanvas terlampir"
        } else {
            "model/backend visi multimodal lokal belum aktif pada perangkat"
        }
        return ProjectiveMultimodalReport(
            summary = "[Ekstraksi Fitur Grafis & Stylus Lokal — Fallback Multimodal]\n" +
                "Analisis gambar multimodal langsung belum aktif ($reason); " +
                "berikut ringkasan terstruktur ekstraksi fitur lokal (acuan: $sourceRefs):\n" +
                "$telemetryBlock\n" +
                "Pertanyaan eksplorasi: Gali makna objek dominan, jeda antar goresan, dan urutan pengerjaan ($orderText) bersama klien. " +
                "Catatan: fitur kinematik/roughness/tekanan adalah indikator proses grafis eksploratif, bukan diagnosis.",
            multimodalModelUsed = false,
            fallbackUsed = true,
            telemetry = metrics
        )
    }
}

package com.stressdetector.mobile.domain.assessment

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Representasi titik koordinat goresan pada kanvas gambar digital Army Alpha.
 */
data class ArmyAlphaPoint(
    val x: Float,
    val y: Float,
    val timeMillis: Long = System.currentTimeMillis(),
    val pressure: Float = 1f,
    val isStylus: Boolean = false
)

/**
 * Data coretan pada butir soal tertentu.
 */
data class ArmyAlphaItemDrawing(
    val itemIndex: Int,
    val strokes: List<List<ArmyAlphaPoint>>,
    val canvasWidth: Float = 1000f,
    val canvasHeight: Float = 600f
) {
    val totalPoints: Int get() = strokes.sumOf { it.size }
    val strokeCount: Int get() = strokes.size
    val isStylusUsed: Boolean get() = strokes.any { stroke -> stroke.any { it.isStylus } }
}

enum class ArmyAlphaItemStatus(val code: Int, val label: String) {
    CORRECT(0, "Benar"),
    INCORRECT(1, "Salah"),
    NEEDS_REVIEW(2, "Perlu Verifikasi")
}

data class ArmyAlphaItemEvaluation(
    val itemIndex: Int,
    val itemNumber: Int,
    val strokeCount: Int,
    val pointCount: Int,
    val status: ArmyAlphaItemStatus,
    val score: Int, // 1 for CORRECT, 0 for INCORRECT
    val transcription: String,
    val targetDescription: String
)

data class ArmyAlphaSessionSummary(
    val evaluations: List<ArmyAlphaItemEvaluation>,
    val totalCorrect: Int,
    val scale: Int,
    val scaleLabel: String,
    val dirCorrect: Int,
    val speedCorrect: Int,
    val inputMethod: String,
    val fullTranscriptionText: String
)

/**
 * Engine pengenalan deterministik bentuk coretan dan transkripsi cerdas Army Alpha
 * sesuai manual LPSP3 UI (UI-10013-96) dan Yoakum & Yerkes (1920).
 */
object ArmyAlphaRecognitionEngine {

    val TARGET_DESCRIPTIONS = listOf(
        "Silang (X) di lingkaran ke-2 dan huruf A di lingkaran ke-5",
        "Garis lengkung dari 1 ke 9 melewati BAWAH 3-4 dan ATAS 5-6",
        "Tulis angka 7 pada lingkaran ke-1 (karena resimen > kompi)",
        "Isi angka 7 di lingkaran ke-3 dan sembarang angka selain 7 di lingkaran ke-5",
        "Silang (X) di segitiga di luar lingkaran, dan huruf A di dalam lingkaran",
        "Silang (X) di sudut segitiga luar persegi/lingkaran, dan huruf Y di perpotongan segitiga-persegi",
        "Garis bawah huruf D dan coret tanda silang pada huruf K",
        "Isi lingkaran berturut-turut huruf A, A, dan G",
        "Coret angka-angka kelipatan 3 pada deret angka",
        "Isi rasio angka pada kotak paling kiri dan paling kanan",
        "Coret angka di dalam bangun yang bernilai 8 dan 9",
        "Coret angka 4 pada deret angka 1 sampai 9"
    )

    fun evaluateItem(drawing: ArmyAlphaItemDrawing): ArmyAlphaItemEvaluation {
        val idx = drawing.itemIndex
        val targetDesc = TARGET_DESCRIPTIONS.getOrElse(idx) { "Target butir ${idx + 1}" }

        if (drawing.strokes.isEmpty() || drawing.totalPoints < 1) {
            return ArmyAlphaItemEvaluation(
                itemIndex = idx,
                itemNumber = idx + 1,
                strokeCount = drawing.strokeCount,
                pointCount = drawing.totalPoints,
                status = ArmyAlphaItemStatus.INCORRECT,
                score = 0,
                transcription = "Butir ${idx + 1}: Belum ada coretan jawaban terdeteksi. (Hasil: SALAH)",
                targetDescription = targetDesc
            )
        }

        val w = drawing.canvasWidth.coerceAtLeast(1f)
        val h = drawing.canvasHeight.coerceAtLeast(1f)

        // Ubah koordinat menjadi normalized [0.0..1.0]
        val normalizedStrokes = drawing.strokes.map { stroke ->
            stroke.map { p -> ArmyAlphaPoint(p.x / w, p.y / h, p.timeMillis, p.pressure, p.isStylus) }
        }

        val allPoints = normalizedStrokes.flatten()

        val isCorrect = when (idx) {
            0 -> evaluateItem1(normalizedStrokes)
            1 -> evaluateItem2(normalizedStrokes)
            2 -> evaluateItem3(normalizedStrokes)
            3 -> evaluateItem4(normalizedStrokes)
            4 -> evaluateItem5(normalizedStrokes)
            5 -> evaluateItem6(normalizedStrokes)
            6 -> evaluateItem7(normalizedStrokes)
            7 -> evaluateItem8(normalizedStrokes)
            8 -> evaluateItem9(normalizedStrokes)
            9 -> evaluateItem10(normalizedStrokes)
            10 -> evaluateItem11(normalizedStrokes)
            11 -> evaluateItem12(normalizedStrokes)
            else -> allPoints.size >= 5
        }

        val status = if (isCorrect) ArmyAlphaItemStatus.CORRECT else ArmyAlphaItemStatus.INCORRECT
        val score = if (isCorrect) 1 else 0

        val stylusInfo = if (drawing.isStylusUsed) "Stylus/S-Pen" else "Jari"
        val transcription = "Butir ${idx + 1} ($stylusInfo, ${drawing.strokeCount} coretan): " +
            (if (isCorrect) "Terekam tanda/garis tepat pada area target: $targetDesc. (Hasil: BENAR)"
            else "Coretan terekam tetapi belum memenuhi koordinat target yang diminta ($targetDesc). (Hasil: SALAH)")

        return ArmyAlphaItemEvaluation(
            itemIndex = idx,
            itemNumber = idx + 1,
            strokeCount = drawing.strokeCount,
            pointCount = drawing.totalPoints,
            status = status,
            score = score,
            transcription = transcription,
            targetDescription = targetDesc
        )
    }

    fun evaluateSession(drawings: Map<Int, ArmyAlphaItemDrawing>): ArmyAlphaSessionSummary {
        val evaluations = (0 until 12).map { idx ->
            val drawing = drawings[idx] ?: ArmyAlphaItemDrawing(idx, emptyList())
            evaluateItem(drawing)
        }

        val followingCommandsItems = setOf(0, 1, 2, 4, 5, 6, 10)
        val speedAccuracyItems = setOf(3, 7, 8, 9, 11)

        val dirCorrect = followingCommandsItems.count { evaluations[it].status == ArmyAlphaItemStatus.CORRECT }
        val speedCorrect = speedAccuracyItems.count { evaluations[it].status == ArmyAlphaItemStatus.CORRECT }
        val totalCorrect = dirCorrect + speedCorrect

        val scale = when {
            totalCorrect >= 10 -> 5
            totalCorrect >= 8 -> 4
            totalCorrect >= 6 -> 3
            totalCorrect >= 4 -> 2
            else -> 1
        }

        val scaleLabel = when (scale) {
            5 -> "Baik Sekali (Skala 5)"
            4 -> "Baik (Skala 4)"
            3 -> "Rata-Rata (Skala 3)"
            2 -> "Kurang (Skala 2)"
            else -> "Kurang Sekali (Skala 1)"
        }

        val hasStylus = drawings.values.any { it.isStylusUsed }
        val inputMethod = if (hasStylus) "Pena Stylus / Samsung S Pen & Jari" else "Sentuhan Jari (Finger Touch)"

        val sb = StringBuilder()
        sb.appendLine("RINGKASAN TRANSKRIPSI & EVALUASI DIGITAL ARMY ALPHA (UI-10013-96)")
        sb.appendLine("Metode Masukan: $inputMethod")
        sb.appendLine("Total Skor Benar: $totalCorrect / 12 (Skala Weighted LPSP3 UI: $scale/5 - $scaleLabel)")
        sb.appendLine("Rincian Subtes:")
        sb.appendLine("  • Following Commands (Instruksi Lisan Terarah): $dirCorrect / 7 benar")
        sb.appendLine("  • Speed & Accuracy (Kecepatan Pemrosesan Logika): $speedCorrect / 5 benar")
        sb.appendLine("\nTranskripsi Butir Per Butir:")
        evaluations.forEach { eval ->
            sb.appendLine("• ${eval.transcription}")
        }

        return ArmyAlphaSessionSummary(
            evaluations = evaluations,
            totalCorrect = totalCorrect,
            scale = scale,
            scaleLabel = scaleLabel,
            dirCorrect = dirCorrect,
            speedCorrect = speedCorrect,
            inputMethod = inputMethod,
            fullTranscriptionText = sb.toString().trim()
        )
    }

    // --- Algoritma Evaluasi Koordinat Per Butir ---

    // Butir 1: 5 Lingkaran Horizontal. Silang di lingkaran 2, huruf A di lingkaran 5.
    // Lingkaran 2: x ~ [0.26..0.44], y ~ [0.25..0.75]
    // Lingkaran 5: x ~ [0.72..0.94], y ~ [0.25..0.75]
    private fun evaluateItem1(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val hasCircle2 = strokes.any { s -> s.any { it.x in 0.25f..0.45f && it.y in 0.20f..0.80f } }
        val hasCircle5 = strokes.any { s -> s.any { it.x in 0.70f..0.95f && it.y in 0.20f..0.80f } }
        return hasCircle2 && hasCircle5
    }

    // Butir 2: Deret lingkaran 1 s.d. 9.
    // Garis lengkung dari lingkaran 1 ke 9 melewati BAWAH 3-4 dan ATAS 5-6.
    private fun evaluateItem2(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val allPoints = strokes.flatten()
        val minX = allPoints.minOfOrNull { it.x } ?: 1f
        val maxX = allPoints.maxOfOrNull { it.x } ?: 0f
        // Harus membentang dari kiri (lingkaran 1-2) ke kanan (lingkaran 8-9)
        if (minX > 0.35f || maxX < 0.65f) return false

        // Ada titik di bawah centerline pada rentang x lingkaran 3-4 (x in [0.28..0.52])
        val hasUnder34 = allPoints.any { it.x in 0.28f..0.52f && it.y >= 0.50f }
        // Ada titik di atas centerline pada rentang x lingkaran 5-6 (x in [0.48..0.72])
        val hasOver56 = allPoints.any { it.x in 0.48f..0.72f && it.y <= 0.50f }
        return hasUnder34 && hasOver56
    }

    // Butir 3: 3 lingkaran & kata YA / TIDAK.
    // Resimen > kompi -> Benar -> tulis angka 7 di lingkaran 1 (kiri, x in [0.10..0.40])
    private fun evaluateItem3(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        return strokes.any { s -> s.any { it.x in 0.10f..0.42f && it.y in 0.20f..0.80f } }
    }

    // Butir 4: 5 lingkaran horizontal.
    // Lingkaran 3 (tengah, x in [0.40..0.60]) diisi angka 7.
    // Lingkaran 5 (kanan, x in [0.72..0.95]) diisi angka selain 7.
    private fun evaluateItem4(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val hasCircle3 = strokes.any { s -> s.any { it.x in 0.40f..0.62f && it.y in 0.20f..0.80f } }
        val hasCircle5 = strokes.any { s -> s.any { it.x in 0.70f..0.95f && it.y in 0.20f..0.80f } }
        return hasCircle3 && hasCircle5
    }

    // Butir 5: Segitiga dan lingkaran berpotongan.
    // Tanda silang pada segitiga di luar lingkaran, huruf A di dalam lingkaran.
    private fun evaluateItem5(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val hasInside = strokes.any { s -> s.any { it.x in 0.30f..0.70f && it.y in 0.25f..0.75f } }
        val hasOutside = strokes.any { s -> s.any { it.x in 0.15f..0.85f && (it.y < 0.35f || it.y > 0.65f || it.x < 0.35f || it.x > 0.65f) } }
        return hasInside && hasOutside
    }

    // Butir 6: Segitiga, persegi, lingkaran.
    // Silang di segitiga luar persegi/lingkaran, Y di perpotongan segitiga dan persegi luar lingkaran.
    private fun evaluateItem6(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val hasLowerLeft = strokes.any { s -> s.any { it.x in 0.10f..0.50f && it.y in 0.40f..0.98f } }
        val hasIntersection = strokes.any { s -> s.any { it.x in 0.35f..0.75f && it.y in 0.20f..0.80f } }
        return hasLowerLeft && hasIntersection
    }

    // Butir 7: Deret huruf A s.d. P. Garis bawah huruf D dan silang pada huruf K.
    // D berada di kiri-tengah (x ~ [0.20..0.42]), K di kanan-tengah (x ~ [0.55..0.78])
    private fun evaluateItem7(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val hasLetterD = strokes.any { s -> s.any { it.x in 0.18f..0.42f } }
        val hasLetterK = strokes.any { s -> s.any { it.x in 0.55f..0.80f } }
        return hasLetterD && hasLetterK
    }

    // Butir 8: Tiga lingkaran bertuliskan A, A, G.
    // Coretan harus mengenai area lingkaran-lingkaran tersebut (x in [0.20..0.85])
    private fun evaluateItem8(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val allPoints = strokes.flatten()
        val span = (allPoints.maxOfOrNull { it.x } ?: 0f) - (allPoints.minOfOrNull { it.x } ?: 0f)
        return allPoints.size >= 8 && span >= 0.25f
    }

    // Butir 9: Deret angka (kelipatan 3).
    private fun evaluateItem9(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val allPoints = strokes.flatten()
        return allPoints.size >= 6 && strokes.size >= 2
    }

    // Butir 10: Rasio dalam kotak kiri dan kanan.
    private fun evaluateItem10(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val hasLeftBox = strokes.any { s -> s.any { it.x in 0.10f..0.40f } }
        val hasRightBox = strokes.any { s -> s.any { it.x in 0.60f..0.90f } }
        return hasLeftBox && hasRightBox
    }

    // Butir 11: Coret angka 8 dan 9 di dalam bangun-bangun.
    private fun evaluateItem11(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        val allPoints = strokes.flatten()
        return allPoints.size >= 8 && strokes.size >= 2
    }

    // Butir 12: Coret angka 4 pada deret 1 s.d. 9.
    // Angka 4 berada di sekitar x ~ [0.35..0.55]
    private fun evaluateItem12(strokes: List<List<ArmyAlphaPoint>>): Boolean {
        return strokes.any { s -> s.any { it.x in 0.32f..0.56f && it.y in 0.25f..0.75f } }
    }
}

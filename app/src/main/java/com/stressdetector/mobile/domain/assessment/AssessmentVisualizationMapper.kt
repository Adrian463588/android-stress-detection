package com.stressdetector.mobile.domain.assessment

data class AccessibleScoreRow(
    val label: String,
    val valueText: String,
    val rangeText: String,
    val note: String = ""
)

enum class ChartLineStyle {
    SOLID,
    DASHED,
    DOTTED
}

enum class ChartPointShape {
    CIRCLE,
    SQUARE,
    DIAMOND
}

data class ChartSeries(
    val id: String,
    val label: String,
    val values: List<Double>,
    val minimum: Double,
    val maximum: Double,
    val lineStyle: ChartLineStyle,
    val pointShape: ChartPointShape
)

data class ScaleBarDatum(
    val label: String,
    val shortLabel: String,
    val value: Double,
    val minimum: Double,
    val maximum: Double,
    val valueText: String,
    val note: String = "",
    val flagged: Boolean = false
)

data class ScalePointDatum(
    val code: String,
    val label: String,
    val value: Double,
    val valueText: String,
    val elevated: Boolean
)

data class TimeSeriesPointDatum(
    val index: Int,
    val label: String,
    val value: Double,
    val errorCount: Int = 0,
    val skipCount: Int = 0
)

data class ScoreBandDatum(
    val label: String,
    val start: Double,
    val end: Double
)

data class BipolarAxisDatum(
    val dimensionLabel: String,
    val leftCode: String,
    val leftLabel: String,
    val leftValue: Double,
    val rightCode: String,
    val rightLabel: String,
    val rightValue: Double,
    val totalMax: Double,
    val dominantCode: String
)

data class RadarAxisDatum(
    val code: String,
    val label: String,
    val normalizedFraction: Float,
    val valueText: String
)

data class KpiMetricDatum(
    val label: String,
    val value: String,
    val subtitle: String = ""
)

data class CodingStatusRow(
    val component: String,
    val status: String
)

sealed interface AssessmentVisualizationModel {
    val title: String
    val subtitle: String
    val tableRows: List<AccessibleScoreRow>

    data class DiscProfileLineModel(
        override val title: String,
        override val subtitle: String,
        val axisLabels: List<String>,
        val series: List<ChartSeries>,
        val primarySummary: String,
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class MmpiClinicalProfileModel(
        override val title: String,
        override val subtitle: String,
        val editionLabel: String,
        val validityScales: List<ScaleBarDatum>,
        val clinicalScales: List<ScalePointDatum>,
        val clinicalMin: Double,
        val clinicalMax: Double,
        val referenceLineValue: Double?,
        val referenceLabel: String?,
        val validityVerified: Boolean,
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class HorizontalBarChartModel(
        override val title: String,
        override val subtitle: String,
        val bars: List<ScaleBarDatum>,
        val radarAxes: List<RadarAxisDatum> = emptyList(),
        val kpiMetrics: List<KpiMetricDatum> = emptyList(),
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class BipolarPreferenceModel(
        override val title: String,
        override val subtitle: String,
        val primaryTypeCode: String,
        val axes: List<BipolarAxisDatum>,
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class RankedBarChartModel(
        override val title: String,
        override val subtitle: String,
        val bars: List<ScaleBarDatum>,
        val lowerIsHigherInterest: Boolean,
        val dominantProfileCode: String? = null,
        val radarAxes: List<RadarAxisDatum> = emptyList(),
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class TimeSeriesCurveModel(
        override val title: String,
        override val subtitle: String,
        val points: List<TimeSeriesPointDatum>,
        val meanValue: Double?,
        val totalErrors: Int,
        val totalSkips: Int,
        val summaryMetrics: List<ScaleBarDatum>,
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class ScoreIndicatorModel(
        override val title: String,
        override val subtitle: String,
        val primaryScore: ScaleBarDatum,
        val bands: List<ScoreBandDatum>,
        val subscales: List<ScaleBarDatum>,
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class WarteggEightBoxModel(
        override val title: String,
        override val subtitle: String,
        val boxes: List<WarteggBoxDraft>,
        val sequenceOrder: List<Int>,
        val totalDurationSeconds: Int,
        val codingStatusRows: List<CodingStatusRow> = emptyList(),
        val telemetry: ProjectiveDrawingTelemetry? = null,
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class ExaminerCodedProfileModel(
        override val title: String,
        override val subtitle: String,
        val systemId: String,
        val responseCount: Int,
        val locationSummary: String,
        val observations: List<AssessmentObservation>,
        val examinerCodes: Map<String, String>,
        val bars: List<ScaleBarDatum> = emptyList(),
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class ObservationMatrixModel(
        override val title: String,
        override val subtitle: String,
        val reviewStatus: ExaminerReviewStatus,
        val observations: List<AssessmentObservation>,
        val examinerCodes: Map<String, String>,
        val panels: List<ProjectivePanelDraft> = emptyList(),
        val bars: List<ScaleBarDatum> = emptyList(),
        val telemetry: ProjectiveDrawingTelemetry? = null,
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel

    data class StatusOnlyModel(
        override val title: String,
        override val subtitle: String,
        val status: InstrumentImplementationStatus,
        val reason: String,
        val normNote: String,
        override val tableRows: List<AccessibleScoreRow>
    ) : AssessmentVisualizationModel
}

@Suppress("MagicNumber", "CyclomaticComplexMethod", "LongMethod")
object AssessmentVisualizationMapper {

    fun map(result: AssessmentResult): AssessmentVisualizationModel {
        val status = result.resolvedScoringStatus()
        val rows = result.scores.map { score ->
            AccessibleScoreRow(
                label = score.label,
                valueText = score.displayValue(),
                rangeText = score.displayRange(),
                note = score.meaning
            )
        }
        val normNote = result.normReference
            ?: PsychometricInstrumentRegistry.specFor(result.instrumentId).normStatus

        if (result.isLegacyUnverified() && result.scores.isEmpty()) {
            return AssessmentVisualizationModel.StatusOnlyModel(
                title = result.title,
                subtitle = "Hasil historis sebelum validasi versi",
                status = status,
                reason = result.aiIneligibilityReason()
                    ?: "Hasil historis tidak memiliki skema versi yang terverifikasi.",
                normNote = normNote,
                tableRows = rows
            )
        }

        if (result.scores.isEmpty() &&
            result.observations.isEmpty() &&
            result.warteggDrafts.isEmpty() &&
            result.rorschachResponses.isEmpty() &&
            result.projectivePanels.isEmpty()
        ) {
            return AssessmentVisualizationModel.StatusOnlyModel(
                title = result.title,
                subtitle = status.displayLabel,
                status = status,
                reason = result.validityWarnings.firstOrNull()
                    ?: result.interpretation,
                normNote = normNote,
                tableRows = rows
            )
        }

        return when (result.resolvedVisualizationType()) {
            AssessmentVisualizationType.PROFILE_LINE_CHART -> mapDisc(result, rows)
            AssessmentVisualizationType.CLINICAL_PROFILE_CHART -> mapMmpi(result, rows)
            AssessmentVisualizationType.BIPOLAR_PREFERENCE -> mapMbti(result, rows)
            AssessmentVisualizationType.TIME_SERIES_CURVE -> mapTimeSeries(result, rows)
            AssessmentVisualizationType.RANKED_BAR -> mapRanked(result, rows)
            AssessmentVisualizationType.SCORE_INDICATOR -> mapScoreIndicator(result, rows)
            AssessmentVisualizationType.WARTEGG_EIGHT_BOX -> mapWartegg(result, rows)
            AssessmentVisualizationType.EXAMINER_CODED_PROFILE -> mapRorschach(result, rows)
            AssessmentVisualizationType.OBSERVATION_MATRIX -> mapObservationMatrix(result, rows)
            AssessmentVisualizationType.STATUS_ONLY -> AssessmentVisualizationModel.StatusOnlyModel(
                title = result.title,
                subtitle = status.displayLabel,
                status = status,
                reason = result.validityWarnings.firstOrNull() ?: result.interpretation,
                normNote = normNote,
                tableRows = rows
            )
            AssessmentVisualizationType.HORIZONTAL_BAR -> mapHorizontal(result, rows)
        }
    }

    private fun mapDisc(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        val axes = listOf("D", "I", "S", "C")
        val names = listOf("Dominance", "Influence", "Steadiness", "Compliance")
        val profilA = axes.indices.mapNotNull { idx ->
            result.scores.firstOrNull {
                it.label.contains("Profil A", ignoreCase = true) && it.label.contains(names[idx], ignoreCase = true)
            }?.value
        }
        val profilB = axes.indices.mapNotNull { idx ->
            result.scores.firstOrNull {
                it.label.contains("Profil B", ignoreCase = true) && it.label.contains(names[idx], ignoreCase = true)
            }?.value
        }
        val profilC = axes.indices.mapNotNull { idx ->
            result.scores.firstOrNull { score ->
                (score.label.contains("Profil C", ignoreCase = true) && score.label.endsWith(" ${axes[idx]}")) ||
                    score.label.equals(axes[idx], ignoreCase = true)
            }?.value
        }
        val markedMost = axes.indices.mapNotNull { idx ->
            result.scores.firstOrNull {
                it.label.contains("${axes[idx]} Most", ignoreCase = true)
            }?.value
        }
        val markedLeast = axes.indices.mapNotNull { idx ->
            result.scores.firstOrNull {
                it.label.contains("${axes[idx]} Least", ignoreCase = true)
            }?.value
        }

        val seriesAValues = if (profilA.size == 4) profilA else markedMost
        val seriesBValues = if (profilB.size == 4) profilB else markedLeast
        val series = buildList {
            if (seriesAValues.size == 4) {
                add(
                    ChartSeries(
                        id = "profil_a",
                        label = "Profil A (Most)",
                        values = seriesAValues,
                        minimum = 0.0,
                        maximum = 24.0,
                        lineStyle = ChartLineStyle.SOLID,
                        pointShape = ChartPointShape.CIRCLE
                    )
                )
            }
            if (seriesBValues.size == 4) {
                add(
                    ChartSeries(
                        id = "profil_b",
                        label = "Profil B (Least)",
                        values = seriesBValues,
                        minimum = 0.0,
                        maximum = 24.0,
                        lineStyle = ChartLineStyle.DASHED,
                        pointShape = ChartPointShape.SQUARE
                    )
                )
            }
            if (profilC.size == 4) {
                add(
                    ChartSeries(
                        id = "profil_c",
                        label = "Profil C (Selisih)",
                        values = profilC,
                        minimum = -24.0,
                        maximum = 24.0,
                        lineStyle = ChartLineStyle.DOTTED,
                        pointShape = ChartPointShape.DIAMOND
                    )
                )
            }
        }

        if (series.isEmpty()) return mapHorizontal(result, rows)

        val topAxisIndex = (if (profilC.size == 4) profilC else seriesAValues)
            .withIndex()
            .maxByOrNull { it.value }
            ?.index ?: 0
        val primarySummary = "${axes[topAxisIndex]} — ${names[topAxisIndex]} " +
            "(skor selisih mentah tertinggi pada formulir ini)"

        return AssessmentVisualizationModel.DiscProfileLineModel(
            title = "Profil Perilaku DISC-24",
            subtitle = "Grafik garis skor mentah Most, Least, dan selisih D-I-S-C",
            axisLabels = axes,
            series = series,
            primarySummary = primarySummary,
            tableRows = rows
        )
    }

    private fun mapMmpi(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        val isClassicMarkedSheet = result.fromMarkedSheet
        val validityPrefixes = if (isClassicMarkedSheet) {
            listOf("L", "F", "K")
        } else {
            listOf("F-r", "L-r", "K-r", "SUI")
        }
        val validityScores = result.scores.filter { score ->
            validityPrefixes.any { prefix ->
                score.label.startsWith("$prefix ", ignoreCase = true) ||
                    score.label.equals(prefix, ignoreCase = true) ||
                    score.label.startsWith("$prefix(", ignoreCase = true)
            }
        }.map { score ->
            val short = score.label.substringBefore(" ")
            val flagged = score.label.startsWith("SUI", ignoreCase = true) && score.value > 0.0 ||
                (isClassicMarkedSheet && score.label.startsWith("F", ignoreCase = true) && score.value >= 80.0) ||
                (isClassicMarkedSheet && score.label.startsWith("L", ignoreCase = true) && score.value >= 70.0)
            ScaleBarDatum(
                label = score.label,
                shortLabel = short,
                value = score.value,
                minimum = score.minimum,
                maximum = score.maximum,
                valueText = score.displayValue(),
                note = score.meaning,
                flagged = flagged
            )
        }

        val clinicalScores = result.scores
            .filterNot { score ->
                score.label.startsWith("Ringkasan", ignoreCase = true) ||
                    validityPrefixes.any { prefix ->
                        score.label.startsWith("$prefix ", ignoreCase = true) ||
                            score.label.equals(prefix, ignoreCase = true) ||
                            score.label.startsWith("$prefix(", ignoreCase = true)
                    }
            }
            .map { score ->
                val code = score.label
                    .removePrefix("H-O ")
                    .substringBefore(" ")
                    .substringBefore("(")
                    .trim()
                val threshold = if (isClassicMarkedSheet) 65.0 else Double.MAX_VALUE
                ScalePointDatum(
                    code = code,
                    label = score.label,
                    value = score.value,
                    valueText = score.displayValue(),
                    elevated = score.value >= threshold
                )
            }

        return AssessmentVisualizationModel.MmpiClinicalProfileModel(
            title = if (isClassicMarkedSheet) "MMPI-2 Klasik — Profil Klinis" else "MMPI-2-RF — Profil Skor Mentah",
            subtitle = if (isClassicMarkedSheet) {
                "Sumbu Y: T-score rekap lembar. Garis T=65 bersifat referensi panduan, bukan cutoff tunggal."
            } else {
                "Skor mentah domain validitas & klinis (belum dikonversi ke skor T standar)."
            },
            editionLabel = if (isClassicMarkedSheet) "MMPI-2 Klasik (Rekap Lembar)" else "MMPI-2-RF (Skor Mentah)",
            validityScales = validityScores,
            clinicalScales = clinicalScores,
            clinicalMin = if (isClassicMarkedSheet) 30.0 else 0.0,
            clinicalMax = if (isClassicMarkedSheet) 120.0 else (clinicalScores.maxOfOrNull { it.value }?.coerceAtLeast(24.0) ?: 24.0),
            referenceLineValue = if (isClassicMarkedSheet) 65.0 else null,
            referenceLabel = if (isClassicMarkedSheet) "Referensi T = 65" else null,
            validityVerified = result.resolvedValidityStatus() == AssessmentValidityStatus.VALID &&
                result.resolvedReviewStatus() == ExaminerReviewStatus.APPROVED,
            tableRows = rows
        )
    }

    private fun mapTimeSeries(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        val pointObs = result.observations.filter {
            it.key.startsWith("pauli_interval_") || it.key.startsWith("kraepelin_col_")
        }
        val points = pointObs.mapIndexedNotNull { idx, obs ->
            val v = obs.value.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val err = obs.tags.firstOrNull { it.startsWith("err=") }?.removePrefix("err=")?.toIntOrNull() ?: 0
            val skip = obs.tags.firstOrNull { it.startsWith("skip=") }?.removePrefix("skip=")?.toIntOrNull() ?: 0
            TimeSeriesPointDatum(
                index = obs.orderIndex ?: (idx + 1),
                label = obs.label,
                value = v,
                errorCount = err,
                skipCount = skip
            )
        }
        val summaryBars = result.scores.map { score ->
            ScaleBarDatum(
                label = score.label,
                shortLabel = score.label.substringBefore(" ("),
                value = score.value,
                minimum = score.minimum,
                maximum = score.maximum.coerceAtLeast(score.value),
                valueText = score.displayValue(),
                note = score.meaning
            )
        }
        if (points.isEmpty()) {
            return AssessmentVisualizationModel.HorizontalBarChartModel(
                title = "${result.title} — Ringkasan Indeks",
                subtitle = "Kurva interval rinci tidak tersedia; menampilkan ringkasan skor.",
                bars = summaryBars,
                tableRows = rows
            )
        }
        val meanValue = points.map { it.value }.average()
        return AssessmentVisualizationModel.TimeSeriesCurveModel(
            title = "Kurva Kerja ${result.title}",
            subtitle = "${points.size} interval kerja dengan garis rerata & hitungan kesalahan/lewatan",
            points = points,
            meanValue = meanValue,
            totalErrors = points.sumOf { it.errorCount },
            totalSkips = points.sumOf { it.skipCount },
            summaryMetrics = summaryBars,
            tableRows = rows
        )
    }

    private fun mapRanked(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        val canonicalId = PsychometricInstrumentRegistry.normalizeInstrumentId(result.instrumentId)
        val isRmib = canonicalId == "rmib"
        val displayScores = result.scores.filterNot {
            it.label.startsWith("Total", ignoreCase = true) || it.label.startsWith("Diferensiasi", ignoreCase = true)
        }
        val sorted = if (isRmib) displayScores.sortedBy { it.value } else displayScores.sortedByDescending { it.value }
        val bars = sorted.map { score ->
            ScaleBarDatum(
                label = score.label,
                shortLabel = score.label.substringBefore(" ["),
                value = score.value,
                minimum = score.minimum,
                maximum = score.maximum.coerceAtLeast(1.0),
                valueText = score.displayValue(),
                note = score.meaning
            )
        }
        val hollandCode = if (canonicalId == "holland" && sorted.size >= 3) {
            sorted.take(3).joinToString("") { s ->
                s.label.substringAfter("(", "").substringBefore(")", s.label.take(1)).take(1)
            }
        } else {
            null
        }
        val hollandRadar = if (canonicalId == "holland" && displayScores.size >= 3) {
            displayScores.map { score ->
                val span = (score.maximum - score.minimum).coerceAtLeast(1.0)
                RadarAxisDatum(
                    code = score.label.substringAfter("(", "").substringBefore(")", score.label.take(1)),
                    label = score.label,
                    normalizedFraction = ((score.value - score.minimum) / span).toFloat().coerceIn(0f, 1f),
                    valueText = score.displayValue()
                )
            }
        } else {
            emptyList()
        }
        return AssessmentVisualizationModel.RankedBarChartModel(
            title = "Peringkat Profil ${result.title}",
            subtitle = if (isRmib) {
                "Catatan RMIB: jumlah peringkat TERENDAH menunjukkan minat vokasional TERTINGGI."
            } else {
                "Urutan dimensi dari skor tertinggi ke terendah."
            },
            bars = bars,
            lowerIsHigherInterest = isRmib,
            dominantProfileCode = hollandCode,
            radarAxes = hollandRadar,
            tableRows = rows
        )
    }

    private fun mapScoreIndicator(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        val total = result.scores.firstOrNull {
            it.label.contains("Total", ignoreCase = true)
        } ?: result.scores.firstOrNull()
        val subscales = result.scores.filter { it != total }.map { score ->
            val isSafetyFlagged = score.label.contains("Butir 9", ignoreCase = true) && score.value > 0.0
            ScaleBarDatum(
                label = score.label,
                shortLabel = score.label,
                value = score.value,
                minimum = score.minimum,
                maximum = score.maximum.coerceAtLeast(1.0),
                valueText = score.displayValue(),
                note = score.meaning,
                flagged = isSafetyFlagged
            )
        }
        val primary = ScaleBarDatum(
            label = total?.label ?: result.title,
            shortLabel = total?.label ?: "Total",
            value = total?.value ?: 0.0,
            minimum = total?.minimum ?: 0.0,
            maximum = total?.maximum ?: 63.0,
            valueText = total?.displayValue() ?: "0",
            note = total?.meaning.orEmpty(),
            flagged = (total?.value ?: 0.0) >= 20.0
        )
        val bands = if (result.instrumentId == "bdi") {
            listOf(
                ScoreBandDatum("Minimal", 0.0, 13.0),
                ScoreBandDatum("Ringan", 14.0, 19.0),
                ScoreBandDatum("Sedang", 20.0, 28.0),
                ScoreBandDatum("Berat", 29.0, 63.0)
            )
        } else {
            emptyList()
        }
        return AssessmentVisualizationModel.ScoreIndicatorModel(
            title = "Indikator Skor ${result.title}",
            subtitle = "Posisi skor pada rentang band instrumen",
            primaryScore = primary,
            bands = bands,
            subscales = subscales,
            tableRows = rows
        )
    }

    private fun mapWartegg(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        val drafts = if (result.warteggDrafts.isNotEmpty()) {
            result.warteggDrafts.sortedBy { it.boxNumber }
        } else {
            (1..8).map { num -> WarteggBoxDraft(boxNumber = num) }
        }
        val ordered = drafts.filter { it.drawOrder != null }
            .sortedBy { it.drawOrder }
            .map { it.boxNumber }
        val totalDuration = drafts.sumOf { it.durationSeconds.coerceAtLeast(0) }
        val obsRows = rows.ifEmpty {
            drafts.map { box ->
                val meta = listOfNotNull(
                    box.drawOrder?.let { "Urutan #$it" },
                    if (box.durationSeconds > 0) "${box.durationSeconds} dtk" else null,
                    box.valence,
                    box.difficulty
                ).joinToString(" · ")
                AccessibleScoreRow(
                    label = "Kotak ${box.boxNumber}",
                    valueText = box.title.ifBlank { if (box.strokes.isNotEmpty()) "Digambar" else "Kosong" },
                    rangeText = meta.ifBlank { "-" },
                    note = "${box.strokes.size} goresan"
                )
            }
        }
        val reviewDone = result.resolvedReviewStatus() == ExaminerReviewStatus.APPROVED
        val hasCoding = result.examinerCodes.isNotEmpty()
        val codingStatusRows = listOf(
            CodingStatusRow("Coding gambar", if (hasCoding) "Tercatat (${result.examinerCodes.size} entri)" else "Belum selesai"),
            CodingStatusRow("Profil fungsi", if (reviewDone && hasCoding) "Terverifikasi" else "Menunggu coding"),
            CodingStatusRow("Interpretasi", if (reviewDone) "Disetujui pemeriksa" else "Menunggu review")
        )
        val warteggTelemetry = ProjectiveTelemetryExtractor.extractMetrics(
            panelStrokes = drafts.map { it.strokes },
            revisionCount = drafts.sumOf { it.revisionCount.coerceAtLeast(0) }
        ).takeIf { it.pointCount > 0 }
        return AssessmentVisualizationModel.WarteggEightBoxModel(
            title = "Ringkasan 8 Kotak Stimulus Wartegg",
            subtitle = "Menampilkan gambar terpersis & observasi terstruktur (bukan skor numerik buatan AI)",
            boxes = drafts,
            sequenceOrder = ordered,
            totalDurationSeconds = totalDuration,
            codingStatusRows = codingStatusRows,
            telemetry = warteggTelemetry,
            tableRows = obsRows
        )
    }

    private fun mapMbti(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        fun scoreVal(code: String): Double = result.scores.firstOrNull {
            it.label.contains("($code)", ignoreCase = true) || it.label.equals(code, ignoreCase = true)
        }?.value ?: 0.0

        val axes = listOf(
            createBipolarAxis("Energi", "E", "Ekstrovert", scoreVal("E"), "I", "Introvert", scoreVal("I"), 10.0),
            createBipolarAxis("Persepsi", "S", "Sensing", scoreVal("S"), "N", "Intuition", scoreVal("N"), 20.0),
            createBipolarAxis("Keputusan", "T", "Thinking", scoreVal("T"), "F", "Feeling", scoreVal("F"), 20.0),
            createBipolarAxis("Gaya Kerja", "J", "Judging", scoreVal("J"), "P", "Perceiving", scoreVal("P"), 20.0)
        )
        val typeCode = axes.joinToString("") { it.dominantCode }
        return AssessmentVisualizationModel.BipolarPreferenceModel(
            title = "Profil Preferensi Bipolar MBTI",
            subtitle = "Empat dikotomi kutub berpasangan (kecenderungan preferensi relatif, bukan kategori kaku)",
            primaryTypeCode = typeCode,
            axes = axes,
            tableRows = rows
        )
    }

    private fun createBipolarAxis(
        label: String,
        lCode: String,
        lName: String,
        lVal: Double,
        rCode: String,
        rName: String,
        rVal: Double,
        defaultMax: Double
    ): BipolarAxisDatum {
        val total = (lVal + rVal).coerceAtLeast(defaultMax)
        return BipolarAxisDatum(
            dimensionLabel = label,
            leftCode = lCode,
            leftLabel = lName,
            leftValue = lVal,
            rightCode = rCode,
            rightLabel = rName,
            rightValue = rVal,
            totalMax = total,
            dominantCode = if (lVal >= rVal) lCode else rCode
        )
    }

    private fun mapObservationMatrix(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        val obsRows = rows.ifEmpty {
            result.observations.map { obs ->
                AccessibleScoreRow(
                    label = obs.label,
                    valueText = obs.value,
                    rangeText = obs.tags.joinToString(", ").ifBlank { "-" },
                    note = ""
                )
            }
        }
        val bars = result.scores.map { score ->
            val rangeSpan = (score.maximum - score.minimum).coerceAtLeast(1.0)
            ScaleBarDatum(
                label = score.label,
                shortLabel = score.label,
                value = score.value,
                minimum = score.minimum,
                maximum = score.minimum + rangeSpan,
                valueText = score.displayValue(),
                note = score.meaning
            )
        }
        val panelTelemetry = ProjectiveTelemetryExtractor.extractMetrics(
            panelStrokes = result.projectivePanels.map { it.strokes },
            revisionCount = result.projectivePanels.sumOf { it.revisionCount.coerceAtLeast(0) }
        ).takeIf { it.pointCount > 0 }
        return AssessmentVisualizationModel.ObservationMatrixModel(
            title = "Matriks Observasi Proyektif ${result.title}",
            subtitle = "Observasi kualitatif, telemetri stylus/goresan, dan coding pemeriksa tanpa persentase ciri buatan",
            reviewStatus = result.resolvedReviewStatus(),
            observations = result.observations,
            examinerCodes = result.examinerCodes,
            panels = result.projectivePanels,
            bars = bars,
            telemetry = panelTelemetry,
            tableRows = obsRows
        )
    }

    private fun mapRorschach(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        val systemId = result.observations.firstOrNull { it.key == "rorschach_system" }?.value
            ?: result.rorschachResponses.firstOrNull()?.codingSystem
            ?: "EXNER_CS"
        val rCount = result.rorschachResponses.size.takeIf { it > 0 }
            ?: result.observations.firstOrNull { it.key == "rorschach_r" }?.value?.toIntOrNull()
            ?: result.answered
        val locSummary = result.observations.firstOrNull { it.key == "rorschach_loc" }?.value
            ?: "Menunggu coding lokasi pemeriksa"
        val obsRows = rows.ifEmpty {
            result.observations.map { obs ->
                AccessibleScoreRow(
                    label = obs.label,
                    valueText = obs.value,
                    rangeText = obs.tags.joinToString(", ").ifBlank { "-" },
                    note = ""
                )
            }
        }
        val bars = result.scores.map { score ->
            val rangeSpan = (score.maximum - score.minimum).coerceAtLeast(1.0)
            ScaleBarDatum(
                label = score.label,
                shortLabel = score.label,
                value = score.value,
                minimum = score.minimum,
                maximum = score.minimum + rangeSpan,
                valueText = score.displayValue(),
                note = score.meaning
            )
        }
        return AssessmentVisualizationModel.ExaminerCodedProfileModel(
            title = "Protokol Proyektif Rorschach ($systemId)",
            subtitle = "Ringkasan hitungan protokol, distribusi lokasi/determinan, dan observasi verbal",
            systemId = systemId,
            responseCount = rCount,
            locationSummary = locSummary,
            observations = result.observations,
            examinerCodes = result.examinerCodes,
            bars = bars,
            tableRows = obsRows
        )
    }

    private fun mapHorizontal(
        result: AssessmentResult,
        rows: List<AccessibleScoreRow>
    ): AssessmentVisualizationModel {
        val canonicalId = PsychometricInstrumentRegistry.normalizeInstrumentId(result.instrumentId)
        val totalScore = result.scores.firstOrNull { it.label.contains("Total", ignoreCase = true) }
        val filteredScores = if (canonicalId in setOf("cfit_2a", "cfit_3a", "ist") && totalScore != null && result.scores.size > 1) {
            result.scores.filter { it != totalScore }
        } else {
            result.scores
        }
        val bars = filteredScores.map { score ->
            val rangeSpan = (score.maximum - score.minimum).coerceAtLeast(1.0)
            ScaleBarDatum(
                label = score.label,
                shortLabel = score.label,
                value = score.value,
                minimum = score.minimum,
                maximum = score.minimum + rangeSpan,
                valueText = score.displayValue(),
                note = score.meaning
            )
        }
        val kpiMetrics = if (canonicalId in setOf("cfit_2a", "cfit_3a", "ist")) {
            val rawLabel = totalScore?.let {
                val maxText = if (it.maximum == it.maximum.toInt().toDouble()) it.maximum.toInt().toString() else it.maximum.toString()
                "${it.displayValue()}/$maxText"
            } ?: "${result.answered}/${result.itemCount}"
            listOf(
                KpiMetricDatum("Raw score", rawLabel, "Jumlah jawaban benar aktual"),
                KpiMetricDatum("IQ score", "—", "Norma populasi belum tersedia")
            )
        } else {
            emptyList()
        }
        val radarAxes = if (canonicalId == "big_five" && bars.size >= 5) {
            bars.take(5).map { bar ->
                val span = (bar.maximum - bar.minimum).coerceAtLeast(1.0)
                RadarAxisDatum(
                    code = bar.label.take(1).uppercase(),
                    label = bar.label,
                    normalizedFraction = ((bar.value - bar.minimum) / span).toFloat().coerceIn(0f, 1f),
                    valueText = bar.valueText
                )
            }
        } else {
            emptyList()
        }
        return AssessmentVisualizationModel.HorizontalBarChartModel(
            title = "Profil Skor ${result.title}",
            subtitle = "Skor mentah / skala per dimensi",
            bars = bars,
            radarAxes = radarAxes,
            kpiMetrics = kpiMetrics,
            tableRows = rows
        )
    }
}

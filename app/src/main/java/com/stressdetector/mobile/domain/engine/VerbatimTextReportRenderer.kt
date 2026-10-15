package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import java.time.Instant
import java.util.Locale

/** Current review text, not a summary or rewritten/fabricated transcript. */
class VerbatimTextReportRenderer(private val analyzer: VerbatimCueAnalyzer = VerbatimCueAnalyzer()) {
    fun render(session: VerbatimSessionDetail, annotations: List<DomainAnnotation> = emptyList()): String = buildString {
        appendHeader(session.summary)
        appendTranscript(session.transcript)
        appendMarkers(session.divergenceEvents, session.transcript)
        appendNotes(session.summary, annotations)
        appendLegend()
    }

    private fun StringBuilder.appendHeader(summary: VerbatimSessionSummary) {
        appendLine("VERBATIM+ — TRANSKRIP DAN MARKER TINJAUAN")
        appendLine("Sesi: ${VerbatimTranscriptText.clean(summary.sessionName)}")
        appendLine("ID: ${summary.sessionId}")
        appendLine("Mulai UTC: ${Instant.ofEpochMilli(summary.startedAtMillis)}")
        appendLine("Finalisasi: ${summary.finalizationStatus}; ringkasan AI: ${summary.summaryStatus}")
        appendLine("Teks raw ASR dipertahankan di aplikasi. Teks bertanda [EDIT] adalah koreksi pengguna.")
        appendLine("Cue = temuan leksikal untuk ditinjau, bukan bukti desepsi/diagnosis.")
        appendLine("Waktu cue adalah rentang ujaran, bukan timestamp per kata. Dengarkan audio untuk konfirmasi.")
        appendLine(
            "Transkrip/cue dan catatan baru: offset audio termasuk jeda; marker sensor: elapsed sesi. Clock catatan lama dapat berbeda."
        )
        appendLine("Analisis leksikal: ${VerbatimCueAnalyzer.VERSION}")
        appendLine()
    }

    private fun StringBuilder.appendTranscript(segments: List<SpeechSegment>) {
        appendLine("TRANSKRIP")
        val transcript = segments.filter(SpeechSegment::isFinal).distinctBy(SpeechSegment::id)
            .sortedWith(compareBy(SpeechSegment::startOffsetMs, SpeechSegment::id))
        require(transcript.any { it.hasFinalReviewEvidence }) {
            "Transkrip final belum tersedia untuk ekspor TXT."
        }
        transcript.forEach { segment ->
            val analysis = analyzer.analyze(segment)
            appendLine(
                "[${time(segment.startOffsetMs)}–${time(segment.endOffsetMs)}] ${segment.displaySpeakerLabel}" +
                    if (segment.editedText != null) " [EDIT]" else ""
            )
            segment.recognitionReviewReason?.let { appendLine("  [TINJAUAN ASR] $it") }
            appendLine(analysis.text)
            analysis.cues.forEach { cue ->
                appendLine(
                    "  [${cue.type.label}] «${cue.matchedText}»; karakter ${cue.startChar}..${cue.endCharExclusive}; " +
                        "${cue.source}; ${cue.ruleId}: ${cue.explanation}"
                )
            }
            segment.decoderEvidence?.let { appendDecoderEvidence(it) }
            segment.prosody?.let { evidence ->
                val pitch = evidence.pitchHz?.let { "F0 rerata geometrik ujaran ${number(it)} Hz" }
                    ?: "pitch unavailable: ${VerbatimTranscriptText.clean(evidence.unavailableReason ?: "voiced frame tidak memadai") }"
                appendLine(
                    "  [Akustik openSMILE] $pitch; voiced=${evidence.voicedFrameCount}/${evidence.frameCount}; metode=${evidence.version}"
                )
            }
            appendLine()
        }
    }

    private fun StringBuilder.appendDecoderEvidence(evidence: SpeechDecoderEvidence) {
        appendLine("  [Bukti decoder] ${evidence.status}; kebijakan=${SpeechRecognitionReview.VERSION}")
        appendDecoderResult("Ujaran utuh", evidence.wholeUtterance)
        evidence.crop?.let { appendDecoderResult("Crop", it) }
        evidence.precedingPartial?.let { appendDecoderResult("Partial terakhir, bukan teks final", it) }
        evidence.streamingFinal?.let { appendDecoderResult("Final streaming", it) }
    }

    private fun StringBuilder.appendDecoderResult(label: String, result: SpeechDecoderResult) {
        appendLine(
            "  $label [${time(result.startOffsetMs)}–${time(result.endOffsetMs)}]; " +
                "backend=${result.backend}; revision=${result.modelRevision}"
        )
        appendLine(result.rawText)
    }

    private fun StringBuilder.appendMarkers(events: List<DivergenceEventEntity>, transcript: List<SpeechSegment>) {
        appendLine("MARKER AUDIO / PPG")
        val markers = events.distinctBy { it.eventId }.sortedBy { it.episodeStartElapsedMillis }
        if (markers.isEmpty()) appendLine("Tidak ada marker tersimpan. Ini bukan bukti tidak adanya stres/keraguan.")
        markers.forEach { marker ->
            appendLine(
                "[${time(marker.episodeStartElapsedMillis)}–${time(marker.episodeEndElapsedMillis)}] " +
                    "${marker.markerType}; kanal=${marker.sourceChannels.ifBlank { marker.channel }}; " +
                    "versi=${marker.markerConfigVersion}"
            )
            appendLine("  ${VerbatimTranscriptText.clean(marker.copyText)}")
            TranscriptTimelineLinker.playbackOffset(marker, transcript)?.let { offset ->
                appendLine("  Ujaran terkait: ${marker.transcriptSegmentId}; offset audio ${time(offset)}")
            }
            marker.currentProbability?.let {
                appendLine("  Probabilitas model ONNX: ${number(it.toDouble())}; bukan confidence atau diagnosis.")
            }
            marker.baselineProbability?.let { appendLine("  Baseline probabilitas: ${number(it.toDouble())}") }
            marker.bpm?.let { bpm ->
                val baseline = marker.baselineBpm?.let { number(it.toDouble()) } ?: "unavailable"
                appendLine("  BPM: ${number(bpm.toDouble())}; baseline=$baseline")
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendNotes(summary: VerbatimSessionSummary, annotations: List<DomainAnnotation>) {
        appendLine("CATATAN MANUAL")
        val notes = annotations.filter { it.sessionId == summary.sessionId }.distinctBy(DomainAnnotation::id).sortedBy { it.timestampMs }
        if (notes.isEmpty()) appendLine("Tidak ada catatan manual.")
        notes.forEach { note -> appendLine("[${time(note.timestampMs)}] ${VerbatimTranscriptText.clean(note.text)}") }
        if (summary.audioGaps.isNotEmpty()) {
            appendLine()
            appendLine("JEDA / GAP AKUISISI")
            summary.audioGaps.forEach { gap -> appendLine("[${time(gap.startOffsetMs)}] ${gap.reason}; durasi ${gap.durationMs} ms") }
        }
        appendLine()
    }

    private fun StringBuilder.appendLegend() {
        appendLine("LEGENDA")
        VerbatimCueType.entries.forEach { appendLine("${it.label}: ${it.explanation}") }
        appendLine("ACOUSTIC_DIVERGENCE: perubahan energi PCM terhadap baseline sesi.")
        appendLine("PROSODY_DIVERGENCE: perubahan pitch openSMILE terhadap baseline pembicara; bukan classifier arousal.")
        appendLine("Pitch Hz ekspor adalah rerata geometrik voiced per ujaran, bukan trace F0 lengkap atau bukti desepsi.")
        appendLine("LOW_AUDIO_QUALITY: kualitas rendah, bukan prediksi stres.")
        appendLine("TEMPORAL_BASELINE_AROUSAL / MODEL_AROUSAL: hasil ONNX kanal PPG yang lolos quality/readiness.")
        appendLine("BPM_RISE / BPM_RECOVERY / BPM_TRAJECTORY: lintasan BPM terhadap baseline valid; bukan diagnosis.")
        appendLine("Kanal PPG independen. Sensor opsional; tanpa data valid tidak ada probabilitas yang dibuat.")
        appendLine("ASR dapat menghilangkan filler/pengulangan. Tidak adanya cue bukan kepastian tidak adanya hesitation.")
    }

    private fun time(millis: Long): String {
        val value = millis.coerceAtLeast(0L)
        return "%02d:%02d:%02d.%03d".format(
            Locale.ROOT,
            value / HOUR_MS,
            value / MINUTE_MS % SEXAGESIMAL_BASE,
            value / SECOND_MS % SEXAGESIMAL_BASE,
            value % SECOND_MS
        )
    }

    private fun number(value: Double): String = "%.4f".format(Locale.ROOT, value)

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val MINUTE_MS = 60_000L
        private const val SECOND_MS = 1_000L
        private const val SEXAGESIMAL_BASE = 60
    }
}

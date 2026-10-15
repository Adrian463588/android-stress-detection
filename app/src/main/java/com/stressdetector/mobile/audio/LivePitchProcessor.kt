package com.stressdetector.mobile.audio

import com.stressdetector.mobile.domain.engine.AcousticAnalysisConfig
import com.stressdetector.mobile.domain.engine.LivePitchObservation
import com.stressdetector.mobile.domain.engine.LivePitchStatus
import com.stressdetector.mobile.domain.engine.VerbatimProsodyEvidence
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal data class PitchPcmWindow(val samples: FloatArray, val startOffsetMs: Long, val endOffsetMs: Long)
internal data class PitchWindowBatch(val windows: List<PitchPcmWindow>, val gap: LongRange? = null)

/** Sample-index continuity prevents pause/overload from joining unrelated PCM. */
internal class LivePitchWindowAssembler(private val sampleRateHz: Int = 16_000) {
    private var pending = FloatArray(sampleRateHz.also { require(it > 0) })
    private var filled = 0
    private var startOffsetMs = 0L
    private var expectedSampleIndex: Long? = null
    private var previousTimebase: Long? = null
    private var previousEndOffsetMs: Long? = null

    fun append(frame: SpeechFrame): PitchWindowBatch {
        require(frame.startOffsetMs >= 0 && frame.sampleStartIndex >= 0)
        val timebase = frame.startOffsetMs - frame.sampleStartIndex * MILLIS_PER_SECOND / sampleRateHz
        val discontinuity = expectedSampleIndex?.let { it != frame.sampleStartIndex || previousTimebase != timebase } == true
        val gap = if (discontinuity || (expectedSampleIndex == null && frame.startOffsetMs > 0)) {
            filled = 0
            (previousEndOffsetMs ?: 0L).coerceAtMost(frame.startOffsetMs)..frame.startOffsetMs
        } else {
            null
        }
        val windows = mutableListOf<PitchPcmWindow>()
        var consumed = 0
        while (consumed < frame.samples.size) {
            if (filled == 0) {
                startOffsetMs = timebase + (frame.sampleStartIndex + consumed) * MILLIS_PER_SECOND / sampleRateHz
            }
            val count = minOf(pending.size - filled, frame.samples.size - consumed)
            frame.samples.copyInto(pending, filled, consumed, consumed + count)
            filled += count
            consumed += count
            if (filled == pending.size) {
                windows.add(PitchPcmWindow(pending, startOffsetMs, startOffsetMs + MILLIS_PER_SECOND))
                pending = FloatArray(sampleRateHz)
                filled = 0
            }
        }
        val nextSampleIndex = frame.sampleStartIndex + frame.samples.size
        expectedSampleIndex = nextSampleIndex
        previousTimebase = timebase
        previousEndOffsetMs = timebase + nextSampleIndex * MILLIS_PER_SECOND / sampleRateHz
        return PitchWindowBatch(windows, gap)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/** Preview-only bounded handoff. Native work never blocks AudioRecord or ASR. */
internal class LivePitchProcessor(
    private val sessionId: String,
    scope: CoroutineScope,
    private val extract: (FloatArray) -> VerbatimProsodyEvidence,
    private val publish: suspend (LivePitchObservation) -> Unit
) {
    private val qualityConfig = AcousticAnalysisConfig()
    private val frames = Channel<SpeechFrame>(capacity = HANDOFF_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val worker = scope.launch {
        val assembler = LivePitchWindowAssembler()
        for (frame in frames) {
            val batch = assembler.append(frame)
            batch.gap?.let { gap ->
                publish(
                    LivePitchObservation(
                        sessionId,
                        gap.first,
                        gap.last,
                        null,
                        LivePitchStatus.PROCESSING_GAP,
                        reason = "Jeda atau gap pemrosesan pitch; tidak ada nilai pengganti."
                    )
                )
            }
            batch.windows.forEach { publish(analyze(it)) }
        }
    }

    fun offer(frame: SpeechFrame) {
        frames.trySend(frame)
    }

    suspend fun close() {
        frames.close()
        worker.join()
    }

    suspend fun cancel() {
        frames.cancel()
        worker.cancelAndJoin()
    }

    private fun analyze(window: PitchPcmWindow): LivePitchObservation {
        val invalid = window.samples.any { !it.isFinite() || abs(it) > 1f }
        val clipping = window.samples.count { abs(it) >= CLIP_THRESHOLD }.toDouble() / window.samples.size
        if (invalid || clipping > qualityConfig.maxClippingRatio) {
            return observation(window, LivePitchStatus.QUALITY_REJECTED, reason = "PCM tidak valid atau clipping; pitch tidak dihitung.")
        }
        val evidence = try {
            extract(window.samples)
        } catch (error: IllegalStateException) {
            VerbatimProsodyEvidence(unavailableReason = "openSMILE pitch gagal: ${error.message}")
        } catch (error: IllegalArgumentException) {
            VerbatimProsodyEvidence(unavailableReason = "openSMILE pitch gagal: ${error.message}")
        }
        val voicedRatio = if (evidence.frameCount > 0) evidence.voicedFrameCount.toDouble() / evidence.frameCount else 0.0
        val hz = evidence.pitchHz?.takeIf { voicedRatio >= MIN_VOICED_RATIO }
        return when {
            hz != null -> observation(window, LivePitchStatus.AVAILABLE, hz, evidence)
            evidence.unavailableReason != null -> observation(
                window,
                LivePitchStatus.NATIVE_UNAVAILABLE,
                reason = evidence.unavailableReason
            )
            evidence.frameCount > 0 -> observation(
                window,
                LivePitchStatus.UNVOICED,
                evidence = evidence,
                reason = "Suara voiced belum cukup; diam/noise tidak memiliki pitch yang dapat ditampilkan."
            )
            else -> observation(
                window,
                LivePitchStatus.NATIVE_UNAVAILABLE,
                reason =
                evidence.unavailableReason ?: "openSMILE pitch belum tersedia."
            )
        }
    }

    private fun observation(
        window: PitchPcmWindow,
        status: LivePitchStatus,
        hz: Double? = null,
        evidence: VerbatimProsodyEvidence? = null,
        reason: String? = null
    ) = LivePitchObservation(
        sessionId,
        window.startOffsetMs,
        window.endOffsetMs,
        hz,
        status,
        voicedFrameCount = evidence?.voicedFrameCount ?: 0,
        frameCount = evidence?.frameCount ?: 0,
        reason = reason,
        waveform = com.stressdetector.mobile.domain.engine.pcmPeakEnvelope(window.samples)
    )

    private companion object {
        const val HANDOFF_CAPACITY = 8
        const val MIN_VOICED_RATIO = 0.2
        const val CLIP_THRESHOLD = 0.98f
    }
}

package com.stressdetector.mobile.audio

import com.stressdetector.mobile.domain.engine.LivePitchObservation
import com.stressdetector.mobile.domain.engine.LivePitchStatus
import com.stressdetector.mobile.domain.engine.VerbatimProsodyEvidence
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Numerical fixtures verify mechanics, not speech/pitch accuracy on a phone. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LivePitchProcessorTest {
    @Test
    fun assemblerEmitsOnlyCompleteObservedWindows() {
        val assembler = LivePitchWindowAssembler(sampleRateHz = 1_000)
        assertEquals(emptyList<PitchPcmWindow>(), assembler.append(frame(400, 0)).windows)
        val window = assembler.append(frame(600, 400)).windows.single()
        assertEquals(0L, window.startOffsetMs)
        assertEquals(1_000L, window.endOffsetMs)
        assertArrayEquals(FloatArray(1_000) { 0.1f }, window.samples, 0f)
        assertEquals(emptyList<PitchPcmWindow>(), assembler.append(frame(200, 1_000)).windows)
    }

    @Test
    fun pauseAndDroppedFramesDiscardIncompleteWindowWithoutPadding() {
        val assembler = LivePitchWindowAssembler(sampleRateHz = 1_000)
        assembler.append(frame(400, 0))
        val afterPause = assembler.append(frame(1_000, 400, startMs = 2_400))
        assertEquals(400L..2_400L, afterPause.gap)
        assertEquals(2_400L, afterPause.windows.single().startOffsetMs)
        val afterDrop = assembler.append(frame(1_000, 2_000, startMs = 4_000))
        assertEquals(3_400L..4_000L, afterDrop.gap)
        assertEquals(4_000L, afterDrop.windows.single().startOffsetMs)
    }

    @Test
    fun voicedSemitoneMeanIsConvertedToGeometricHzMean() = runTest {
        val observations = mutableListOf<LivePitchObservation>()
        val processor = LivePitchProcessor("session", this, { voicedEvidence() }, observations::add)
        processor.offer(pcmFrame(0))
        processor.close()
        val result = observations.single()
        assertEquals(LivePitchStatus.AVAILABLE, result.status)
        assertEquals(110.0, result.frequencyHz!!, 1e-9)
        assertEquals(1_000L, result.endOffsetMs)
    }

    @Test
    fun silenceNativeFailureAndClippingNeverEmitNumericPitch() = runTest {
        val outputs = mutableListOf<LivePitchObservation>()
        val silence = LivePitchProcessor("silence", this, { VerbatimProsodyEvidence(frameCount = 98) }, outputs::add)
        silence.offer(pcmFrame(0, value = 0f))
        silence.close()
        assertEquals(LivePitchStatus.UNVOICED, outputs.last().status)
        val nativeFailure = LivePitchProcessor("unavailable", this, { error("native test failure") }, outputs::add)
        nativeFailure.offer(pcmFrame(0))
        nativeFailure.close()
        assertEquals(LivePitchStatus.NATIVE_UNAVAILABLE, outputs.last().status)
        val clipped = LivePitchProcessor("clipped", this, { error("Clipped audio must never reach native extraction") }, outputs::add)
        clipped.offer(pcmFrame(0, value = 1f))
        clipped.close()
        assertEquals(LivePitchStatus.QUALITY_REJECTED, outputs.last().status)
        outputs.forEach { assertNull(it.frequencyHz) }
    }

    @Test
    fun overloadIsBoundedAndPublishesGapInsteadOfSyntheticFrames() = runTest {
        val observations = mutableListOf<LivePitchObservation>()
        val processor = LivePitchProcessor("session", this, { voicedEvidence() }, observations::add)
        repeat(10) { processor.offer(pcmFrame(it.toLong() * 16_000)) }
        runCurrent()
        processor.close()
        assertEquals(LivePitchStatus.PROCESSING_GAP, observations.first().status)
        assertEquals(2_000L, observations.first().endOffsetMs)
        assertEquals(8, observations.count { it.status == LivePitchStatus.AVAILABLE })
    }

    private fun frame(count: Int, sampleIndex: Long, startMs: Long = sampleIndex) = SpeechFrame(
        FloatArray(count) { 0.1f },
        startMs,
        count.toLong(),
        sampleStartIndex = sampleIndex
    )

    private fun pcmFrame(sampleIndex: Long, value: Float = 0.1f) = SpeechFrame(
        FloatArray(16_000) { value },
        sampleIndex * 1_000 / 16_000,
        1_000,
        sampleStartIndex = sampleIndex
    )

    private fun voicedEvidence() = VerbatimProsodyEvidence(
        features = mapOf("F0semitoneFrom27.5Hz_sma3nz" to 24.0),
        frameCount = 98,
        voicedFrameCount = 90
    )
}

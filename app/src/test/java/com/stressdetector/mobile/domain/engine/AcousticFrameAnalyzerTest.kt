package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticFrameAnalyzerTest {
    @Test
    fun amplitudeDeviationProducesAcousticMarkerAfterBaselineWarmup() {
        val analyzer = AcousticFrameAnalyzer()

        repeat(5) { index ->
            val snapshot = analyzer.analyze(frame("session", 0.1f, index * 100L))
            assertNull(snapshot.markerType)
        }

        val firstDeviation = analyzer.analyze(frame("session", 0.4f, 500L))
        assertNull(firstDeviation.markerType)
        assertNull(firstDeviation.markerTransition)
        val snapshot = (1..4).fold(firstDeviation) { _, index ->
            analyzer.analyze(frame("session", 0.4f, 500L + index * 100L))
        }

        assertEquals(SessionMarkerType.ACOUSTIC_DIVERGENCE, snapshot.markerType)
        assertEquals(SessionMarkerType.ACOUSTIC_DIVERGENCE, snapshot.markerTransition)
        assertEquals(500L, snapshot.markerEpisodeStartOffsetMs)
        assertNotNull(snapshot.baselineDeviationDb)
        assertTrue(snapshot.qualityScore > 0.9f)
        assertTrue(snapshot.evidenceConfidence > 0.9f)
        assertTrue(snapshot.waveform.size <= 64)
    }

    @Test
    fun silenceIsRecordedAsLowAudioQuality() {
        val analyzer = AcousticFrameAnalyzer()
        val first = analyzer.analyze(frame("session", 0f, 0L))
        assertNull(first.markerTransition)
        val snapshot = (1..4).fold(first) { _, index ->
            analyzer.analyze(frame("session", 0f, index * 100L))
        }

        assertEquals(SessionMarkerType.LOW_AUDIO_QUALITY, snapshot.markerType)
        assertEquals(SessionMarkerType.LOW_AUDIO_QUALITY, snapshot.markerTransition)
        assertEquals(0f, snapshot.qualityScore)
        assertEquals(0f, snapshot.clippingRatio)
    }

    @Test
    fun clippedSamplesAreRecordedAsLowAudioQuality() {
        val analyzer = AcousticFrameAnalyzer()
        var snapshot = analyzer.analyze(clippedFrame(0L))
        repeat(4) { index -> snapshot = analyzer.analyze(clippedFrame((index + 1) * 100L)) }

        assertEquals(SessionMarkerType.LOW_AUDIO_QUALITY, snapshot.markerType)
        assertEquals(SessionMarkerType.LOW_AUDIO_QUALITY, snapshot.markerTransition)
        assertTrue(snapshot.clippingRatio > 0.9f)
        assertEquals(0f, snapshot.qualityScore)
    }

    @Test
    fun nonFinitePcmIsExposedAsSafeLowQualityEvidence() {
        val analyzer = AcousticFrameAnalyzer()
        var snapshot = analyzer.analyze(nonFiniteFrame(0L))
        repeat(4) { index -> snapshot = analyzer.analyze(nonFiniteFrame((index + 1) * 100L)) }

        assertEquals(SessionMarkerType.LOW_AUDIO_QUALITY, snapshot.markerType)
        assertEquals(0f, snapshot.finiteSampleRatio)
        assertEquals(0f, snapshot.qualityScore)
        assertTrue(snapshot.waveform.all { it == 0f })
    }

    @Test
    fun exitedEpisodeDoesNotReemitInsideCooldown() {
        val analyzer = AcousticFrameAnalyzer(
            AcousticAnalysisConfig(
                baselineWarmupFrames = 2,
                baselineWindowFrames = 20,
                markerEnterPersistenceMillis = 200L,
                markerExitPersistenceMillis = 200L,
                markerCooldownMillis = 1_000L
            )
        )
        analyzer.analyze(frame("session", 0.1f, 0L))
        analyzer.analyze(frame("session", 0.1f, 100L))
        assertNull(analyzer.analyze(frame("session", 0.4f, 200L)).markerTransition)
        assertEquals(
            SessionMarkerType.ACOUSTIC_DIVERGENCE,
            analyzer.analyze(frame("session", 0.4f, 300L)).markerTransition
        )
        analyzer.analyze(frame("session", 0.1f, 400L))
        analyzer.analyze(frame("session", 0.1f, 500L))
        analyzer.analyze(frame("session", 0.4f, 600L))
        assertNull(analyzer.analyze(frame("session", 0.4f, 700L)).markerTransition)
    }

    @Test
    fun fiveMinuteEquivalentFramesKeepEnvelopeBounded() {
        val analyzer = AcousticFrameAnalyzer(
            AcousticAnalysisConfig(baselineWarmupFrames = 5, baselineWindowFrames = 20)
        )

        var snapshot = analyzer.analyze(frame("session", 0.1f, 0L))
        repeat(2_999) { index ->
            snapshot = analyzer.analyze(frame("session", 0.1f, (index + 1) * 100L))
        }

        assertEquals(64, snapshot.waveform.size)
        assertNull(snapshot.markerTransition)
        assertTrue(snapshot.qualityScore > 0.9f)
    }

    private fun frame(sessionId: String, amplitude: Float, startOffsetMs: Long) = AcousticAudioFrame(
        sessionId = sessionId,
        samples = FloatArray(160) { amplitude },
        startOffsetMs = startOffsetMs,
        durationMs = 100L
    )

    private fun clippedFrame(startOffsetMs: Long) = AcousticAudioFrame(
        sessionId = "session",
        samples = FloatArray(160) { if (it % 2 == 0) 1f else -1f },
        startOffsetMs = startOffsetMs,
        durationMs = 100L
    )

    private fun nonFiniteFrame(startOffsetMs: Long) = AcousticAudioFrame(
        sessionId = "session",
        samples = FloatArray(160) { Float.NaN },
        startOffsetMs = startOffsetMs,
        durationMs = 100L
    )
}

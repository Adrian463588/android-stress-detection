package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbatimCueAnalyzerTest {
    private val analyzer = VerbatimCueAnalyzer()

    @Test
    fun `Indonesian cues retain literal spans and utterance timing`() {
        val result = analyzer.analyze(segment("Hmm... saya mungkin sampai sekitar delapan, saya tidak yakin."))
        assertEquals(
            listOf(VerbatimCueType.FILLED_PAUSE, VerbatimCueType.HEDGING, VerbatimCueType.UNCERTAINTY),
            result.cues.map {
                it.type
            }
        )
        result.cues.forEach { cue ->
            assertEquals(cue.matchedText, result.text.substring(cue.startChar, cue.endCharExclusive))
            assertEquals(1_200L, cue.startOffsetMs)
            assertEquals(4_800L, cue.endOffsetMs)
            assertEquals(VerbatimCueSource.RAW_ASR, cue.source)
        }
    }

    @Test
    fun `quoted hedges polite prompts and negated possibility do not mark uncertainty`() {
        listOf(
            "Dia berkata \"mungkin\" tadi.",
            "Dia berkata ‘sepertinya’ tadi.",
            "Mungkin Anda bisa menjelaskan?",
            "Tidak mungkin saya datang, saya bukan tidak yakin.",
            "Ya begini, saya tidak datang kemarin.",
            "Ketidakmungkinan itu dibahas."
        ).forEach { assertTrue(it, analyzer.analyze(segment(it)).cues.isEmpty()) }
    }

    @Test
    fun `ordinary reduplication is preserved while actual repetition is marked`() {
        val result = analyzer.analyze(segment("Anak anak jalan jalan, saya, saya melihat mereka."))
        assertEquals(1, result.cues.size)
        assertEquals(VerbatimCueType.REPETITION, result.cues.single().type)
        assertEquals("saya, saya", result.cues.single().matchedText)
    }

    @Test
    fun `longest self repair phrase emits one cue and ordinary negation emits none`() {
        val result = analyzer.analyze(segment("Jam sembilan, eh maksud saya delapan, bukan tujuh."))
        assertEquals(listOf("eh maksud saya"), result.cues.map { it.matchedText })
        assertEquals(VerbatimCueType.SELF_REPAIR, result.cues.single().type)
    }

    @Test
    fun `clean view and edits preserve raw transcript and regenerate exact cues`() {
        val raw = "  Hmm\n saya   saya mungkin.  "
        val speech = segment(raw)
        val result = analyzer.analyze(speech)
        assertEquals("Hmm saya saya mungkin.", result.text)
        assertEquals(raw, speech.rawText)
        val edited = analyzer.analyze(speech.copy(editedText = "Saya tidak yakin."))
        assertEquals(listOf(VerbatimCueType.UNCERTAINTY), edited.cues.map { it.type })
        assertEquals(VerbatimCueSource.USER_EDITED, edited.cues.single().source)
    }

    @Test
    fun `partials unsupported languages and phrases crossing punctuation abstain`() {
        assertTrue(analyzer.analyze(segment("mungkin").copy(isFinal = false)).cues.isEmpty())
        assertTrue(analyzer.analyze(segment("mungkin").copy(language = "unknown")).cues.isEmpty())
        assertTrue(analyzer.analyze(segment("Maksud. Saya datang.")).cues.isEmpty())
    }

    @Test
    fun `English phrases and repeated pronouns use the English lexicon`() {
        val result = analyzer.analyze(segment("Um I I think so, I am not sure.").copy(language = "en"))
        assertEquals(
            listOf(VerbatimCueType.FILLED_PAUSE, VerbatimCueType.REPETITION, VerbatimCueType.UNCERTAINTY),
            result.cues.map {
                it.type
            }
        )
        listOf("I’m not sure.", "I don’t know.").forEach { text ->
            val cue = analyzer.analyze(segment(text).copy(language = "en")).cues.single()
            assertEquals(VerbatimCueType.UNCERTAINTY, cue.type)
            assertEquals(text.substring(cue.startChar, cue.endCharExclusive), cue.matchedText)
        }
    }

    @Test
    fun `questionable raw ASR cannot become a linguistic cue but explicit edits can`() {
        val raw = "mungkin mungkin mungkin mungkin"
        val original = segment(raw)
        assertTrue(analyzer.analyze(original).cues.isEmpty())
        assertEquals(raw, original.rawText)
        val edited = analyzer.analyze(original.copy(editedText = "Saya tidak yakin."))
        assertEquals(VerbatimCueType.UNCERTAINTY, edited.cues.single().type)
        assertEquals(VerbatimCueSource.USER_EDITED, edited.cues.single().source)
    }

    private fun segment(text: String) = SpeechSegment(
        id = "utterance", speakerId = "Speaker_0", startOffsetMs = 1_200L, endOffsetMs = 4_800L,
        startTimestampUtc = 1_200L, endTimestampUtc = 4_800L, rawText = text, confidence = 1f, sessionId = "session"
    )
}

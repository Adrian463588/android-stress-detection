package com.stressdetector.mobile.data.repository

import com.stressdetector.mobile.data.db.TranscriptSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedSessionRepositoryTest {
    @Test
    fun `archived transcript retains row ownership and exact raw text`() {
        val row = transcriptRow()
        val restored = row.toSpeechSegment(10_000L)

        assertEquals("saved-session", restored.sessionId)
        assertEquals("archived-segment", restored.id)
        assertEquals(row.rawText, restored.rawText)
        assertEquals(10_100L, restored.startTimestampUtc)
        assertEquals(12_000L, restored.endTimestampUtc)
        assertEquals("Patient", restored.speakerId)
    }

    @Test
    fun `archived transcript preserves separate edits without inventing edits from raw copies`() {
        val row = transcriptRow()
        listOf(null to null, row.rawText to null, "Koreksi pengguna." to "Koreksi pengguna.", "" to "")
            .forEach { (stored, expected) ->
                val restored = row.copy(cleanedText = stored).toSpeechSegment(10_000L)
                assertEquals(expected, restored.editedText)
                assertEquals(row.rawText, restored.rawText)
                assertEquals("saved-session", restored.sessionId)
            }
    }

    @Test
    fun `failed finalization is never persisted as completed`() {
        assertEquals("FAILED", sessionStatusForFinalization("FAILED"))
        assertEquals("FAILED", sessionStatusForFinalization("ERROR_FINALIZATION_FAILED"))
    }

    @Test
    fun `process death keeps an explicit interrupted status`() {
        assertEquals(
            "INTERRUPTED",
            sessionStatusForFinalization("INTERRUPTED_PROCESS_DEATH")
        )
    }

    @Test
    fun `completed artifact publication remains completed`() {
        assertEquals("COMPLETED", sessionStatusForFinalization("COMPLETED"))
    }

    private fun transcriptRow() = TranscriptSegmentEntity(
        segmentId = "archived-segment",
        sessionId = "saved-session",
        speakerId = "Patient",
        startOffsetMs = 100L,
        endOffsetMs = 2_000L,
        rawText = "  hmm\n Mungkin saya ingat. ",
        cleanedText = null,
        confidence = 0.9f,
        language = "id"
    )
}

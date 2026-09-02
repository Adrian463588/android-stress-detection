package com.stressdetector.mobile.audio

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTranscriptionWorkQueueTest {
    @Test
    fun workItemsAreOrderedAndInternalDrainCanFinishAfterInputCloses() {
        val directory = Files.createTempDirectory("speech-work-queue").toFile()
        try {
            val queue = SpeechTranscriptionWorkQueue(directory)
            val firstReference = directory.resolve("first.frame")
            val secondReference = directory.resolve("second.frame")

            assertTrue(queue.offer(SpeechTranscriptionWork.Capture(firstReference)))
            assertTrue(queue.offer(SpeechTranscriptionWork.Frame(secondReference)))
            assertEquals(
                SpeechTranscriptionWork.Capture(firstReference),
                queue.poll()
            )
            assertEquals(
                SpeechTranscriptionWork.Frame(secondReference),
                queue.poll()
            )

            queue.close()
            assertFalse(queue.offer(SpeechTranscriptionWork.FinalizeUtterance))
            assertTrue(
                queue.offer(
                    SpeechTranscriptionWork.FinalizeUtterance,
                    allowAfterClose = true
                )
            )
            assertEquals(SpeechTranscriptionWork.FinalizeUtterance, queue.poll())
        } finally {
            directory.deleteRecursively()
        }
    }
}

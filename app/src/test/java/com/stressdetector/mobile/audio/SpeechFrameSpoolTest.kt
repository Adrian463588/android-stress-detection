package com.stressdetector.mobile.audio

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechFrameSpoolTest {
    @Test
    fun completedFrameCanBeReadAndDeletedWithoutExposingPartialFile() {
        val directory = Files.createTempDirectory("speech-frame-spool").toFile()
        try {
            val spool = SpeechFrameSpool(directory, sampleRateHz = 1_000)
            val frame = SpeechFrame(
                samples = floatArrayOf(-0.5f, 0f, 0.5f),
                startOffsetMs = 240L,
                durationMs = 3L,
                sequence = 17L,
                sampleStartIndex = 3_840L
            )

            val reference = spool.write(frame)

            assertTrue(reference.isFile)
            assertFalse(directory.resolve("${reference.name}.part").exists())
            val restored = spool.read(reference)
            assertEquals(frame.startOffsetMs, restored.startOffsetMs)
            assertEquals(frame.durationMs, restored.durationMs)
            assertEquals(frame.sequence, restored.sequence)
            assertEquals(frame.sampleStartIndex, restored.sampleStartIndex)
            assertArrayEquals(frame.samples, restored.samples, 1f / Short.MAX_VALUE)

            spool.delete(reference)
            assertFalse(reference.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}

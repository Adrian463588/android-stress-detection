package com.stressdetector.mobile.domain.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WavFileValidatorTest {
    @Test
    fun acceptsPcmWavWithAudioData() {
        val file = temporaryFile(validPcmWav())
        try {
            WavFileValidator.validate(file.toFile())
            assertTrue(file.toFile().exists())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun rejectsNonWavFile() {
        val file = temporaryFile("not audio".toByteArray())
        try {
            assertThrows(IllegalArgumentException::class.java) {
                WavFileValidator.validate(file.toFile())
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun temporaryFile(bytes: ByteArray): Path = Files.createTempFile("verbatim-playback-", ".wav").also { it.writeBytes(bytes) }

    private fun validPcmWav(): ByteArray {
        val bytes = ByteArray(46)
        "RIFF".toByteArray().copyInto(bytes, 0)
        littleEndian(bytes, 4, 38)
        "WAVE".toByteArray().copyInto(bytes, 8)
        "fmt ".toByteArray().copyInto(bytes, 12)
        littleEndian(bytes, 16, 16)
        littleEndian(bytes, 20, 1)
        littleEndian(bytes, 22, 1)
        littleEndian(bytes, 24, 16_000)
        littleEndian(bytes, 28, 32_000)
        littleEndian(bytes, 32, 2)
        littleEndian(bytes, 34, 16)
        "data".toByteArray().copyInto(bytes, 36)
        littleEndian(bytes, 40, 2)
        bytes[44] = 0
        bytes[45] = 0
        return bytes
    }

    private fun littleEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}

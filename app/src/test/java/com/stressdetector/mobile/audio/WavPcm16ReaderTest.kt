package com.stressdetector.mobile.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WavPcm16ReaderTest {
    @Test
    fun parsesCanonicalPcmWavAndRejectsWrongSampleRate() {
        val file = File.createTempFile("verbatim-test", ".wav")
        try {
            writeWav(file, sampleRateHz = 16_000, dataBytes = 320)
            val format = WavPcm16Reader.readFormat(file)
            assertEquals(16_000, format.sampleRateHz)
            assertEquals(320L, format.dataLength)
            assertEquals(10L, format.durationMs)

            writeWav(file, sampleRateHz = 8_000, dataBytes = 320)
            assertThrows(IllegalArgumentException::class.java) { WavPcm16Reader.readFormat(file) }
        } finally {
            file.delete()
        }
    }

    private fun writeWav(file: File, sampleRateHz: Int, dataBytes: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRateHz)
        header.putInt(sampleRateHz * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        FileOutputStream(file).use { output ->
            output.write(header.array())
            output.write(ByteArray(dataBytes))
        }
    }
}

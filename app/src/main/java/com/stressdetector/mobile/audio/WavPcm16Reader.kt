@file:Suppress("MagicNumber")

package com.stressdetector.mobile.audio

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class WavPcmFormat(
    val audioFormat: Int,
    val channels: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataLength: Long
) {
    val durationMs: Long
        get() = dataLength * 1_000L / (sampleRateHz.toLong() * channels * (bitsPerSample / 8))

    fun requireCanonical() {
        require(audioFormat == 1) { "Only uncompressed PCM WAV is supported." }
        require(channels == 1) { "Imported WAV must be mono." }
        require(sampleRateHz == 16_000) { "Imported WAV must use 16 kHz." }
        require(bitsPerSample == 16) { "Imported WAV must use 16-bit PCM." }
        require(dataLength > 0L) { "Imported WAV contains no PCM data." }
        require(dataLength % 2L == 0L) { "Imported WAV contains an incomplete PCM sample." }
    }
}

/** Strict parser for real, supported import files; unsupported codecs fail closed. */
internal object WavPcm16Reader {
    fun readFormat(file: File): WavPcmFormat = FileInputStream(file).use { input ->
        require(readAscii(input, HEADER_FIELD_BYTES) == "RIFF") { "Invalid WAV RIFF header." }
        readUInt32(input) // RIFF chunk size
        require(readAscii(input, HEADER_FIELD_BYTES) == "WAVE") { "Invalid WAV format tag." }

        var format: WavPcmFormat? = null
        var dataOffset = -1L
        var dataLength = -1L
        var cursor = RIFF_HEADER_BYTES.toLong()
        while (true) {
            val id = runCatching { readAscii(input, HEADER_FIELD_BYTES) }.getOrNull() ?: break
            val chunkLength = readUInt32(input)
            cursor += CHUNK_HEADER_BYTES
            when (id) {
                "fmt " -> {
                    require(chunkLength >= FMT_CHUNK_BYTES) { "Invalid WAV fmt chunk." }
                    val bytes = input.readExactly(FMT_CHUNK_BYTES.toInt())
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    format = WavPcmFormat(
                        audioFormat = buffer.short.toInt() and UINT16_MASK,
                        channels = buffer.short.toInt() and UINT16_MASK,
                        sampleRateHz = buffer.int,
                        bitsPerSample = run {
                            buffer.int // byte rate
                            buffer.short // block alignment
                            buffer.short.toInt() and UINT16_MASK
                        },
                        dataOffset = 0L,
                        dataLength = 0L
                    )
                    skipFully(input, chunkLength - FMT_CHUNK_BYTES)
                }
                "data" -> {
                    dataOffset = cursor
                    dataLength = chunkLength
                    skipFully(input, chunkLength)
                }
                else -> skipFully(input, chunkLength)
            }
            cursor += chunkLength
            if ((chunkLength and ODD_BYTE_MASK) != 0L) {
                skipFully(input, PADDING_BYTES)
                cursor += PADDING_BYTES
            }
        }
        val parsed = requireNotNull(format) { "WAV fmt chunk is missing." }
        require(dataOffset >= 0L && dataLength >= 0L) { "WAV data chunk is missing." }
        parsed.copy(dataOffset = dataOffset, dataLength = dataLength).also(WavPcmFormat::requireCanonical)
    }

    fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) error("Unexpected end of WAV stream.")
            remaining -= 1L
        }
    }

    private fun readAscii(input: InputStream, length: Int): String = input.readExactly(length).toString(Charsets.US_ASCII)

    private fun readUInt32(input: InputStream): Long {
        val bytes = input.readExactly(UINT32_BYTES)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and UINT32_MASK
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(bytes, offset, length - offset)
            if (count < 0) error("Unexpected end of WAV stream.")
            offset += count
        }
        return bytes
    }

    private const val HEADER_FIELD_BYTES = 4
    private const val UINT32_BYTES = 4
    private const val RIFF_HEADER_BYTES = 12L
    private const val CHUNK_HEADER_BYTES = 8L
    private const val FMT_CHUNK_BYTES = 16L
    private const val PADDING_BYTES = 1L
    private const val ODD_BYTE_MASK = 1L
    private const val UINT16_MASK = 0xffff
    private const val UINT32_MASK = 0xffff_ffffL
}

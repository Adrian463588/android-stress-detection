package com.stressdetector.mobile.ml.rag

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFormatTest {
    @Test
    fun `detects only explicitly supported formats from provider metadata`() {
        assertEquals(DocumentFormat.PDF, DocumentFormats.detect("application/pdf", "record.bin"))
        assertEquals(
            DocumentFormat.DOCX,
            DocumentFormats.detect(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "record.bin"
            )
        )
        assertEquals(DocumentFormat.DOCX, DocumentFormats.detect(null, "record.docx"))
        assertEquals(DocumentFormat.PLAIN_TEXT, DocumentFormats.detect("text/plain", "record.txt"))
        assertEquals(DocumentFormat.MARKDOWN, DocumentFormats.detect("text/markdown", "record.bin"))
        assertEquals(DocumentFormat.MARKDOWN, DocumentFormats.detect(null, "notes.md"))
    }

    @Test(expected = DocumentParseException::class)
    fun `legacy binary DOC is rejected instead of parsed as UTF-8`() {
        DocumentFormats.detect("application/msword", "legacy.doc")
    }

    @Test(expected = DocumentParseException::class)
    fun `unknown format is rejected instead of defaulting to plain text`() {
        DocumentFormats.detect("application/octet-stream", "record.bin")
    }

    @Test
    fun `signature verification rejects binary content mislabeled as text`() {
        val file = File.createTempFile("binary_", ".txt")
        try {
            file.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x7f))
            val error = runCatching {
                DocumentFormats.verifySignature(DocumentFormat.PLAIN_TEXT, file)
            }.exceptionOrNull()
            assertTrue(error is DocumentParseException)
            assertTrue(error?.message.orEmpty().contains("biner", ignoreCase = true))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `signature verification accepts PDF DOCX and strict UTF-8 text`() {
        val pdf = File.createTempFile("fixture_", ".pdf")
        val docx = File.createTempFile("fixture_", ".docx")
        val text = File.createTempFile("fixture_", ".txt")
        try {
            pdf.writeBytes("%PDF-1.7\n".toByteArray())
            ZipOutputStream(docx.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("<w:document/>".toByteArray())
                zip.closeEntry()
            }
            text.writeText("Dokumen konseling UTF-8: aman.", Charsets.UTF_8)

            DocumentFormats.verifySignature(DocumentFormat.PDF, pdf)
            DocumentFormats.verifySignature(DocumentFormat.DOCX, docx)
            DocumentFormats.verifySignature(DocumentFormat.PLAIN_TEXT, text)
        } finally {
            pdf.delete()
            docx.delete()
            text.delete()
        }
    }

    @Test(expected = DocumentParseException::class)
    fun `strict UTF-8 reader rejects malformed byte sequences`() {
        StrictUtf8TextReader.read(
            byteArrayOf(0xC3.toByte(), 0x28).inputStream(),
            maxCharacters = 32
        )
    }

    @Test
    fun `source byte ceiling is exactly 100 MiB`() {
        assertEquals(104_857_600L, DocumentIngestionLimits.MAX_FILE_BYTES)
    }
}

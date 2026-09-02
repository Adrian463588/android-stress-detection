package com.stressdetector.mobile.ml.rag

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Supported local knowledge-base formats. */
enum class DocumentFormat {
    PDF,
    DOCX,
    PLAIN_TEXT,
    MARKDOWN
}

object DocumentFormats {
    fun detect(mimeType: String?, displayName: String?): DocumentFormat {
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
        val name = displayName.orEmpty().substringBefore('?').substringBefore('#').lowercase()
        if (mime == LEGACY_DOC_MIME || name.endsWith(".doc")) {
            throw DocumentParseException("Format .doc lama tidak didukung; konversikan ke .docx atau PDF.")
        }
        val mimeFormat = formatFromMime(mime)
        val extensionFormat = formatFromExtension(name)
        if (mimeFormat != null && extensionFormat != null && !formatsCompatible(mimeFormat, extensionFormat)) {
            throw DocumentParseException("Tipe dokumen tidak cocok dengan ekstensi file.")
        }
        return when {
            extensionFormat == DocumentFormat.MARKDOWN && mimeFormat == DocumentFormat.PLAIN_TEXT -> DocumentFormat.MARKDOWN
            else ->
                mimeFormat ?: extensionFormat
                    ?: throw DocumentParseException(
                        "Format dokumen tidak didukung. Gunakan PDF, DOCX, TXT UTF-8, atau Markdown."
                    )
        }
    }

    /** Verifies bytes after SAF content has been copied to app-private storage. */
    fun verifySignature(format: DocumentFormat, file: File) {
        require(file.isFile && file.length() > 0L) { "Dokumen lokal tidak dapat dibaca." }
        when (format) {
            DocumentFormat.PDF -> requirePrefix(file, PDF_SIGNATURE, "File bukan PDF yang valid.")
            DocumentFormat.DOCX -> verifyDocx(file)
            DocumentFormat.PLAIN_TEXT,
            DocumentFormat.MARKDOWN
            -> StrictUtf8TextReader.validate(file)
        }
    }

    private fun formatFromMime(mime: String): DocumentFormat? = when (mime) {
        "application/pdf" -> DocumentFormat.PDF
        DOCX_MIME -> DocumentFormat.DOCX
        "text/plain" -> DocumentFormat.PLAIN_TEXT
        "text/markdown", "text/x-markdown" -> DocumentFormat.MARKDOWN
        "", "application/octet-stream" -> null
        else -> null
    }

    private fun formatFromExtension(name: String): DocumentFormat? = when {
        name.endsWith(".pdf") -> DocumentFormat.PDF
        name.endsWith(".docx") -> DocumentFormat.DOCX
        name.endsWith(".txt") -> DocumentFormat.PLAIN_TEXT
        name.endsWith(".md") || name.endsWith(".markdown") -> DocumentFormat.MARKDOWN
        else -> null
    }

    private fun formatsCompatible(mime: DocumentFormat, extension: DocumentFormat): Boolean =
        mime == extension || mime == DocumentFormat.PLAIN_TEXT && extension == DocumentFormat.MARKDOWN

    private fun requirePrefix(file: File, expected: ByteArray, message: String) {
        val prefix = file.inputStream().use { input -> ByteArray(expected.size).also { input.read(it) } }
        if (!prefix.contentEquals(expected)) throw DocumentParseException(message)
    }

    private fun verifyDocx(file: File) {
        try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("word/document.xml") == null) {
                    throw DocumentParseException("File bukan DOCX WordprocessingML yang valid.")
                }
            }
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: Exception) {
            throw DocumentParseException("File bukan DOCX WordprocessingML yang valid.", error)
        }
    }

    private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
    private const val LEGACY_DOC_MIME = "application/msword"
    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
}

/** Strict UTF-8 reader with a deterministic binary-content guard. */
object StrictUtf8TextReader {
    fun validate(file: File) {
        file.inputStream().use { read(it, DocumentIngestionLimits.MAX_TEXT_CHARACTERS) }
    }

    fun read(input: java.io.InputStream, maxCharacters: Int): String {
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count.toLong() > DocumentIngestionLimits.MAX_FILE_BYTES) {
                    throw DocumentParseException("Ukuran dokumen melebihi batas impor lokal.")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.any { it == 0.toByte() }) {
            throw DocumentParseException("Dokumen teks berisi data biner dan tidak dapat diindeks.")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw DocumentParseException("Dokumen teks harus menggunakan UTF-8 yang valid.", error)
        }
        if (text.length > maxCharacters) {
            throw DocumentParseException("Teks dokumen melebihi batas impor lokal.")
        }
        val suspiciousControls = text.count { character ->
            character.code in 1..31 && character !in ALLOWED_CONTROLS
        }
        if (suspiciousControls > maxOf(1, text.length / MAX_CONTROL_RATIO_DIVISOR)) {
            throw DocumentParseException("Dokumen teks berisi data biner dan tidak dapat diindeks.")
        }
        return text
    }

    private val ALLOWED_CONTROLS = setOf('\t', '\n', '\r', '\u000C')
    private const val BUFFER_BYTES = 8 * 1024
    private const val MAX_CONTROL_RATIO_DIVISOR = 100
}

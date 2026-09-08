package com.stressdetector.mobile.data.search

import android.content.Context
import com.stressdetector.mobile.domain.search.ScrapedPage
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small local-only cache for the exact approved-source text used to ground a
 * response. It is deliberately temporary, bounded, and never uploaded.
 */
interface WebResearchEvidenceStore {
    suspend fun store(pages: List<ScrapedPage>)

    data object NoOp : WebResearchEvidenceStore {
        override suspend fun store(pages: List<ScrapedPage>) = Unit
    }
}

class FileBackedWebResearchEvidenceStore(context: Context, private val nowMillis: () -> Long = { System.currentTimeMillis() }) :
    WebResearchEvidenceStore {
    private val directory = File(context.cacheDir, "ai_web_evidence")

    override suspend fun store(pages: List<ScrapedPage>) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        prune()
        pages.forEach { page ->
            val hash = sha256(page.url + page.text)
            val finalFile = File(directory, "$hash.json")
            val temporaryFile = File(directory, "$hash.pending")
            temporaryFile.writeText(
                "{\"url\":\"${page.url.jsonEscaped()}\",\"title\":\"${page.title.jsonEscaped()}\"," +
                    "\"retrievedAtMillis\":${page.retrievedAtMillis},\"contentHash\":\"${sha256(page.text)}\"," +
                    "\"text\":\"${page.text.jsonEscaped()}\"}",
                Charsets.UTF_8
            )
            if (!temporaryFile.renameTo(finalFile)) {
                temporaryFile.copyTo(finalFile, overwrite = true)
                temporaryFile.delete()
            }
        }
        prune()
    }

    private fun prune() {
        val cutoff = nowMillis() - RETENTION_MILLIS
        directory.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { file -> file.delete() }
        directory.listFiles().orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_EVIDENCE_FILES)
            .forEach { file -> file.delete() }
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        const val MAX_EVIDENCE_FILES = 15
    }
}

private fun String.jsonEscaped(): String = buildString(length) {
    for (character in this@jsonEscaped) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

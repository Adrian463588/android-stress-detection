package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.data.db.ClinicalDocumentEntity
import java.security.MessageDigest

/**
 * Stable, privacy-preserving WorkManager identity. A new content checksum or
 * processing revision produces a different unique-work name without exposing
 * a source URI/path in WorkManager diagnostics.
 */
object DocumentWorkIdentity {
    /** De-duplicates an active selection without exposing its URI in WorkManager metadata. */
    fun importUniqueName(uri: String): String {
        require(uri.isNotBlank())
        return "rag-import-" + sha256("rag-import-v1:$uri").take(32)
    }

    fun reindexUniqueName(document: ClinicalDocumentEntity): String = reindexUniqueName(
        documentId = document.documentId,
        sourceSha256 = document.sourceSha256.orEmpty(),
        processingRevision = document.processingRevision
    )

    fun reindexUniqueName(documentId: String, sourceSha256: String, processingRevision: Int): String {
        require(documentId.isNotBlank())
        require(sourceSha256.isNotBlank())
        require(processingRevision >= 1)
        val material = "rag-reindex-v1:$documentId:$sourceSha256:$processingRevision"
        return "rag-reindex-" + sha256(material).take(32)
    }

    fun reindexTag(documentId: String): String {
        require(documentId.isNotBlank())
        return "rag-document-" + sha256(documentId).take(20)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

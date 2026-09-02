package com.stressdetector.mobile.ml.rag

import android.content.Context
import com.stressdetector.mobile.data.db.DocumentDao
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * One-way migration for legacy plaintext sources. A row is updated only after
 * the encrypted copy has a matching SHA-256; failed copies remain untouched.
 */
class LegacyKnowledgeBaseMigrator(
    context: Context,
    private val documentDao: DocumentDao,
    private val sourceStore: EncryptedDocumentSourceStore
) {
    private val legacyRoot = File(context.applicationContext.filesDir, "app_knowledge_base").canonicalFile

    suspend fun migratePlaintextSources(): Int {
        var migrated = 0
        documentDao.getAllDocuments().first().forEach { document ->
            if (EncryptedSourceRef.fromDatabasePath(document.filePath, document.sourceSha256.orEmpty()) != null) return@forEach
            val source = runCatching { File(document.filePath).canonicalFile }.getOrNull() ?: return@forEach
            if (!source.isFile || !source.path.startsWith(legacyRoot.path + File.separator)) return@forEach
            val encrypted = sourceStore.put(document.documentId, source.inputStream(), document.sourceFileName ?: source.name)
            if (document.sourceSha256 != null && !document.sourceSha256.equals(encrypted.sha256, ignoreCase = true)) {
                runCatching { sourceStore.delete(encrypted) }
                return@forEach
            }
            documentDao.updateSourceReference(document.documentId, encrypted.asDatabasePath())
            check(!source.exists() || source.delete()) { "Legacy plaintext source could not be deleted." }
            migrated++
        }
        return migrated
    }
}

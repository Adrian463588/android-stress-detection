package com.stressdetector.mobile.ml.rag

import android.content.Context
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class EncryptedSourceRef(val relativePath: String, val sha256: String, val sizeBytes: Long) {
    fun asDatabasePath(): String = "$SCHEME$relativePath"

    companion object {
        const val SCHEME = "encrypted://rag/"

        fun fromDatabasePath(path: String, sha256: String, sizeBytes: Long = 0L): EncryptedSourceRef? {
            if (!path.startsWith(SCHEME)) return null
            return EncryptedSourceRef(path.removePrefix(SCHEME), sha256, sizeBytes)
        }
    }
}

interface EncryptedDocumentSourceStore {
    suspend fun put(documentId: String, source: InputStream, fileName: String): EncryptedSourceRef
    suspend fun open(ref: EncryptedSourceRef): InputStream
    suspend fun delete(ref: EncryptedSourceRef)
}

/**
 * Persists imported knowledge documents through the existing Keystore-backed
 * AES-GCM storage boundary. The plaintext staging file is deleted after the
 * encrypted publish attempt.
 */
class AndroidEncryptedDocumentSourceStore(context: Context, private val storage: EncryptedStorageService) : EncryptedDocumentSourceStore {
    private val cacheDirectory = File(context.applicationContext.cacheDir, "rag_source_staging")

    override suspend fun put(documentId: String, source: InputStream, fileName: String): EncryptedSourceRef {
        cacheDirectory.mkdirs()
        val staged = File(cacheDirectory, "${UUID.randomUUID()}_${safeName(fileName)}")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            source.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        size += count
                    }
                }
            }
            val relativePath = "$documentId/${safeName(fileName)}"
            staged.inputStream().use { input ->
                storage.writeEncryptedFile(USER_ID, relativePath, input).also {
                    check(it.sizeBytes > 0L) { "Encrypted document publish returned an empty artifact." }
                }
            }
            return EncryptedSourceRef(relativePath, digest.digest().toHex(), size)
        } finally {
            staged.delete()
        }
    }

    override suspend fun open(ref: EncryptedSourceRef): InputStream = storage.readEncryptedFile(USER_ID, ref.relativePath)

    override suspend fun delete(ref: EncryptedSourceRef) {
        check(storage.deleteEncryptedFile(USER_ID, ref.relativePath)) {
            "Encrypted document source could not be deleted: ${ref.relativePath}"
        }
    }

    private fun safeName(name: String): String = name
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120)
        .ifBlank { "document.bin" }

    private companion object {
        const val USER_ID = "rag-knowledge-base"
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

package com.stressdetector.mobile.security

import java.io.ByteArrayInputStream
import java.security.SecureRandom

/** Stores the SQLCipher passphrase only as an AES-GCM encrypted artifact. */
class KnowledgeBasePassphraseStore(private val storage: EncryptedStorageService) {
    fun getOrCreate(): ByteArray {
        val existing = runCatching {
            storage.readEncryptedFile(USER_ID, FILE_NAME).use { it.readBytes() }
        }.getOrNull()
        if (existing?.size == PASSPHRASE_BYTES) return existing

        val generated = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
        storage.writeEncryptedFile(USER_ID, FILE_NAME, ByteArrayInputStream(generated))
        return generated
    }

    private companion object {
        const val USER_ID = "rag-knowledge-database"
        const val FILE_NAME = "sqlcipher-passphrase.bin"
        const val PASSPHRASE_BYTES = 32
    }
}

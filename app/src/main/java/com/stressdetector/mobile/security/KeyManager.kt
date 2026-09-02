package com.stressdetector.mobile.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface KeyManager {
    fun getOrCreateFileEncryptionKey(userId: String): SecretKey
    fun getExistingFileEncryptionKey(userId: String): SecretKey
    fun deleteKeys(userId: String)
    fun isKeyValid(userId: String): Boolean
}

sealed class KeyManagerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingKey : KeyManagerException("The encryption key for this local profile is unavailable.")
    class Unavailable(cause: Throwable) : KeyManagerException("The encryption key could not be read.", cause)
}

/** AES-GCM keys remain non-exportable inside Android Keystore. */
class AndroidKeystoreKeyManager : KeyManager {
    override fun getOrCreateFileEncryptionKey(userId: String): SecretKey {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val keyStore = keyStore()
        val alias = aliasFor(userId)
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    override fun getExistingFileEncryptionKey(userId: String): SecretKey {
        require(userId.isNotBlank()) { "userId must not be blank" }
        return try {
            val keyStore = keyStore()
            val alias = aliasFor(userId)
            if (!keyStore.containsAlias(alias)) throw KeyManagerException.MissingKey()
            (keyStore.getKey(alias, null) as? SecretKey) ?: throw KeyManagerException.MissingKey()
        } catch (error: KeyManagerException) {
            throw error
        } catch (error: Exception) {
            throw KeyManagerException.Unavailable(error)
        }
    }

    override fun deleteKeys(userId: String) {
        keyStore().deleteEntry(aliasFor(userId))
    }

    override fun isKeyValid(userId: String): Boolean = runCatching {
        keyStore().containsAlias(aliasFor(userId)) && keyStore().getKey(aliasFor(userId), null) is SecretKey
    }.getOrDefault(false)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun aliasFor(userId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "stress_sensitive_files_$digest"
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

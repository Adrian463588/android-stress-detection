package com.stressdetector.mobile.security

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedFileRef(val relativePath: String, val sizeBytes: Long)

sealed class SecureStorageException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidPath : SecureStorageException("Sensitive file path is invalid.")
    class MissingFile : SecureStorageException("The encrypted artifact is missing.")
    class KeyUnavailable(cause: Throwable) : SecureStorageException("The encryption key is unavailable.", cause)
    class AuthenticationFailed(cause: Throwable) : SecureStorageException("Encrypted artifact authentication failed.", cause)
    class MalformedFile(cause: Throwable) : SecureStorageException("The encrypted artifact header is invalid.", cause)
    class EncryptionFailed(cause: Throwable) : SecureStorageException("Sensitive data encryption failed.", cause)
    class DecryptionFailed(cause: Throwable) : SecureStorageException("Sensitive data decryption failed.", cause)
    class DeletionFailed : SecureStorageException("Sensitive data could not be deleted.")
}

interface EncryptedStorageService {
    fun writeEncryptedFile(userId: String, fileName: String, data: InputStream): EncryptedFileRef

    /** Create-only move of a staged file encrypted with the same installation key. */
    fun moveNewEncryptedFile(userId: String, source: String, target: String): EncryptedFileRef =
        throw UnsupportedOperationException("Create-only encrypted publication is not supported by this provider")
    fun readEncryptedFile(userId: String, relativePath: String): InputStream
    fun copyEncryptedFile(userId: String, relativePath: String, output: OutputStream) {
        readEncryptedFile(userId, relativePath).use { input -> input.copyTo(output) }
    }
    fun deleteEncryptedFile(userId: String, relativePath: String): Boolean
    fun listEncryptedFiles(userId: String): List<EncryptedFileRef>
    fun deleteAllUserFiles(userId: String)
}

class AndroidEncryptedStorageService(context: Context, private val keyManager: KeyManager) : EncryptedStorageService {
    private val root = File(context.applicationContext.filesDir, "sensitive")

    override fun moveNewEncryptedFile(userId: String, source: String, target: String): EncryptedFileRef {
        val staged = resolveUserFile(userId, source)
        require(staged.isFile) { "Staged encrypted artifact is missing" }
        val destination = resolveUserFile(userId, target)
        destination.parentFile?.mkdirs()
        AtomicFilePublisher.moveNew(staged, destination)
        return EncryptedFileRef(target, destination.length())
    }

    override fun writeEncryptedFile(userId: String, fileName: String, data: InputStream): EncryptedFileRef {
        val destination = resolveUserFile(userId, fileName)
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        val backup = File(destination.parentFile, ".${destination.name}.bak")
        destination.parentFile?.mkdirs()
        return try {
            FileOutputStream(temporary).use { output ->
                AesGcmFileCipher.encrypt(keyManager.getOrCreateFileEncryptionKey(userId), data, output)
                output.fd.sync()
            }
            if (backup.exists() && !backup.delete()) throw SecureStorageException.DeletionFailed()
            if (destination.exists() && !destination.renameTo(backup)) {
                throw SecureStorageException.EncryptionFailed(IllegalStateException("Existing encrypted file could not be backed up"))
            }
            if (!temporary.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                throw SecureStorageException.EncryptionFailed(IllegalStateException("Atomic publish failed"))
            }
            if (backup.exists() && !backup.delete()) {
                throw SecureStorageException.DeletionFailed()
            }
            val relativePath = destination
                .relativeTo(userRoot(userId).canonicalFile)
                .invariantSeparatorsPath
            EncryptedFileRef(relativePath, destination.length())
        } catch (error: SecureStorageException) {
            temporary.delete()
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            throw error
        } catch (error: Exception) {
            temporary.delete()
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            throw SecureStorageException.EncryptionFailed(error)
        }
    }

    override fun readEncryptedFile(userId: String, relativePath: String): InputStream {
        val encrypted = resolveUserFile(userId, relativePath)
        if (!encrypted.isFile) throw SecureStorageException.MissingFile()
        return try {
            val output = ByteArrayOutputStream()
            FileInputStream(encrypted).use { AesGcmFileCipher.decrypt(keyManager.getExistingFileEncryptionKey(userId), it, output) }
            ByteArrayInputStream(output.toByteArray())
        } catch (error: KeyManagerException) {
            throw SecureStorageException.KeyUnavailable(error)
        } catch (error: EncryptedFileAuthenticationException) {
            throw SecureStorageException.AuthenticationFailed(error)
        } catch (error: EncryptedFileFormatException) {
            throw SecureStorageException.MalformedFile(error)
        } catch (error: FileNotFoundException) {
            throw SecureStorageException.MissingFile()
        } catch (error: SecureStorageException) {
            throw error
        } catch (error: Exception) {
            throw SecureStorageException.DecryptionFailed(error)
        }
    }

    override fun copyEncryptedFile(userId: String, relativePath: String, output: OutputStream) {
        val encrypted = resolveUserFile(userId, relativePath)
        if (!encrypted.isFile) throw SecureStorageException.MissingFile()
        try {
            FileInputStream(encrypted).use { input ->
                AesGcmFileCipher.decrypt(keyManager.getExistingFileEncryptionKey(userId), input, output)
            }
        } catch (error: KeyManagerException) {
            throw SecureStorageException.KeyUnavailable(error)
        } catch (error: EncryptedFileAuthenticationException) {
            throw SecureStorageException.AuthenticationFailed(error)
        } catch (error: EncryptedFileFormatException) {
            throw SecureStorageException.MalformedFile(error)
        } catch (error: FileNotFoundException) {
            throw SecureStorageException.MissingFile()
        } catch (error: SecureStorageException) {
            throw error
        } catch (error: Exception) {
            throw SecureStorageException.DecryptionFailed(error)
        }
    }

    override fun deleteEncryptedFile(userId: String, relativePath: String): Boolean {
        val file = resolveUserFile(userId, relativePath)
        return !file.exists() || file.delete()
    }

    override fun listEncryptedFiles(userId: String): List<EncryptedFileRef> {
        val root = userRoot(userId)
        if (!root.exists()) return emptyList()
        return root.walkTopDown().filter(File::isFile).map {
            EncryptedFileRef(it.relativeTo(root).invariantSeparatorsPath, it.length())
        }.toList()
    }

    override fun deleteAllUserFiles(userId: String) {
        val root = userRoot(userId)
        if (root.exists() && !root.deleteRecursively()) throw SecureStorageException.DeletionFailed()
    }

    private fun resolveUserFile(userId: String, relativePath: String): File {
        require(relativePath.isNotBlank()) { "fileName must not be blank" }
        val userRoot = userRoot(userId).canonicalFile
        val candidate = File(userRoot, relativePath).canonicalFile
        if (candidate == userRoot || !candidate.path.startsWith(userRoot.path + File.separator)) {
            throw SecureStorageException.InvalidPath()
        }
        return candidate
    }

    private fun userRoot(userId: String): File {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val id = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, id)
    }
}

/** Versioned file envelope: magic, version, IV length, IV, then AES-GCM ciphertext/tag. */
internal class EncryptedFileFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class EncryptedFileAuthenticationException(cause: Throwable) : Exception("Encrypted file authentication failed.", cause)

internal object AesGcmFileCipher {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'D'.code.toByte(), 'E'.code.toByte(), 'F'.code.toByte())
    private const val VERSION: Int = 1
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, input: InputStream, output: java.io.OutputStream) {
        // Let Android Keystore generate the nonce. Keys are created with
        // randomized encryption required, so supplying an application-created
        // GCM nonce is rejected by newer Android Keystore implementations.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            require(iv.size == IV_SIZE) { "Unexpected AES-GCM IV size" }
            updateAAD(MAGIC + VERSION.toByte())
        }
        val iv = cipher.iv
        output.write(MAGIC)
        output.write(VERSION)
        output.write(iv.size)
        output.write(iv)
        input.use {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                cipher.update(buffer, 0, count)?.let(output::write)
            }
        }
        output.write(cipher.doFinal())
    }

    fun decrypt(key: SecretKey, input: InputStream, output: java.io.OutputStream) {
        val actualMagic = ByteArray(MAGIC.size)
        readFully(input, actualMagic)
        if (!actualMagic.contentEquals(MAGIC)) throw EncryptedFileFormatException("Unknown encrypted file format")
        val actualVersion = readHeaderByte(input, "version")
        if (actualVersion != VERSION) throw EncryptedFileFormatException("Unsupported encrypted file version: $actualVersion")
        val actualIvSize = readHeaderByte(input, "IV length")
        if (actualIvSize !in 12..16) throw EncryptedFileFormatException("Invalid IV length")
        val iv = ByteArray(actualIvSize)
        readFully(input, iv)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(MAGIC + actualVersion.toByte())
            }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                cipher.update(buffer, 0, count)?.let(output::write)
            }
            output.write(cipher.doFinal())
        } catch (error: AEADBadTagException) {
            throw EncryptedFileAuthenticationException(error)
        } catch (error: BadPaddingException) {
            throw EncryptedFileAuthenticationException(error)
        }
    }

    private fun readHeaderByte(input: InputStream, field: String): Int =
        input.read().takeIf { it >= 0 } ?: throw EncryptedFileFormatException("Truncated encrypted file $field")

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EncryptedFileFormatException("Truncated encrypted file header", EOFException())
            if (count == 0) continue
            offset += count
        }
    }
}

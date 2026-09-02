package com.stressdetector.mobile.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmFileCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `round trip preserves content without plaintext envelope`() {
        val plaintext = "sensitive clinical recording".toByteArray()
        val encrypted = ByteArrayOutputStream()
        AesGcmFileCipher.encrypt(key, ByteArrayInputStream(plaintext), encrypted)

        assertFalse(encrypted.toByteArray().containsSubsequence(plaintext))

        val decrypted = ByteArrayOutputStream()
        AesGcmFileCipher.decrypt(key, ByteArrayInputStream(encrypted.toByteArray()), decrypted)
        assertArrayEquals(plaintext, decrypted.toByteArray())
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val encrypted = ByteArrayOutputStream()
        AesGcmFileCipher.encrypt(key, ByteArrayInputStream("private".toByteArray()), encrypted)
        val tampered = encrypted.toByteArray().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertThrows(EncryptedFileAuthenticationException::class.java) {
            AesGcmFileCipher.decrypt(key, ByteArrayInputStream(tampered), ByteArrayOutputStream())
        }
    }

    @Test
    fun `header reads tolerate streams that return partial reads`() {
        val encrypted = ByteArrayOutputStream()
        AesGcmFileCipher.encrypt(key, ByteArrayInputStream("partial header".toByteArray()), encrypted)

        val decrypted = ByteArrayOutputStream()
        AesGcmFileCipher.decrypt(key, OneByteInputStream(encrypted.toByteArray()), decrypted)

        assertArrayEquals("partial header".toByteArray(), decrypted.toByteArray())
    }

    @Test
    fun `truncated headers fail as malformed files`() {
        val error = assertThrows(EncryptedFileFormatException::class.java) {
            AesGcmFileCipher.decrypt(key, ByteArrayInputStream(byteArrayOf('S'.code.toByte())), ByteArrayOutputStream())
        }

        assertTrue(error.message?.contains("Truncated") == true)
    }

    private class OneByteInputStream(private val bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun read(target: ByteArray, offset: Int, length: Int): Int = super.read(target, offset, minOf(length, 1))
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean = indices.any { start ->
        start + candidate.size <= size && candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
    }
}

package com.stressdetector.mobile.security

import android.content.Context
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class EncryptedStorageServiceTest {
    @Test
    fun `reading with a missing key is reported separately from authentication failure`() {
        val root = Files.createTempDirectory("encrypted-storage-test").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(root)
        val keyManager = FakeKeyManager()
        val storage = AndroidEncryptedStorageService(context, keyManager)

        storage.writeEncryptedFile("profile", "verbatim/session/audio.wav.enc", "audio".byteInputStream())
        keyManager.available = false

        assertThrows(SecureStorageException.KeyUnavailable::class.java) {
            storage.readEncryptedFile("profile", "verbatim/session/audio.wav.enc")
        }

        root.deleteRecursively()
    }

    @Test
    fun `wrong existing key is reported as authentication failure`() {
        val root = Files.createTempDirectory("encrypted-storage-auth-test").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(root)
        val keyManager = FakeKeyManager()
        val storage = AndroidEncryptedStorageService(context, keyManager)

        storage.writeEncryptedFile("profile", "verbatim/session/audio.wav.enc", "audio".byteInputStream())
        keyManager.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertThrows(SecureStorageException.AuthenticationFailed::class.java) {
            storage.readEncryptedFile("profile", "verbatim/session/audio.wav.enc")
        }

        root.deleteRecursively()
    }

    @Test
    fun `round trip uses the existing key and preserves bytes`() {
        val root = Files.createTempDirectory("encrypted-storage-round-trip-test").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(root)
        val storage = AndroidEncryptedStorageService(context, FakeKeyManager())

        storage.writeEncryptedFile("profile", "verbatim/session/audio.wav.enc", "audio".byteInputStream())
        val actual = storage.readEncryptedFile("profile", "verbatim/session/audio.wav.enc").use { it.readBytes() }

        assertArrayEquals("audio".toByteArray(), actual)
        root.deleteRecursively()
    }

    private class FakeKeyManager : KeyManager {
        var key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var available: Boolean = true

        override fun getOrCreateFileEncryptionKey(userId: String): SecretKey = key

        override fun getExistingFileEncryptionKey(userId: String): SecretKey {
            if (!available) throw KeyManagerException.MissingKey()
            return key
        }

        override fun deleteKeys(userId: String) = Unit

        override fun isKeyValid(userId: String): Boolean = available
    }
}

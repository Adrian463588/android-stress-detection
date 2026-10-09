package com.stressdetector.mobile.lifecycle

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableBackupCipherTest {
    private val passphrase = "correct horse battery staple".toCharArray()
    private val bytes = ByteArray(2 * 64 * 1024 + 13) { index -> (index * 37 + 11).toByte() }

    @Test
    fun `round trip crosses frame boundaries with partial reads`() {
        val archive = encrypt(bytes)
        val restored = ByteArrayOutputStream()

        PortableBackupCipher.decrypt(OneByteInputStream(archive), restored, passphrase)

        assertArrayEquals(bytes, restored.toByteArray())
        assertFalse(archive.containsSubsequence(bytes.copyOfRange(100, 132)))
    }

    @Test
    fun `ZIP writer streams directly into encrypted output`() {
        val encrypted = ByteArrayOutputStream()
        PortableBackupCipher.encryptTo(encrypted, passphrase) { plaintext ->
            ZipOutputStream(plaintext).use { zip ->
                zip.putNextEntry(ZipEntry("numbers.bin"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        val restoredZip = ByteArrayOutputStream()
        PortableBackupCipher.decrypt(ByteArrayInputStream(encrypted.toByteArray()), restoredZip, passphrase)
        ZipInputStream(ByteArrayInputStream(restoredZip.toByteArray())).use { zip ->
            org.junit.Assert.assertEquals("numbers.bin", zip.nextEntry.name)
            assertArrayEquals(bytes, zip.readBytes())
            org.junit.Assert.assertNull(zip.nextEntry)
        }
    }

    @Test
    fun `producer failure cannot emit authenticated end frame`() {
        val encrypted = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            PortableBackupCipher.encryptTo(encrypted, passphrase) { plaintext ->
                plaintext.write(bytes, 0, 32)
                throw IOException("producer stopped")
            }
        }

        assertInvalid(encrypted.toByteArray())
    }

    @Test
    fun `empty archive requires authenticated end frame`() {
        val archive = encrypt(byteArrayOf())
        val restored = ByteArrayOutputStream()

        PortableBackupCipher.decrypt(ByteArrayInputStream(archive), restored, passphrase)

        assertArrayEquals(byteArrayOf(), restored.toByteArray())
        assertInvalid(archive.copyOf(archive.size - 1))
    }

    @Test
    fun `different encryptions use fresh salt and nonce`() {
        val first = encrypt(bytes.copyOfRange(0, 32))
        val second = encrypt(bytes.copyOfRange(0, 32))

        assertFalse(first.copyOfRange(9, 37).contentEquals(second.copyOfRange(9, 37)))
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `wrong passphrase and altered payload fail authentication`() {
        val archive = encrypt(bytes.copyOfRange(0, 80))
        assertThrows(IOException::class.java) {
            PortableBackupCipher.decrypt(
                ByteArrayInputStream(archive),
                ByteArrayOutputStream(),
                "wrong password long enough".toCharArray()
            )
        }
        assertInvalid(archive.copyOf().also { it[50] = (it[50].toInt() xor 1).toByte() })
        assertInvalid(archive.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
    }

    @Test
    fun `truncation missing end and appended bytes are rejected`() {
        val archive = encrypt(bytes.copyOfRange(0, 80))
        assertInvalid(archive.copyOf(8))
        assertInvalid(archive.copyOf(42))
        assertInvalid(archive.copyOf(archive.size - 20))
        assertInvalid(archive + byteArrayOf(0))
    }

    @Test
    fun `unsupported header and oversized frame are rejected before allocation`() {
        val archive = encrypt(bytes.copyOfRange(0, 80))
        assertInvalid(archive.copyOf().also { it[4] = 2 })
        assertInvalid(archive.copyOf().also { it[37] = 0x7f })
    }

    @Test
    fun `weak passphrase writes no archive bytes`() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCipher.encrypt(ByteArrayInputStream(bytes), output, "too short".toCharArray())
        }
        assertArrayEquals(byteArrayOf(), output.toByteArray())
    }

    @Test
    fun `caller staging must be discarded if later frame fails`() {
        val archive = encrypt(bytes)
        val output = ByteArrayOutputStream()
        val tampered = archive.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertThrows(IOException::class.java) {
            PortableBackupCipher.decrypt(ByteArrayInputStream(tampered), output, passphrase)
        }
        assertArrayEquals(bytes, output.toByteArray())
    }

    private fun encrypt(plaintext: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        PortableBackupCipher.encrypt(ByteArrayInputStream(plaintext), output, passphrase)
    }.toByteArray()

    private fun assertInvalid(archive: ByteArray) {
        assertThrows(IOException::class.java) {
            PortableBackupCipher.decrypt(ByteArrayInputStream(archive), ByteArrayOutputStream(), passphrase)
        }
    }

    private class OneByteInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun read(target: ByteArray, offset: Int, length: Int): Int = super.read(target, offset, minOf(length, 1))
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean = indices.any { start ->
        start + candidate.size <= size && candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
    }
}

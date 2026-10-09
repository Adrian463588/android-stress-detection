package com.stressdetector.mobile.lifecycle

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable backup envelope v1: magic, version, PBKDF2 iterations, 16-byte salt, 12-byte
 * random base nonce, then authenticated 64 KiB-or-smaller frames and an authenticated end frame.
 * Frame lengths are signed big-endian ints; -1 denotes the end frame. Nonces are unique within
 * the archive because the frame index is XORed into the base nonce's last four bytes.
 *
 * The caller owns the streams and passphrase. It must wipe its passphrase after use. In particular,
 * decrypt output is **untrusted until this method returns successfully**: write it only to a
 * private staging destination, then publish atomically. Discard staging on any exception.
 */
object PortableBackupCipher {
    private val magic = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 4 + 1 + 4 + SALT_BYTES + NONCE_BYTES
    private const val CHUNK_BYTES = 64 * 1024
    private const val ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val KEY_BITS = 256
    private const val MIN_PASSPHRASE_CHARS = 12
    private const val END_FRAME = -1

    /** Convenience input-stream adapter for [encryptTo]. */
    fun encrypt(inputStream: InputStream, outputStream: OutputStream, passphrase: CharArray) {
        encryptTo(outputStream, passphrase) { plaintext -> inputStream.copyTo(plaintext) }
    }

    /**
     * Streams a ZIP writer (or other producer) directly into ciphertext without a plaintext file.
     * Neither caller-owned output nor passphrase is closed/cleared. A failed callback leaves an
     * incomplete archive; the caller must discard its ciphertext staging destination.
     */
    fun encryptTo(output: OutputStream, passphrase: CharArray, writePlaintext: (OutputStream) -> Unit) {
        requirePassphrase(passphrase)
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val baseNonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(magic).put(VERSION).putInt(ITERATIONS).put(salt).put(baseNonce).array()
        val keyBytes = deriveKey(passphrase, salt, ITERATIONS)
        try {
            val key = SecretKeySpec(keyBytes, "AES")
            val context = FrameContext(key, header, baseNonce)
            val encrypted = DataOutputStream(output)
            encrypted.write(header)
            val plaintext = object : OutputStream() {
                private val chunk = ByteArray(CHUNK_BYTES)
                private var length = 0
                private var frameIndex = 0
                private var closed = false

                override fun write(value: Int) {
                    checkOpen()
                    chunk[length++] = value.toByte()
                    if (length == CHUNK_BYTES) sealChunk()
                }

                override fun write(source: ByteArray, offset: Int, count: Int) {
                    checkOpen()
                    if (offset < 0 || count < 0 || offset > source.size - count) {
                        throw IndexOutOfBoundsException("Invalid plaintext buffer range")
                    }
                    var cursor = offset
                    val end = offset + count
                    while (cursor < end) {
                        val copied = minOf(end - cursor, CHUNK_BYTES - length)
                        source.copyInto(chunk, length, cursor, cursor + copied)
                        length += copied
                        cursor += copied
                        if (length == CHUNK_BYTES) sealChunk()
                    }
                }

                override fun flush() {
                    checkOpen()
                    sealChunk()
                    encrypted.flush()
                }

                override fun close() {
                    if (!closed) {
                        sealChunk()
                        closed = true
                    }
                }

                fun finish() {
                    if (!closed) close()
                    writeFrame(encrypted, context, frameIndex, END_FRAME, chunk)
                }

                fun discard() {
                    chunk.fill(0)
                }

                private fun sealChunk() {
                    if (length == 0) return
                    if (frameIndex == Int.MAX_VALUE) throw IOException("Portable backup exceeds frame limit")
                    writeFrame(encrypted, context, frameIndex++, length, chunk)
                    chunk.fill(0)
                    length = 0
                }

                private fun checkOpen() {
                    if (closed) throw IOException("Plaintext stream is closed")
                }
            }
            try {
                writePlaintext(plaintext)
                plaintext.finish()
            } finally {
                plaintext.discard()
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    /**
     * Decrypts without closing either stream. Output is untrusted until this method returns:
     * earlier authenticated frames may already have been written when a later frame fails.
     */
    fun decrypt(inputStream: InputStream, outputStream: OutputStream, passphrase: CharArray) {
        requirePassphrase(passphrase)
        val input = DataInputStream(inputStream)
        val header = ByteArray(HEADER_BYTES)
        try {
            input.readFully(header)
        } catch (error: EOFException) {
            throw invalidArchive(error)
        }
        val fields = ByteBuffer.wrap(header)
        val actualMagic = ByteArray(magic.size).also { fields.get(it) }
        requireValidFrame(actualMagic.contentEquals(magic) && fields.get() == VERSION)
        val workFactor = fields.int
        requireValidFrame(workFactor in ITERATIONS..MAX_ITERATIONS)
        val salt = ByteArray(SALT_BYTES).also { fields.get(it) }
        val baseNonce = ByteArray(NONCE_BYTES).also { fields.get(it) }
        val keyBytes = deriveKey(passphrase, salt, workFactor)
        try {
            val context = FrameContext(SecretKeySpec(keyBytes, "AES"), header, baseNonce)
            var frameIndex = 0
            while (true) {
                val plaintext = readFrame(input, context, frameIndex) ?: return
                try {
                    outputStream.write(plaintext)
                } finally {
                    plaintext.fill(0)
                }
                frameIndex++
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun readFrame(input: DataInputStream, context: FrameContext, frameIndex: Int): ByteArray? {
        val length = try {
            input.readInt()
        } catch (error: EOFException) {
            throw invalidArchive(error)
        }
        requireValidFrame(length == END_FRAME || (length in 1..CHUNK_BYTES && frameIndex < Int.MAX_VALUE))
        val sealed = ByteArray((if (length == END_FRAME) 0 else length) + TAG_BYTES)
        try {
            try {
                input.readFully(sealed)
            } catch (error: EOFException) {
                throw invalidArchive(error)
            }
            val plaintext = openFrame(context, frameIndex, length, sealed)
            if (length != END_FRAME) {
                requireValidFrame(plaintext.size == length)
                return plaintext
            }
            requireValidFrame(plaintext.isEmpty() && input.read() == -1)
            plaintext.fill(0)
            return null
        } finally {
            sealed.fill(0)
        }
    }

    private fun requireValidFrame(valid: Boolean) {
        if (!valid) throw invalidArchive()
    }

    private data class FrameContext(val key: SecretKeySpec, val header: ByteArray, val baseNonce: ByteArray)

    private fun requirePassphrase(passphrase: CharArray) {
        require(passphrase.size >= MIN_PASSPHRASE_CHARS) { "Backup passphrase must have at least 12 characters" }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, workFactor: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, workFactor, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun writeFrame(output: DataOutputStream, context: FrameContext, frameIndex: Int, length: Int, chunk: ByteArray) {
        try {
            val cipher = frameCipher(Cipher.ENCRYPT_MODE, context, frameIndex, length)
            val sealed = if (length == END_FRAME) cipher.doFinal() else cipher.doFinal(chunk, 0, length)
            try {
                output.writeInt(length)
                output.write(sealed)
            } finally {
                sealed.fill(0)
            }
        } catch (error: GeneralSecurityException) {
            throw IOException("Portable backup encryption failed", error)
        }
    }

    private fun openFrame(context: FrameContext, frameIndex: Int, length: Int, sealed: ByteArray): ByteArray = try {
        frameCipher(Cipher.DECRYPT_MODE, context, frameIndex, length).doFinal(sealed)
    } catch (error: GeneralSecurityException) {
        throw invalidArchive(error)
    }

    private fun frameCipher(mode: Int, context: FrameContext, frameIndex: Int, length: Int): Cipher {
        val nonce = context.baseNonce.copyOf()
        for (offset in 0 until Int.SIZE_BYTES) {
            val index = NONCE_BYTES - Int.SIZE_BYTES + offset
            val shift = (Int.SIZE_BYTES - 1 - offset) * Byte.SIZE_BITS
            nonce[index] = (nonce[index].toInt() xor (frameIndex ushr shift)).toByte()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, context.key, GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, nonce))
            updateAAD(context.header)
            updateAAD(ByteBuffer.allocate(Int.SIZE_BYTES * 2).putInt(frameIndex).putInt(length).array())
        }
    }
}

private fun invalidArchive(cause: Throwable? = null): IOException = IOException("Invalid or unauthenticated portable backup", cause)

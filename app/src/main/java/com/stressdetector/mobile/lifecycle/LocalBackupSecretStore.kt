package com.stressdetector.mobile.lifecycle

import android.content.Context
import com.stressdetector.mobile.security.AesGcmFileCipher
import com.stressdetector.mobile.security.KeyManager
import com.stressdetector.mobile.security.PrivacyIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Only an installation-local automation cache. Never copied to SAF or required for recovery. */
internal class LocalBackupSecretStore(context: Context, private val keyManager: KeyManager) {
    private val localSecret = File(context.applicationContext.noBackupFilesDir, "saf-user-recovery-secret.enc")
    val exists: Boolean get() = localSecret.isFile

    fun forget() {
        check(!localSecret.exists() || localSecret.delete()) { "Kunci cadangan lokal tidak dapat dihapus." }
    }

    fun save(passphrase: CharArray) {
        val temporary = File(localSecret.parentFile, ".${localSecret.name}.part")
        val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(temporary).use { output ->
                AesGcmFileCipher.encrypt(
                    keyManager.getOrCreateFileEncryptionKey(PrivacyIdentity.LOCAL_PROFILE_ID),
                    ByteArrayInputStream(bytes),
                    output
                )
                output.fd.sync()
            }
            // Both paths are app-private on the same filesystem; POSIX rename replaces atomically.
            check(temporary.renameTo(localSecret)) { "Kunci cadangan lokal tidak dapat disimpan." }
        } finally {
            bytes.fill(0)
            temporary.delete()
        }
    }

    fun read(): CharArray {
        val output = ByteArrayOutputStream()
        FileInputStream(localSecret).use { input ->
            AesGcmFileCipher.decrypt(
                keyManager.getExistingFileEncryptionKey(PrivacyIdentity.LOCAL_PROFILE_ID),
                input,
                output
            )
        }
        val bytes = output.toByteArray()
        return try {
            bytes.toString(Charsets.UTF_8).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }
}

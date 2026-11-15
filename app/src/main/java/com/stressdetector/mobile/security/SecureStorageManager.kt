package com.stressdetector.mobile.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hardware-backed encrypted storage utilizing Android KeyStore & AES256-GCM (OWASP MASVS-STORAGE).
 * Stores sensitive clinical flags, assessment tokens, and offline security state without plain text exposure.
 */
object SecureStorageManager {
    private const val PREFS_FILE = "secure_clinical_prefs"
    private var encryptedPrefs: SharedPreferences? = null

    fun getEncryptedPreferences(context: Context): SharedPreferences {
        return encryptedPrefs ?: synchronized(this) {
            encryptedPrefs ?: run {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { encryptedPrefs = it }
            }
        }
    }

    fun putSecureString(context: Context, key: String, value: String) {
        getEncryptedPreferences(context).edit().putString(key, value).apply()
    }

    fun getSecureString(context: Context, key: String, default: String? = null): String? {
        return getEncryptedPreferences(context).getString(key, default)
    }

    fun putSecureBoolean(context: Context, key: String, value: Boolean) {
        getEncryptedPreferences(context).edit().putBoolean(key, value).apply()
    }

    fun getSecureBoolean(context: Context, key: String, default: Boolean = false): Boolean {
        return getEncryptedPreferences(context).getBoolean(key, default)
    }
}

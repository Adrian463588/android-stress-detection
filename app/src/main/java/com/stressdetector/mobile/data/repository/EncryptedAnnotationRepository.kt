package com.stressdetector.mobile.data.repository

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stressdetector.mobile.domain.engine.AnnotationRepository
import com.stressdetector.mobile.domain.engine.DomainAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Repository methods return only after encrypted annotation changes reach persistent storage.
@SuppressLint("ApplySharedPref")
class EncryptedAnnotationRepository(context: Context) : AnnotationRepository {

    private val applicationContext = context.applicationContext

    private val masterKey = MasterKey.Builder(applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        applicationContext,
        "encrypted_annotations",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val gson = Gson()
    private val typeToken = object : TypeToken<List<DomainAnnotation>>() {}.type

    override suspend fun getAnnotationsForSession(sessionId: String): List<DomainAnnotation> = withContext(Dispatchers.IO) {
        val json = sharedPreferences.getString(sessionId, null)
        if (json != null) {
            gson.fromJson(json, typeToken)
        } else {
            emptyList()
        }
    }

    override suspend fun addAnnotation(annotation: DomainAnnotation) = withContext(Dispatchers.IO) {
        val currentAnnotations = getAnnotationsForSession(annotation.sessionId).toMutableList()

        // Remove if it already exists to avoid duplicates
        currentAnnotations.removeAll { it.id == annotation.id }
        currentAnnotations.add(annotation)

        val json = gson.toJson(currentAnnotations)
        sharedPreferences.edit().putString(annotation.sessionId, json).commit()
        Unit
    }

    override suspend fun deleteAnnotation(annotationId: String) = withContext(Dispatchers.IO) {
        // Since we key by sessionId, we have to search all keys to find and delete the annotation by id.
        val editor = sharedPreferences.edit()
        var modified = false
        for ((sessionId, json) in sharedPreferences.all) {
            if (json is String) {
                val annotations: MutableList<DomainAnnotation> = gson.fromJson(json, typeToken)
                val removed = annotations.removeAll { it.id == annotationId }
                if (removed) {
                    if (annotations.isEmpty()) {
                        editor.remove(sessionId)
                    } else {
                        editor.putString(sessionId, gson.toJson(annotations))
                    }
                    modified = true
                }
            }
        }
        if (modified) {
            editor.commit()
        }
        Unit
    }

    override suspend fun deleteAnnotationsForSession(sessionId: String) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().remove(sessionId).commit()
        Unit
    }
}

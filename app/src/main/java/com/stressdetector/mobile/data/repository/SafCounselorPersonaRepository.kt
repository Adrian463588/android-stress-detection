package com.stressdetector.mobile.data.repository

import android.content.Context
import com.google.gson.Gson
import com.stressdetector.mobile.domain.counselor.CounselorPersona
import com.stressdetector.mobile.domain.counselor.CounselorPersonaProfile
import com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository
import com.stressdetector.mobile.domain.counselor.CounselorPersonaStorageStatus
import com.stressdetector.mobile.domain.counselor.CounselorPersonaValidator
import com.stressdetector.mobile.domain.counselor.CounselorTone
import com.stressdetector.mobile.domain.counselor.LanguageMode
import com.stressdetector.mobile.domain.counselor.PersonaValidationResult
import com.stressdetector.mobile.domain.counselor.ResponseLength
import com.stressdetector.mobile.ml.storage.ModelStorageManager
import com.stressdetector.mobile.ml.storage.ModelStorageSlot
import com.stressdetector.mobile.ml.storage.SafGenerationPayload
import com.stressdetector.mobile.ml.storage.SafStorageStatus
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SAF is the canonical store for the structured style profile. Room is kept as
 * a fast encrypted-app cache and never gets presented as reinstall-safe storage.
 */
class SafCounselorPersonaRepository(
    context: Context,
    private val storage: ModelStorageManager,
    private val cache: RoomCounselorPersonaRepository,
    private val gson: Gson = Gson()
) : CounselorPersonaRepository {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val persona = MutableStateFlow(defaultPersona())
    private val storageStatus = MutableStateFlow(CounselorPersonaStorageStatus.SAF_NOT_CONNECTED)

    init {
        scope.launch {
            storage.state
                .map { state ->
                    Triple(state.treeUri, state.connected, state.status)
                }
                .distinctUntilChanged()
                .collect { refreshFromCanonicalStore() }
        }
    }

    override fun observe(): Flow<CounselorPersona> = persona.asStateFlow()

    override fun observeStorageStatus(): StateFlow<CounselorPersonaStorageStatus> = storageStatus.asStateFlow()

    override suspend fun get(): CounselorPersona {
        refreshFromCanonicalStore()
        return persona.value
    }

    override suspend fun save(profile: CounselorPersonaProfile): Result<CounselorPersona> = mutex.withLock {
        runCatching {
            require(storage.state.value.connected) {
                "Folder SAF belum terhubung. Hubungkan folder dari Profile sebelum menyimpan Persona permanen."
            }
            val cached = cache.get()
            val nextVersion = maxOf(persona.value.profile.version, cached.profile.version) + 1L
            val validated = when (
                val validation = CounselorPersonaValidator.validate(
                    profile.copy(version = nextVersion, profileHash = "")
                )
            ) {
                is PersonaValidationResult.Valid -> validation.profile
                is PersonaValidationResult.Invalid -> error(validation.reason)
            }
            val saved = CounselorPersona(
                profile = validated,
                updatedAtMillis = System.currentTimeMillis()
            )
            publishToSaf(saved)
            cache.mirror(saved)
            persona.value = saved
            storageStatus.value = CounselorPersonaStorageStatus.DURABLE_SAF
            saved
        }.onFailure { error ->
            storageStatus.value = statusForFailure(error)
        }
    }

    override suspend fun reset(): Result<CounselorPersona> = save(CounselorPersonaProfile())

    private suspend fun refreshFromCanonicalStore() {
        mutex.withLock {
            val state = storage.state.value
            if (!state.connected) {
                persona.value = cache.get()
                storageStatus.value = if (state.status == SafStorageStatus.PERMISSION_LOST) {
                    CounselorPersonaStorageStatus.PERMISSION_LOST
                } else {
                    CounselorPersonaStorageStatus.SAF_NOT_CONNECTED
                }
                return
            }

            val raw = storage.readText(ModelStorageSlot.PERSONA_AI, PERSONA_FILE_NAME)
            if (raw.isNullOrBlank()) {
                persona.value = cache.get()
                storageStatus.value = CounselorPersonaStorageStatus.SAF_CONNECTED_NO_PERSONA
                return
            }

            runCatching { parse(raw) }
                .onSuccess { canonical ->
                    persona.value = canonical
                    cache.mirror(canonical)
                    storageStatus.value = CounselorPersonaStorageStatus.DURABLE_SAF
                }
                .onFailure {
                    persona.value = cache.get()
                    storageStatus.value = CounselorPersonaStorageStatus.CORRUPT_SAF_FALLBACK
                }
        }
    }

    private fun parse(raw: String): CounselorPersona {
        val payload = gson.fromJson(raw, SafPersonaPayload::class.java)
            ?: error("Payload Persona SAF kosong.")
        require(payload.schemaVersion == CounselorPersonaProfile.PROFILE_SCHEMA_VERSION) {
            "Schema Persona SAF tidak didukung: ${payload.schemaVersion}"
        }
        val candidate = payload.toProfile()
        val validation = CounselorPersonaValidator.validate(candidate)
        val valid = when (validation) {
            is PersonaValidationResult.Valid -> validation.profile
            is PersonaValidationResult.Invalid -> error(validation.reason)
        }
        require(valid.profileHash == candidate.profileHash) {
            "Checksum logis Persona SAF tidak cocok."
        }
        return CounselorPersona(valid, payload.updatedAtMillis)
    }

    private suspend fun publishToSaf(value: CounselorPersona) {
        val payloadFile = File(appContext.cacheDir, ".persona-ai.json.part")
        payloadFile.parentFile?.mkdirs()
        try {
            payloadFile.writeText(gson.toJson(SafPersonaPayload.from(value)), Charsets.UTF_8)
            val checksum = sha256(payloadFile)
            val generation = "persona-${value.profile.version}-${value.profile.profileHash.take(HASH_DISPLAY_LENGTH)}"
            storage.publishGeneration(
                slot = ModelStorageSlot.PERSONA_AI,
                generation = generation,
                version = CounselorPersonaProfile.PROFILE_SCHEMA_VERSION,
                revision = value.profile.profileHash,
                payloads = listOf(
                    SafGenerationPayload(
                        source = payloadFile,
                        fileName = PERSONA_FILE_NAME,
                        expectedSizeBytes = payloadFile.length(),
                        expectedSha256 = checksum,
                        mediaType = "application/json"
                    )
                )
            ).getOrThrow()
        } finally {
            payloadFile.delete()
        }
    }

    private fun statusForFailure(error: Throwable): CounselorPersonaStorageStatus = when {
        error is SecurityException -> CounselorPersonaStorageStatus.PERMISSION_LOST
        storage.state.value.status == SafStorageStatus.PERMISSION_LOST ->
            CounselorPersonaStorageStatus.PERMISSION_LOST
        else -> storageStatus.value
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun defaultPersona(): CounselorPersona = CounselorPersona(
        CounselorPersonaProfile().withIntegrityHash(),
        updatedAtMillis = 0L
    )

    private companion object {
        const val HASH_DISPLAY_LENGTH = 12
        const val PERSONA_FILE_NAME = "persona.json"
    }
}

private data class SafPersonaPayload(
    val schemaVersion: String = CounselorPersonaProfile.PROFILE_SCHEMA_VERSION,
    val version: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val language: String = LanguageMode.AUTO.name,
    val tone: String = CounselorTone.WARM.name,
    val responseLength: String = ResponseLength.BALANCED.name,
    val reflectiveQuestions: Int = 1,
    val focusNote: String? = null,
    val profileHash: String = ""
) {
    fun toProfile(): CounselorPersonaProfile = CounselorPersonaProfile(
        language = enumValueOf(language),
        tone = enumValueOf(tone),
        responseLength = enumValueOf(responseLength),
        reflectiveQuestions = reflectiveQuestions,
        focusNote = focusNote,
        version = version,
        profileHash = profileHash
    )

    companion object {
        fun from(persona: CounselorPersona): SafPersonaPayload = SafPersonaPayload(
            version = persona.profile.version,
            updatedAtMillis = persona.updatedAtMillis,
            language = persona.profile.language.name,
            tone = persona.profile.tone.name,
            responseLength = persona.profile.responseLength.name,
            reflectiveQuestions = persona.profile.reflectiveQuestions,
            focusNote = persona.profile.focusNote,
            profileHash = persona.profile.profileHash
        )
    }
}

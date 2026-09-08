package com.stressdetector.mobile.data.repository

import com.google.gson.Gson
import com.stressdetector.mobile.data.db.CounselorPersonaDao
import com.stressdetector.mobile.data.db.CounselorPersonaEntity
import com.stressdetector.mobile.domain.counselor.CounselorPersona
import com.stressdetector.mobile.domain.counselor.CounselorPersonaProfile
import com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository
import com.stressdetector.mobile.domain.counselor.CounselorPersonaValidator
import com.stressdetector.mobile.domain.counselor.CounselorTone
import com.stressdetector.mobile.domain.counselor.LanguageMode
import com.stressdetector.mobile.domain.counselor.PersonaValidationResult
import com.stressdetector.mobile.domain.counselor.ResponseLength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Stores a versioned JSON payload in the existing column; no destructive Room migration is required. */
class RoomCounselorPersonaRepository(private val dao: CounselorPersonaDao, private val gson: Gson = Gson()) : CounselorPersonaRepository {
    override fun observe(): Flow<CounselorPersona> = dao.observe(CounselorPersonaRepository.DEFAULT_PROFILE_ID)
        .map { it?.toDomain() ?: emptyPersona() }

    override suspend fun get(): CounselorPersona = dao.get(CounselorPersonaRepository.DEFAULT_PROFILE_ID)
        ?.toDomain()
        ?: emptyPersona()

    override suspend fun save(profile: CounselorPersonaProfile): Result<CounselorPersona> = runCatching {
        val previous = dao.get(CounselorPersonaRepository.DEFAULT_PROFILE_ID)
        val nextVersion = (previous?.version ?: 0) + 1
        val candidate = profile.copy(version = nextVersion.toLong(), profileHash = "")
        val valid = when (val validation = CounselorPersonaValidator.validate(candidate)) {
            is PersonaValidationResult.Valid -> validation.profile
            is PersonaValidationResult.Invalid -> throw IllegalArgumentException(validation.reason)
        }
        val now = System.currentTimeMillis()
        val payload = StoredPersonaPayload.from(valid)
        val saved = CounselorPersonaEntity(
            profileId = CounselorPersonaRepository.DEFAULT_PROFILE_ID,
            markdown = gson.toJson(payload),
            version = nextVersion,
            updatedAtMillis = now
        )
        dao.upsert(saved)
        CounselorPersona(profile = valid, updatedAtMillis = now)
    }

    override suspend fun reset(): Result<CounselorPersona> = save(CounselorPersonaProfile())

    /** Mirrors a validated SAF value without changing its canonical version. */
    internal suspend fun mirror(persona: CounselorPersona) {
        dao.upsert(
            CounselorPersonaEntity(
                profileId = CounselorPersonaRepository.DEFAULT_PROFILE_ID,
                markdown = gson.toJson(StoredPersonaPayload.from(persona.profile)),
                version = persona.profile.version.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                updatedAtMillis = persona.updatedAtMillis
            )
        )
    }

    private fun CounselorPersonaEntity.toDomain(): CounselorPersona {
        val validation = if (markdown.trimStart().startsWith("{")) {
            runCatching { gson.fromJson(markdown, StoredPersonaPayload::class.java).toProfile(version.toLong()) }
                .getOrNull()
                ?.let(::validateStoredProfile)
        } else {
            validateLegacyFocusNote(markdown, version.toLong())
        }
        val safeProfile = when (validation) {
            is PersonaValidationResult.Valid -> validation.profile
            else -> CounselorPersonaProfile(version = version.toLong()).withIntegrityHash()
        }
        return CounselorPersona(profile = safeProfile, updatedAtMillis = updatedAtMillis)
    }

    private fun validateStoredProfile(profile: CounselorPersonaProfile): PersonaValidationResult {
        if (profile.profileHash.isBlank() || profile.profileHash != profile.calculateHash()) {
            return PersonaValidationResult.Invalid("Stored counselor persona integrity hash mismatch.")
        }
        return CounselorPersonaValidator.validate(profile)
    }

    /**
     * Legacy rows stored plain text in the historical column. Treat that text
     * only as an untrusted focus note: normalize and validate it before use,
     * then expose the structured profile without rewriting the row on read.
     */
    private fun validateLegacyFocusNote(focusNote: String, version: Long): PersonaValidationResult = CounselorPersonaValidator.validate(
        CounselorPersonaProfile(focusNote = focusNote, version = version)
    )

    private fun emptyPersona() = CounselorPersona(
        profile = CounselorPersonaProfile().withIntegrityHash(),
        updatedAtMillis = 0L
    )
}

private data class StoredPersonaPayload(
    val schemaVersion: String = CounselorPersonaProfile.PROFILE_SCHEMA_VERSION,
    val language: String = LanguageMode.AUTO.name,
    val tone: String = CounselorTone.WARM.name,
    val responseLength: String = ResponseLength.BALANCED.name,
    val reflectiveQuestions: Int = 1,
    val focusNote: String? = null,
    val profileHash: String = ""
) {
    fun toProfile(version: Long): CounselorPersonaProfile {
        require(schemaVersion == CounselorPersonaProfile.PROFILE_SCHEMA_VERSION) {
            "Unsupported counselor persona schema: $schemaVersion"
        }
        return CounselorPersonaProfile(
            language = enumValueOf(language),
            tone = enumValueOf(tone),
            responseLength = enumValueOf(responseLength),
            reflectiveQuestions = reflectiveQuestions,
            focusNote = focusNote,
            version = version,
            profileHash = profileHash
        )
    }

    companion object {
        fun from(profile: CounselorPersonaProfile) = StoredPersonaPayload(
            language = profile.language.name,
            tone = profile.tone.name,
            responseLength = profile.responseLength.name,
            reflectiveQuestions = profile.reflectiveQuestions,
            focusNote = profile.focusNote,
            profileHash = profile.profileHash
        )
    }
}

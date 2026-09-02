package com.stressdetector.mobile.domain.counselor

import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class LanguageMode { AUTO, INDONESIAN, ENGLISH }

enum class CounselorTone { WARM, CALM, DIRECT, NEUTRAL }

enum class ResponseLength { BRIEF, BALANCED, DETAILED }

/**
 * Style-only counselor preferences. Safety, evidence and counselor scope are
 * deliberately absent from this model so a stored persona cannot override them.
 */
data class CounselorPersonaProfile(
    val language: LanguageMode = LanguageMode.AUTO,
    val tone: CounselorTone = CounselorTone.WARM,
    val responseLength: ResponseLength = ResponseLength.BALANCED,
    val reflectiveQuestions: Int = 1,
    val focusNote: String? = null,
    val version: Long = 0,
    val profileHash: String = ""
) {
    val isCustomized: Boolean
        get() = language != LanguageMode.AUTO ||
            tone != CounselorTone.WARM ||
            responseLength != ResponseLength.BALANCED ||
            reflectiveQuestions != 1 ||
            !focusNote.isNullOrBlank()

    fun withIntegrityHash(): CounselorPersonaProfile = copy(profileHash = calculateHash())

    fun calculateHash(): String {
        val canonical = listOf(
            PROFILE_SCHEMA_VERSION,
            language.name,
            tone.name,
            responseLength.name,
            reflectiveQuestions.toString(),
            focusNote.orEmpty(),
            version.toString()
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val PROFILE_SCHEMA_VERSION = "counselor_persona_v2"
    }
}

sealed interface PersonaValidationResult {
    data class Valid(val profile: CounselorPersonaProfile) : PersonaValidationResult
    data class Invalid(val reason: String) : PersonaValidationResult
}

/** Fail-closed validation for the only free-text persona field. */
object CounselorPersonaValidator {
    fun validate(candidate: CounselorPersonaProfile): PersonaValidationResult {
        if (candidate.reflectiveQuestions !in 0..2) {
            return PersonaValidationResult.Invalid("Jumlah pertanyaan reflektif harus antara 0 dan 2.")
        }
        val rawNote = candidate.focusNote.orEmpty()
        val normalized = Normalizer.normalize(rawNote, Normalizer.Form.NFKC)
            .map { character -> if (character.isWhitespace()) ' ' else character }
            .joinToString("")
            .replace(Regex(" {2,}"), " ")
            .trim()
        if (normalized.wordCount() > CounselorPersonaRepository.MAX_FOCUS_NOTE_WORDS) {
            return PersonaValidationResult.Invalid(
                "Catatan fokus maksimal ${CounselorPersonaRepository.MAX_FOCUS_NOTE_WORDS} kata."
            )
        }
        val forbidden = FORBIDDEN_FOCUS_PATTERNS.firstOrNull { it.containsMatchIn(normalized) }
        if (forbidden != null) {
            return PersonaValidationResult.Invalid(
                "Catatan fokus hanya boleh mengatur gaya; instruksi sistem, citation, diagnosis, obat, atau bypass keselamatan ditolak."
            )
        }
        val normalizedProfile = candidate.copy(focusNote = normalized.ifBlank { null }, profileHash = "")
        return PersonaValidationResult.Valid(normalizedProfile.withIntegrityHash())
    }
}

private val FORBIDDEN_FOCUS_PATTERNS = listOf(
    Regex("(?i)(?:^|\\s)(system|developer|assistant|user)\\s*[:=]"),
    Regex("(?i)\\b(ignore|abaikan|bypass|jailbreak|override)\\b.{0,40}\\b(instruction|prompt|aturan|safety|krisis)\\b"),
    Regex("(?i)\\[(?:S|W)\\d+(?:\\|[^]]+)?]"),
    Regex("(?i)\\b(diagnos(?:is|e)|mendiagnosis|diagnosa|resep|dosis|medication|obat)\\b"),
    Regex("(?i)\\b(crisis|krisis|bunuh diri|self-harm)\\b.{0,40}\\b(ignore|abaikan|bypass|lewati)\\b"),
    Regex("(?i)```|<\\s*(?:system|assistant|developer)\\b")
)

/** Persisted persona plus storage timestamp. */
data class CounselorPersona(val profile: CounselorPersonaProfile, val updatedAtMillis: Long) {
    val isCustomized: Boolean get() = profile.isCustomized
    val version: Long get() = profile.version
    val profileHash: String get() = profile.profileHash
}

enum class CounselorPersonaStorageStatus {
    DURABLE_SAF,
    SAF_CONNECTED_NO_PERSONA,
    SAF_NOT_CONNECTED,
    PERMISSION_LOST,
    CORRUPT_SAF_FALLBACK,
    LOCAL_CACHE
}

interface CounselorPersonaRepository {
    fun observe(): Flow<CounselorPersona>

    /** Storage provenance is surfaced so the UI never overclaims durability. */
    fun observeStorageStatus(): Flow<CounselorPersonaStorageStatus> = flowOf(CounselorPersonaStorageStatus.LOCAL_CACHE)

    suspend fun get(): CounselorPersona
    suspend fun save(profile: CounselorPersonaProfile): Result<CounselorPersona>

    suspend fun reset(): Result<CounselorPersona>

    data object Empty : CounselorPersonaRepository {
        private val emptyProfile = CounselorPersonaProfile().withIntegrityHash()
        private val empty = CounselorPersona(profile = emptyProfile, updatedAtMillis = 0L)

        override fun observe(): Flow<CounselorPersona> = flowOf(empty)
        override suspend fun get(): CounselorPersona = empty
        override suspend fun save(profile: CounselorPersonaProfile): Result<CounselorPersona> =
            when (val validation = CounselorPersonaValidator.validate(profile.copy(version = 0, profileHash = ""))) {
                is PersonaValidationResult.Valid -> Result.success(
                    CounselorPersona(validation.profile, updatedAtMillis = 0L)
                )
                is PersonaValidationResult.Invalid -> Result.failure(IllegalArgumentException(validation.reason))
            }

        override suspend fun reset(): Result<CounselorPersona> = Result.success(empty)
    }

    companion object {
        const val MAX_FOCUS_NOTE_WORDS = 1_500
        const val DEFAULT_PROFILE_ID = "default"
    }
}

private fun String.wordCount(): Int = trim()
    .split(Regex("\\s+"))
    .count(String::isNotBlank)

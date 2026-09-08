package com.stressdetector.mobile.data.repository

import com.stressdetector.mobile.data.db.CounselorPersonaDao
import com.stressdetector.mobile.data.db.CounselorPersonaEntity
import com.stressdetector.mobile.domain.counselor.CounselorPersonaProfile
import com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository
import com.stressdetector.mobile.domain.counselor.CounselorTone
import com.stressdetector.mobile.domain.counselor.LanguageMode
import com.stressdetector.mobile.domain.counselor.ResponseLength
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomCounselorPersonaRepositoryTest {
    @Test
    fun structuredProfileNormalizesFocusNoteAndResetClearsIt() = runTest {
        val dao = FakePersonaDao()
        val repository = RoomCounselorPersonaRepository(dao)

        val saved = repository.save(
            CounselorPersonaProfile(
                language = LanguageMode.INDONESIAN,
                tone = CounselorTone.CALM,
                responseLength = ResponseLength.BRIEF,
                reflectiveQuestions = 2,
                focusNote = "  Fokus\n konseling  "
            )
        ).getOrThrow()

        assertEquals("Fokus konseling", saved.profile.focusNote)
        assertEquals(LanguageMode.INDONESIAN, saved.profile.language)
        assertEquals(CounselorTone.CALM, saved.profile.tone)
        assertEquals(ResponseLength.BRIEF, saved.profile.responseLength)
        assertEquals(2, saved.profile.reflectiveQuestions)
        assertEquals(1, saved.version)
        assertEquals("Fokus konseling", repository.observe().first().profile.focusNote)

        val reset = repository.reset().getOrThrow()
        assertEquals(null, reset.profile.focusNote)
        assertEquals(2, reset.version)
    }

    @Test
    fun unicodeCompatibilityFormsAndWhitespaceAreNormalizedBeforePersistence() = runTest {
        val repository = RoomCounselorPersonaRepository(FakePersonaDao())

        val saved = repository.save(
            CounselorPersonaProfile(focusNote = "Ｆｏｃｕｓ\u00A0\n\t tenang")
        ).getOrThrow()

        assertEquals("Focus tenang", saved.profile.focusNote)
    }

    @Test
    fun overlongFocusNoteIsRejectedBeforePersistence() = runTest {
        val dao = FakePersonaDao()
        val repository = RoomCounselorPersonaRepository(dao)
        val saved = repository.save(
            CounselorPersonaProfile(
                focusNote = List(CounselorPersonaRepository.MAX_FOCUS_NOTE_WORDS + 1) { "x" }.joinToString(" ")
            )
        )

        assertTrue(saved.isFailure)
        assertEquals(null, dao.get(CounselorPersonaRepository.DEFAULT_PROFILE_ID))
    }

    @Test
    fun tamperedStoredPersonaHashFallsBackToSafeDefault() = runTest {
        val dao = FakePersonaDao()
        val repository = RoomCounselorPersonaRepository(dao)
        repository.save(CounselorPersonaProfile(focusNote = "Bahasa hangat")).getOrThrow()
        val stored = requireNotNull(dao.get(CounselorPersonaRepository.DEFAULT_PROFILE_ID))
        dao.seed(stored.copy(markdown = stored.markdown.replace("profileHash", "profileHashTampered")))

        val loaded = repository.get()

        assertEquals(null, loaded.profile.focusNote)
        assertTrue(loaded.profileHash.isNotBlank())
    }

    @Test
    fun legacyPlainTextRowMigratesIntoValidatedFocusNote() = runTest {
        val dao = FakePersonaDao()
        dao.seed(
            CounselorPersonaEntity(
                profileId = CounselorPersonaRepository.DEFAULT_PROFILE_ID,
                markdown = "  Fokus\n coping aman  ",
                version = 3,
                updatedAtMillis = 10L
            )
        )
        val repository = RoomCounselorPersonaRepository(dao)

        val loaded = repository.get()

        assertEquals("Fokus coping aman", loaded.profile.focusNote)
        assertEquals(3L, loaded.version)
        assertTrue(loaded.profileHash.isNotBlank())
    }

    private class FakePersonaDao : CounselorPersonaDao {
        private val state = MutableStateFlow<CounselorPersonaEntity?>(null)

        override fun observe(profileId: String): Flow<CounselorPersonaEntity?> = state

        override suspend fun get(profileId: String): CounselorPersonaEntity? = state.value

        override suspend fun upsert(persona: CounselorPersonaEntity) {
            state.value = persona
        }

        fun seed(persona: CounselorPersonaEntity) {
            state.value = persona
        }
    }
}

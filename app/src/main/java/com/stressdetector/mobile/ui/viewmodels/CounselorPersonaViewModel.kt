package com.stressdetector.mobile.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.counselor.CounselorPersona
import com.stressdetector.mobile.domain.counselor.CounselorPersonaProfile
import com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository
import com.stressdetector.mobile.domain.counselor.CounselorPersonaStorageStatus
import com.stressdetector.mobile.domain.counselor.CounselorTone
import com.stressdetector.mobile.domain.counselor.LanguageMode
import com.stressdetector.mobile.domain.counselor.ResponseLength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CounselorPersonaUiState(
    val language: LanguageMode = LanguageMode.AUTO,
    val tone: CounselorTone = CounselorTone.WARM,
    val responseLength: ResponseLength = ResponseLength.BALANCED,
    val reflectiveQuestions: Int = 1,
    val focusNote: String = "",
    val version: Long = 0,
    val updatedAtMillis: Long = 0L,
    val storageStatus: CounselorPersonaStorageStatus = CounselorPersonaStorageStatus.SAF_NOT_CONNECTED,
    val isSaving: Boolean = false,
    val statusMessage: String? = null
)

class CounselorPersonaViewModel(private val repository: CounselorPersonaRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(CounselorPersonaUiState())
    val uiState: StateFlow<CounselorPersonaUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe().collect { persona ->
                _uiState.update { current ->
                    current.withPersona(persona)
                }
            }
        }
        viewModelScope.launch {
            repository.observeStorageStatus().collect { status ->
                _uiState.update { it.copy(storageStatus = status) }
            }
        }
    }

    fun updateLanguage(value: LanguageMode) = updateProfile { copy(language = value) }

    fun updateTone(value: CounselorTone) = updateProfile { copy(tone = value) }

    fun updateResponseLength(value: ResponseLength) = updateProfile { copy(responseLength = value) }

    fun updateReflectiveQuestions(value: Int) = updateProfile {
        copy(reflectiveQuestions = value.coerceIn(MIN_REFLECTIVE_QUESTIONS, MAX_REFLECTIVE_QUESTIONS))
    }

    fun updateFocusNote(value: String) = updateProfile { copy(focusNote = value) }

    fun save() {
        if (_uiState.value.isSaving) return
        val profile = _uiState.value.toProfile()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isSaving = true, statusMessage = null) }
            repository.save(profile)
                .onSuccess { persona ->
                    _uiState.update {
                        it.withPersona(persona).copy(
                            isSaving = false,
                            statusMessage = "Persona AI Counselor tersimpan permanen di folder SAF."
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            statusMessage = error.message ?: "Persona tidak dapat disimpan."
                        )
                    }
                }
        }
    }

    fun reset() {
        if (_uiState.value.isSaving) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isSaving = true, statusMessage = null) }
            repository.reset()
                .onSuccess { persona ->
                    _uiState.update {
                        it.withPersona(persona).copy(
                            isSaving = false,
                            statusMessage = "Persona dikembalikan ke guardrail bawaan."
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            statusMessage = error.message ?: "Persona tidak dapat direset."
                        )
                    }
                }
        }
    }

    class Factory(private val context: android.content.Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CounselorPersonaViewModel(
            AppLocator.counselorPersonaRepository(context)
        ) as T
    }

    private fun updateProfile(transform: CounselorPersonaUiState.() -> CounselorPersonaUiState) {
        _uiState.update { state -> transform(state).copy(statusMessage = null) }
    }

    private fun CounselorPersonaUiState.withPersona(persona: CounselorPersona): CounselorPersonaUiState = copy(
        language = persona.profile.language,
        tone = persona.profile.tone,
        responseLength = persona.profile.responseLength,
        reflectiveQuestions = persona.profile.reflectiveQuestions,
        focusNote = persona.profile.focusNote.orEmpty(),
        version = persona.version,
        updatedAtMillis = persona.updatedAtMillis
    )

    private fun CounselorPersonaUiState.toProfile(): CounselorPersonaProfile = CounselorPersonaProfile(
        language = language,
        tone = tone,
        responseLength = responseLength,
        reflectiveQuestions = reflectiveQuestions,
        focusNote = focusNote
    )

    private companion object {
        const val MIN_REFLECTIVE_QUESTIONS = 0
        const val MAX_REFLECTIVE_QUESTIONS = 2
    }
}

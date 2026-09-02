package com.stressdetector.mobile.domain.counselor

/** Persists the chat selected by the user across ViewModel recreation. */
interface CounselorSessionSelectionStore {
    fun read(): String?
    fun save(sessionId: String)
    fun clear()

    data object None : CounselorSessionSelectionStore {
        override fun read(): String? = null
        override fun save(sessionId: String) = Unit
        override fun clear() = Unit
    }
}

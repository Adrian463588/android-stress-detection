package com.stressdetector.mobile.domain.search

import kotlinx.coroutines.flow.StateFlow

interface WebConsentStore {
    val granted: StateFlow<Boolean>
    fun setGranted(granted: Boolean)

    /** Separate opt-in: old one-query consent must never enable automatic networking. */
    val automaticAllowed: StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun setAutomaticAllowed(allowed: Boolean) = Unit

    data object Unavailable : WebConsentStore {
        override val granted: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
        override fun setGranted(granted: Boolean) = Unit
    }
}

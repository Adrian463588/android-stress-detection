package com.stressdetector.mobile.domain.search

import kotlinx.coroutines.flow.StateFlow

interface WebConsentStore {
    val granted: StateFlow<Boolean>
    fun setGranted(granted: Boolean)

    data object Unavailable : WebConsentStore {
        override val granted: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
        override fun setGranted(granted: Boolean) = Unit
    }
}

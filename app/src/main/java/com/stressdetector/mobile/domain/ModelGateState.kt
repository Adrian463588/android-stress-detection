package com.stressdetector.mobile.domain

enum class ModelGateStatus {
    CHECKING,
    READY,
    UNAVAILABLE
}

data class ModelGateState(val status: ModelGateStatus = ModelGateStatus.CHECKING, val reason: String? = null) {
    val isReady: Boolean
        get() = status == ModelGateStatus.READY
}

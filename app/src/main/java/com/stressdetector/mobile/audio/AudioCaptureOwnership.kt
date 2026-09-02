package com.stressdetector.mobile.audio

import kotlinx.coroutines.sync.Mutex

enum class AudioCaptureOwner {
    AI_VOICE,
    VERBATIM
}

/** Process-wide ownership gate: only one AudioRecord may be active. */
class AudioCaptureOwnership {
    private val mutex = Mutex()

    @Volatile private var owner: AudioCaptureOwner? = null

    fun tryAcquire(requestedBy: AudioCaptureOwner): AudioCaptureLease? {
        if (!mutex.tryLock()) return null
        owner = requestedBy
        return AudioCaptureLease {
            if (owner == requestedBy) owner = null
            mutex.unlock()
        }
    }

    fun activeOwner(): AudioCaptureOwner? = owner
}

class AudioCaptureLease internal constructor(private val releaseAction: () -> Unit) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        releaseAction()
    }
}

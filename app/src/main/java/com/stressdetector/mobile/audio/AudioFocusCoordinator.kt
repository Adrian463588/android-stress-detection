package com.stressdetector.mobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Coordinates local speech/playback focus without introducing network audio. */
class AudioFocusCoordinator(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    @Volatile private var recordingFocusActive = false

    @Volatile private var playbackFocusGranted = true
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            playbackFocusGranted = false
        }
    }
    private val playbackFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
    private var recordingFocusRequest: AudioFocusRequest? = null

    fun requestPlaybackFocus(): Boolean {
        if (recordingFocusActive) return false
        val granted = audioManager.requestAudioFocus(playbackFocusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        playbackFocusGranted = granted
        return granted
    }

    fun hasPlaybackFocus(): Boolean = !recordingFocusActive && playbackFocusGranted

    fun requestRecordingFocus(): Boolean {
        recordingFocusActive = true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        recordingFocusRequest = request
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            recordingFocusActive = false
            recordingFocusRequest = null
        }
        return granted
    }

    fun abandonRecordingFocus() {
        recordingFocusActive = false
        playbackFocusGranted = false
        recordingFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        recordingFocusRequest = null
    }

    fun abandonPlaybackFocus() {
        playbackFocusGranted = false
        audioManager.abandonAudioFocusRequest(playbackFocusRequest)
    }
}

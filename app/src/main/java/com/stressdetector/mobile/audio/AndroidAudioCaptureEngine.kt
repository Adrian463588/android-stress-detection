package com.stressdetector.mobile.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.stressdetector.mobile.R
import com.stressdetector.mobile.domain.engine.AudioCaptureEngine
import java.io.File

class AndroidAudioCaptureEngine(private val context: Context) : AudioCaptureEngine {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingState = false
    private var currentOutputFile: File? = null
    private var startTimeMillis: Long = 0L

    override fun isRecording(): Boolean = isRecordingState

    override fun startRecording(outputFile: File, userConsentGranted: Boolean, onAudioLevel: (Float) -> Unit): Boolean = try {
        currentOutputFile = outputFile

        // Setup Notification
        val channelId = "recording_channel"
        val channel = NotificationChannel(
            channelId,
            "Session Recording",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, channelId)
            // Use a default system icon if R.drawable.ic_launcher is missing, but typically we have one.
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Clinical Session Active")
            .setContentText("Recording session audio...")
            .setOngoing(true)
            .build()

        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(1001, notification)
            }
        } catch (e: SecurityException) {
            Log.w("AudioCaptureEngine", "Missing POST_NOTIFICATIONS permission", e)
        }

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioSamplingRate(16000)
        recorder.setAudioEncodingBitRate(32000)
        recorder.setOutputFile(outputFile.absolutePath)

        recorder.prepare()
        recorder.start()

        mediaRecorder = recorder
        isRecordingState = true
        startTimeMillis = System.currentTimeMillis()
        Log.d("AudioCaptureEngine", "Started recording to ${outputFile.absolutePath}")
        true
    } catch (e: SecurityException) {
        Log.e("AudioCaptureEngine", "SecurityException: Missing RECORD_AUDIO permission", e)
        isRecordingState = false
        false
    } catch (e: Exception) {
        Log.e("AudioCaptureEngine", "Failed to start recording", e)
        isRecordingState = false
        false
    }

    override fun pauseRecording(): Boolean {
        if (!isRecordingState || mediaRecorder == null) return false
        return try {
            mediaRecorder?.pause()
            Log.d("AudioCaptureEngine", "Paused recording")
            true
        } catch (e: Exception) {
            Log.e("AudioCaptureEngine", "Failed to pause recording", e)
            false
        }
    }

    override fun resumeRecording(): Boolean {
        if (!isRecordingState || mediaRecorder == null) return false
        return try {
            mediaRecorder?.resume()
            Log.d("AudioCaptureEngine", "Resumed recording")
            true
        } catch (e: Exception) {
            Log.e("AudioCaptureEngine", "Failed to resume recording", e)
            false
        }
    }

    override fun stopRecording(): File? {
        if (!isRecordingState || mediaRecorder == null) return null
        return try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecordingState = false

            try {
                NotificationManagerCompat.from(context).cancel(1001)
            } catch (e: Exception) {
                Log.w("AudioCaptureEngine", "Failed to cancel notification", e)
            }

            Log.d("AudioCaptureEngine", "Stopped recording, file size: ${currentOutputFile?.length()} bytes")
            currentOutputFile
        } catch (e: Exception) {
            Log.e("AudioCaptureEngine", "Failed to stop recording cleanly", e)
            mediaRecorder?.release()
            mediaRecorder = null
            isRecordingState = false
            try {
                NotificationManagerCompat.from(context).cancel(1001)
            } catch (ex: Exception) { }
            currentOutputFile
        }
    }

    override fun getDurationMillis(): Long {
        if (!isRecordingState) return 0L
        return System.currentTimeMillis() - startTimeMillis
    }
}

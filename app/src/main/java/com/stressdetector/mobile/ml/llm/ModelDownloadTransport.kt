package com.stressdetector.mobile.ml.llm

import android.net.Uri
import java.io.File

enum class DownloadTransportStatus {
    Pending,
    Running,
    Paused,
    Successful,
    Failed
}

data class DownloadSnapshot(
    val id: Long,
    val status: DownloadTransportStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val reason: Int? = null,
    val localUri: Uri? = null
)

interface ModelDownloadTransport {
    fun enqueue(destination: File): Long
    fun query(downloadId: Long): DownloadSnapshot?
    fun remove(downloadId: Long)
}

package com.stressdetector.mobile.ml.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

class AndroidDownloadManagerTransport(context: Context) : ModelDownloadTransport {
    private val applicationContext = context.applicationContext
    private val downloadManager: DownloadManager =
        applicationContext.getSystemService(DownloadManager::class.java)

    override fun enqueue(destination: File): Long {
        destination.parentFile?.mkdirs()
        check(!destination.exists()) { "Download candidate already exists: ${destination.name}" }

        val request = DownloadManager.Request(Uri.parse(ModelManager.MODEL_DOWNLOAD_URL))
            .setTitle("Gemma 4 E2B")
            .setDescription("Downloading the on-device AI counselor model")
            .setMimeType("application/octet-stream")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))

        return downloadManager.enqueue(request)
    }

    override fun query(downloadId: Long): DownloadSnapshot? =
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return null

            val status = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> DownloadTransportStatus.Pending
                DownloadManager.STATUS_RUNNING -> DownloadTransportStatus.Running
                DownloadManager.STATUS_PAUSED -> DownloadTransportStatus.Paused
                DownloadManager.STATUS_SUCCESSFUL -> DownloadTransportStatus.Successful
                DownloadManager.STATUS_FAILED -> DownloadTransportStatus.Failed
                else -> DownloadTransportStatus.Failed
            }
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reasonColumn = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            val localUriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            DownloadSnapshot(
                id = downloadId,
                status = status,
                bytesDownloaded = bytes.coerceAtLeast(0L),
                totalBytes = total.coerceAtLeast(0L),
                reason = reasonColumn.takeIf { it >= 0 }?.let(cursor::getInt),
                localUri = localUriColumn.takeIf { it >= 0 }?.let(cursor::getString)?.let(Uri::parse)
            )
        }

    override fun remove(downloadId: Long) {
        downloadManager.remove(downloadId)
    }
}

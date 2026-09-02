package com.stressdetector.mobile.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.provider.MediaStore
import com.stressdetector.mobile.data.db.ChatMessageEntity
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatExportUtil {
    fun exportChatToPdf(context: Context, messages: List<ChatMessageEntity>, sessionTitle: String): Result<String> = try {
        val document = PdfDocument()
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create() // A4 standard size
        var page = document.startPage(pageInfo)
        var canvas: Canvas = page.canvas

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 14f
        }

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            isFakeBoldText = true
        }

        var currentY = 50f
        val margin = 50f
        val lineHeight = 20f

        canvas.drawText("Chat Export: $sessionTitle", margin, currentY, titlePaint)
        currentY += 40f

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for (msg in messages) {
            // If we reach near the bottom of the page, start a new one
            if (currentY > 780f) {
                document.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                page = document.startPage(pageInfo)
                canvas = page.canvas
                currentY = 50f
            }

            val senderName = if (msg.sender == "counselor") "AI Counselor" else "User"
            val dateStr = dateFormat.format(Date(msg.timestampMs))

            paint.isFakeBoldText = true
            canvas.drawText("[$dateStr] $senderName:", margin, currentY, paint)
            currentY += lineHeight
            paint.isFakeBoldText = false

            // Basic text wrapping simulation for MVP
            val words = msg.content.split(" ")
            var currentLine = ""
            for (word in words) {
                val tempLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                val width = paint.measureText(tempLine)
                if (width > (595f - margin * 2)) {
                    canvas.drawText(currentLine, margin, currentY, paint)
                    currentY += lineHeight
                    currentLine = word
                } else {
                    currentLine = tempLine
                }
            }
            if (currentLine.isNotEmpty()) {
                canvas.drawText(currentLine, margin, currentY, paint)
                currentY += lineHeight
            }
            currentY += lineHeight // Space between messages
        }

        document.finishPage(page)

        // Save to MediaStore (Downloads folder)
        val fileName = "Chat_Export_${System.currentTimeMillis()}.pdf"
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/StressDetector")
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val uri = resolver.insert(collection, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { os ->
                document.writeTo(os)
            }
            document.close()
            Result.success("Exported to Downloads: $fileName")
        } else {
            document.close()
            Result.failure(IOException("Failed to create MediaStore entry"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

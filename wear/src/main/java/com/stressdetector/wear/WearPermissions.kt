package com.stressdetector.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun requiredWearPermissions(context: Context): Array<String> {
    val permissions = mutableListOf(Manifest.permission.BODY_SENSORS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        permissions += Manifest.permission.ACTIVITY_RECOGNITION
    }
    if (Build.VERSION.SDK_INT >= 33) {
        // Android 13+ requires BODY_SENSORS to be granted BEFORE requesting
        // BODY_SENSORS_BACKGROUND. Notification permission is intentionally
        // not part of the sensor gate: Android permits starting a foreground
        // service without POST_NOTIFICATIONS. Denying notifications must not
        // prevent the Samsung PPG tracker from being activated.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.BODY_SENSORS_BACKGROUND
        }
    }
    return permissions.toTypedArray()
}

fun missingWearPermissions(context: Context): List<String> = requiredWearPermissions(context).filter {
    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
}

fun hasRequiredWearPermissions(context: Context): Boolean = missingWearPermissions(context).isEmpty()

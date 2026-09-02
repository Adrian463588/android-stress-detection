package com.stressdetector.shared

data class PermissionStatus(val allGranted: Boolean, val missingPermissions: List<String>, val shouldShowRationale: Boolean)

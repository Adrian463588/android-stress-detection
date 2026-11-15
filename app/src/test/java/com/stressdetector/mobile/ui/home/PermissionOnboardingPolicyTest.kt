package com.stressdetector.mobile.ui.home

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionOnboardingPolicyTest {
    @Test
    fun firstInstallDoesNotOfferSettingsBeforeRuntimePermissionRequest() {
        assertEquals(
            false,
            shouldOfferPermissionSettingsRecovery(
                requestAttempted = false,
                shouldShowRationale = false,
                allGranted = false
            )
        )
    }

    @Test
    fun settingsRecoveryRequiresAnAttemptAndUnrecoverableRuntimePrompt() {
        assertEquals(
            false,
            shouldOfferPermissionSettingsRecovery(
                requestAttempted = false,
                shouldShowRationale = false,
                allGranted = false
            )
        )
        assertEquals(
            false,
            shouldOfferPermissionSettingsRecovery(
                requestAttempted = true,
                shouldShowRationale = true,
                allGranted = false
            )
        )
        assertEquals(
            true,
            shouldOfferPermissionSettingsRecovery(
                requestAttempted = true,
                shouldShowRationale = false,
                allGranted = false
            )
        )
        assertEquals(
            false,
            shouldOfferPermissionSettingsRecovery(
                requestAttempted = true,
                shouldShowRationale = false,
                allGranted = true
            )
        )
    }

    @Test
    fun modernAndroidExplainsNearbyGroupingAndListsAllPermissionCategories() {
        val entries = permissionChecklistEntries(
            apiLevel = 33,
            watchPermissionsGranted = false,
            microphoneGranted = false,
            notificationsEnabled = false
        )

        assertEquals(
            listOf(
                PermissionCategory.LOCATION,
                PermissionCategory.MICROPHONE,
                PermissionCategory.BLUETOOTH,
                PermissionCategory.NEARBY_DEVICES,
                PermissionCategory.NOTIFICATIONS,
                PermissionCategory.STORAGE
            ),
            entries.map(PermissionChecklistEntry::category)
        )
        assertEquals("Tidak diperlukan", entries[0].status)
        assertEquals("Belum diizinkan", entries[1].status)
        assertEquals("Diperlukan", entries[2].status)
        assertEquals("Diatur bersama Bluetooth", entries[3].status)
        assertEquals("Belum diizinkan", entries[4].status)
        assertEquals("Pilih saat dibutuhkan", entries[5].status)
        assertTrue(entries[5].description.contains("tidak meminta akses penyimpanan luas"))
    }

    @Test
    fun runtimeRequestIncludesEveryPermissionApplicableToTheAndroidVersion() {
        assertEquals(onboardingRuntimePermissions(30), onboardingRuntimePermissions(26))
        assertEquals(onboardingRuntimePermissions(31), onboardingRuntimePermissions(32))
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO
            ),
            onboardingRuntimePermissions(30)
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO
            ),
            onboardingRuntimePermissions(31)
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            onboardingRuntimePermissions(33)
        )
        assertFalse(onboardingRuntimePermissions(33).contains(Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    @Test
    fun legacyAndroidExplainsLocationAsWatchDiscoveryPrerequisite() {
        val entries = permissionChecklistEntries(
            apiLevel = 30,
            watchPermissionsGranted = false,
            microphoneGranted = true,
            notificationsEnabled = true
        )

        assertEquals("Diperlukan", entries.first().status)
        assertTrue(entries.first().description.contains("Android 11 ke bawah"))
        assertEquals("Mengikuti izin Lokasi", entries[3].status)
        assertEquals("Diizinkan", entries[1].status)
        assertEquals("Aktif", entries[4].status)
    }
}

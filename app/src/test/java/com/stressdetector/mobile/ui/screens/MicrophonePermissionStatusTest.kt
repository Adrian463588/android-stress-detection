package com.stressdetector.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophonePermissionStatusTest {

    @Test
    fun resolveHandlesGrantedInitialDeniedRationaleRevokedAndPermanentlyDenied() {
        assertEquals(
            MicrophonePermissionStatus.GRANTED,
            resolveMicrophonePermissionStatus(
                isGranted = true,
                hasRequestedBefore = false,
                shouldShowRationale = false
            )
        )
        assertEquals(
            MicrophonePermissionStatus.CAN_REQUEST,
            resolveMicrophonePermissionStatus(
                isGranted = false,
                hasRequestedBefore = false,
                shouldShowRationale = false
            )
        )
        assertEquals(
            MicrophonePermissionStatus.NEEDS_RATIONALE,
            resolveMicrophonePermissionStatus(
                isGranted = false,
                hasRequestedBefore = true,
                shouldShowRationale = true
            )
        )
        // Simulates one-time permission or revocation in OS Settings when hasRequestedBefore was cleared on grant.
        assertEquals(
            MicrophonePermissionStatus.CAN_REQUEST,
            resolveMicrophonePermissionStatus(
                isGranted = false,
                hasRequestedBefore = false,
                shouldShowRationale = false
            )
        )
        assertEquals(
            MicrophonePermissionStatus.PERMANENTLY_DENIED,
            resolveMicrophonePermissionStatus(
                isGranted = false,
                hasRequestedBefore = true,
                shouldShowRationale = false
            )
        )
    }
}

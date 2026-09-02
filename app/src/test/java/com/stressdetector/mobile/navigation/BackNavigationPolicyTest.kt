package com.stressdetector.mobile.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BackNavigationPolicyTest {
    @Test
    fun nonHomeTopLevelReturnsHome() {
        assertEquals(
            BackNavigationAction.GO_HOME,
            BackNavigationPolicy.decide("profile", documentLibraryVisible = false, recording = false)
        )
    }

    @Test
    fun nestedReportPopsToParent() {
        assertEquals(
            BackNavigationAction.POP_NESTED,
            BackNavigationPolicy.decide("history/abc", documentLibraryVisible = false, recording = false)
        )
    }

    @Test
    fun overlayIsDismissedBeforeNavigation() {
        assertEquals(
            BackNavigationAction.DISMISS_OVERLAY,
            BackNavigationPolicy.decide("profile", documentLibraryVisible = true, recording = false)
        )
    }

    @Test
    fun recordingNeverClosesTheActivity() {
        assertEquals(
            BackNavigationAction.STAY,
            BackNavigationPolicy.decide("home", documentLibraryVisible = false, recording = true)
        )
    }
}

package com.stressdetector.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDestinationTest {
    @Test
    fun nestedRoutesKeepParentShellSelection() {
        assertEquals(Screen.History, parentDestinationFor("history/session-1"))
        assertEquals(Screen.Profile, parentDestinationFor(Screen.CounselorPersona.route))
        assertEquals(Screen.Chat, parentDestinationFor("documents/doc-1"))
    }
}

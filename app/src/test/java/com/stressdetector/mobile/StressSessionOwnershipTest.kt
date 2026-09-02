package com.stressdetector.mobile

import com.stressdetector.mobile.data.PpgStreamState
import com.stressdetector.shared.WearSessionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StressSessionOwnershipTest {
    @Test
    fun homeProjectionRejectsVerbatimAndUnownedSessions() {
        assertTrue(PpgStreamState(sessionMode = WearSessionMode.HOME_STRESS).isHomeStressSession())
        assertFalse(PpgStreamState(sessionMode = WearSessionMode.VERBATIM_AROUSAL).isHomeStressSession())
        assertFalse(PpgStreamState().isHomeStressSession())
    }
}

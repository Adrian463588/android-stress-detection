package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounselorPreferencesStoreTest {

    @Test
    fun `updates mode response profile and behavioral context synchronously`() {
        val store = CounselorPreferencesStore.InMemory()

        assertEquals(CounselingInteractionMode.GENERAL_COUNSELING, store.state.value.interactionMode)
        assertEquals(ResponseProfile.STANDARD, store.state.value.responseProfile)
        assertTrue(store.state.value.includeBehavioralContext)

        store.setInteractionMode(CounselingInteractionMode.COUNSELOR_PLANNING)
        assertEquals(CounselingInteractionMode.COUNSELOR_PLANNING, store.state.value.interactionMode)
        assertEquals(ResponseProfile.COUNSELING_PLAN, store.state.value.responseProfile)

        store.setInteractionMode(CounselingInteractionMode.ASSESSMENT_INTERPRETATION)
        assertEquals(CounselingInteractionMode.ASSESSMENT_INTERPRETATION, store.state.value.interactionMode)
        assertEquals(ResponseProfile.STANDARD, store.state.value.responseProfile)

        store.setResponseProfile(ResponseProfile.DETAILED)
        assertEquals(ResponseProfile.DETAILED, store.state.value.responseProfile)

        store.setIncludeBehavioralContext(false)
        assertFalse(store.state.value.includeBehavioralContext)
    }
}

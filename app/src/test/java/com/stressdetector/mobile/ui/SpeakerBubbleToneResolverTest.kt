package com.stressdetector.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpeakerBubbleToneResolverTest {
    @Test
    fun canonicalSpeakersUseDifferentStableTones() {
        assertEquals(SpeakerBubbleTone.FIRST, DefaultSpeakerBubbleToneResolver.resolve("Speaker_0"))
        assertEquals(SpeakerBubbleTone.SECOND, DefaultSpeakerBubbleToneResolver.resolve("Speaker_1"))
        assertNotEquals(
            DefaultSpeakerBubbleToneResolver.resolve("Speaker_0"),
            DefaultSpeakerBubbleToneResolver.resolve("Speaker_1")
        )
    }

    @Test
    fun editedLabelsKeepSemanticTone() {
        assertEquals(SpeakerBubbleTone.FIRST, DefaultSpeakerBubbleToneResolver.resolve("Psychologist"))
        assertEquals(SpeakerBubbleTone.SECOND, DefaultSpeakerBubbleToneResolver.resolve("Patient"))
    }

    @Test
    fun unknownSpeakerToneIsDeterministic() {
        val first = DefaultSpeakerBubbleToneResolver.resolve("cluster-unknown")
        repeat(10) { assertEquals(first, DefaultSpeakerBubbleToneResolver.resolve("cluster-unknown")) }
    }
}

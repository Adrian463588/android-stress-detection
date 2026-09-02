package com.stressdetector.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableDiarizationAssignerTest {
    @Test
    fun clusterIdsCanChangeWithoutChangingStableIdentity() {
        val assigner = StableDiarizationAssigner()

        val first = assigner.assign(
            listOf(
                DiarizationSegment(0L, 1_000L, rawCluster = 7),
                DiarizationSegment(1_000L, 2_000L, rawCluster = 9)
            )
        )
        val second = assigner.assign(
            listOf(
                DiarizationSegment(0L, 1_000L, rawCluster = 41),
                DiarizationSegment(1_000L, 2_000L, rawCluster = 3)
            )
        )

        assertEquals(0, first[0].stableSpeakerIndex)
        assertEquals(1, first[1].stableSpeakerIndex)
        assertEquals(0, second[0].stableSpeakerIndex)
        assertEquals(1, second[1].stableSpeakerIndex)
    }

    @Test
    fun clusterWithoutTemporalEvidenceRemainsUnknown() {
        val assigner = StableDiarizationAssigner()
        assigner.assign(listOf(DiarizationSegment(0L, 1_000L, rawCluster = 7)))

        val result = assigner.assign(listOf(DiarizationSegment(5_000L, 6_000L, rawCluster = 99)))

        assertNull(result.single().stableSpeakerIndex)
    }
}

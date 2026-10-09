package com.stressdetector.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerbatimDiarizationIdentityTest {
    @Test
    fun `native embeddings retain identity across gaps and changed cluster ids`() {
        val assigner = StableDiarizationAssigner()
        assertEquals(0, assigner.assign(listOf(turn(0, 1_000, 7)), mapOf(7 to floatArrayOf(1f, 0f))).single().stableSpeakerIndex)
        assertEquals(1, assigner.assign(listOf(turn(10_000, 12_000, 0)), mapOf(0 to floatArrayOf(0f, 1f))).single().stableSpeakerIndex)
        assertEquals(0, assigner.assign(listOf(turn(100_000, 102_000, 12)), mapOf(12 to floatArrayOf(1f, 0f))).single().stableSpeakerIndex)
    }

    @Test
    fun `ambiguous third voice or invalid native embedding never invents identity`() {
        val assigner = StableDiarizationAssigner()
        assigner.assign(
            listOf(turn(0, 1_000, 0), turn(2_000, 3_000, 1)),
            mapOf(0 to floatArrayOf(1f, 0f), 1 to floatArrayOf(0f, 1f))
        )
        assertNull(assigner.assign(listOf(turn(10_000, 11_000, 0)), mapOf(0 to floatArrayOf(1f, 1f))).single().stableSpeakerIndex)
        assertNull(assigner.assign(listOf(turn(20_000, 21_000, 0)), mapOf(0 to floatArrayOf(-1f, -1f))).single().stableSpeakerIndex)
        assigner.reset()
        assertNull(assigner.assign(listOf(turn(0, 1_000, 0)), mapOf(0 to floatArrayOf(Float.NaN))).single().stableSpeakerIndex)
    }

    @Test
    fun `two turns in one VAD utterance keep actual diarization crop boundaries`() {
        val planned = DiarizedUtterancePlanner.plan(listOf(stable(100, 1_800, 0), stable(2_000, 3_500, 1)), 0, 4_000)
        assertPlan(planned, listOf(0L, 100L, 1_800L, 2_000L, 3_500L, 4_000L), listOf(null, 0, null, 1, null))
    }

    @Test
    fun `conflicting overlap abstains only within its actual bounds`() {
        val planned = DiarizedUtterancePlanner.plan(listOf(stable(100, 2_000, 0), stable(1_000, 3_000, 1)), 0, 4_000)
        assertPlan(planned, listOf(0L, 100L, 1_000L, 2_000L, 3_000L, 4_000L), listOf(null, 0, null, 1, null))
    }

    @Test
    fun `one sparse identity never labels uncovered utterance context`() {
        val planned = DiarizedUtterancePlanner.plan(listOf(stable(1_000, 2_000, 0)), 0, 4_000)
        assertPlan(planned, listOf(0L, 1_000L, 2_000L, 4_000L), listOf(null, 0, null))
    }

    @Test
    fun `same identity never bridges an uncovered gap`() {
        val planned = DiarizedUtterancePlanner.plan(listOf(stable(100, 1_000, 0), stable(1_500, 3_000, 0)), 0, 4_000)
        assertPlan(planned, listOf(0L, 100L, 1_000L, 1_500L, 3_000L, 4_000L), listOf(null, 0, null, 0, null))
    }

    @Test
    fun `compatible speaker overlap merges without retaining a false raw cluster`() {
        val planned = DiarizedUtterancePlanner.plan(
            listOf(StableDiarizationSegment(100, 2_000, 7, 0), StableDiarizationSegment(1_000, 3_000, 9, 0)),
            0,
            4_000
        )
        assertPlan(planned, listOf(0L, 100L, 3_000L, 4_000L), listOf(null, 0, null))
        assertEquals(-1, planned[1].rawCluster)
    }

    @Test
    fun `nested compatible turns never shorten their enclosing interval`() {
        val planned = DiarizedUtterancePlanner.plan(
            listOf(stable(100, 3_000, 0), stable(500, 1_000, 0), stable(2_800, 3_500, 0)),
            0,
            4_000
        )
        assertPlan(planned, listOf(0L, 100L, 3_500L, 4_000L), listOf(null, 0, null))
    }

    @Test
    fun `an unresolved overlapping turn cannot inherit the known speaker`() {
        val planned = DiarizedUtterancePlanner.plan(
            listOf(stable(0, 4_000, 0), StableDiarizationSegment(1_000, 2_000, 9, null)),
            0,
            4_000
        )
        assertPlan(planned, listOf(0L, 1_000L, 2_000L, 4_000L), listOf(0, null, 0))
    }

    @Test
    fun `clipping and input order preserve a complete positive duration partition`() {
        val turns = listOf(
            stable(2_000, 5_000, 1),
            stable(-100, 1_000, 0),
            stable(1_500, 1_500, 0),
            stable(1_800, 1_700, 1),
            stable(6_000, 7_000, 0)
        )
        val planned = DiarizedUtterancePlanner.plan(turns, 0, 4_000)
        assertPlan(planned, listOf(0L, 1_000L, 2_000L, 4_000L), listOf(0, null, 1))
        assertEquals(planned, DiarizedUtterancePlanner.plan(turns.reversed(), 0, 4_000))
    }

    @Test
    fun `touching different speakers have no invented overlap or gap`() {
        val planned = DiarizedUtterancePlanner.plan(listOf(stable(0, 2_000, 0), stable(2_000, 4_000, 1)), 0, 4_000)
        assertPlan(planned, listOf(0L, 2_000L, 4_000L), listOf(0, 1))
    }

    @Test
    fun `missing native turns stay Unknown and empty ranges emit no spans`() {
        assertPlan(DiarizedUtterancePlanner.plan(emptyList(), 0, 4_000), listOf(0L, 4_000L), listOf(null))
        assertEquals(emptyList<StableDiarizationSegment>(), DiarizedUtterancePlanner.plan(emptyList(), 1_000, 1_000))
        assertEquals(emptyList<StableDiarizationSegment>(), DiarizedUtterancePlanner.plan(emptyList(), 2_000, 1_000))
    }

    @Test
    fun `exclusive raw speaker spans retain both clean sides of a partial conflict`() {
        val turns = listOf(turn(0, 2_000, 7), turn(1_000, 3_000, 9))
        val expected = listOf(turn(0, 1_000, 7), turn(2_000, 3_000, 9))
        assertEquals(expected, DiarizedUtterancePlanner.exclusiveSpeakerSpans(turns))
        assertEquals(expected, DiarizedUtterancePlanner.exclusiveSpeakerSpans(turns.reversed()))
    }

    @Test
    fun `exclusive raw speaker spans merge same cluster duplicates and subtract nested conflict`() {
        val turns = listOf(turn(0, 4_000, 7), turn(500, 1_000, 7), turn(1_500, 2_500, 9))
        val expected = listOf(turn(0, 1_500, 7), turn(2_500, 4_000, 7))
        assertEquals(expected, DiarizedUtterancePlanner.exclusiveSpeakerSpans(turns))
    }

    @Test
    fun `exclusive raw speaker spans preserve gaps and ignore empty or reversed intervals`() {
        val turns = listOf(turn(0, 500, 7), turn(500, 1_000, 7), turn(1_200, 1_500, 7), turn(900, 800, 9), turn(1_000, 1_000, 9))
        val expected = listOf(turn(0, 1_000, 7), turn(1_200, 1_500, 7))
        assertEquals(expected, DiarizedUtterancePlanner.exclusiveSpeakerSpans(turns))
        assertEquals(emptyList<DiarizationSegment>(), DiarizedUtterancePlanner.exclusiveSpeakerSpans(emptyList()))
    }

    @Test
    fun `disjoint fragmented clusters with matching embeddings share one identity`() {
        val assigner = StableDiarizationAssigner()
        val assigned = assigner.assign(
            listOf(turn(2_000, 3_000, 9), turn(0, 1_000, 7)),
            mapOf(7 to floatArrayOf(1f, 0f), 9 to floatArrayOf(1f, 0f))
        )
        assertEquals(listOf(0, 0), assigned.map { it.stableSpeakerIndex })
        assertEquals(listOf(2_000L, 0L), assigned.map { it.startOffsetMs })
        val later = assigner.assign(listOf(turn(10_000, 11_000, 31)), mapOf(31 to floatArrayOf(1f, 0f)))
        assertEquals(0, later.single().stableSpeakerIndex)
    }

    @Test
    fun `overlapping raw clusters cannot reuse one identity even with matching embeddings`() {
        val assigned = StableDiarizationAssigner().assign(
            listOf(turn(0, 2_000, 7), turn(1_000, 3_000, 9)),
            mapOf(7 to floatArrayOf(1f, 0f), 9 to floatArrayOf(1f, 0f))
        )
        assertEquals(listOf(0, null), assigned.map { it.stableSpeakerIndex })
    }

    @Test
    fun `short embeddingless observation abstains despite full temporal coverage`() {
        val assigned = seededAssigner().assign(listOf(turn(100, 200, 7)), mapOf(7 to FloatArray(0)))
        assertNull(assigned.single().stableSpeakerIndex)
    }

    @Test
    fun `tiny competing boundary overlaps never choose by cluster order`() {
        val assigned = seededAssigner().assign(listOf(turn(999, 1_001, 7)), mapOf(7 to FloatArray(0)))
        assertNull(assigned.single().stableSpeakerIndex)
    }

    @Test
    fun `temporal support must cover a meaningful part of the current cluster`() {
        val assigner = StableDiarizationAssigner()
        assigner.assign(listOf(turn(0, 1_000, 0)), mapOf(0 to floatArrayOf(1f, 0f)))
        val assigned = assigner.assign(listOf(turn(500, 2_000, 7)), mapOf(7 to FloatArray(0)))
        assertNull(assigned.single().stableSpeakerIndex)
    }

    @Test
    fun `competing temporal support needs a margin even when minimum coverage passes`() {
        val assigned = seededAssigner(minimumTemporalCoverage = 0.5f)
            .assign(listOf(turn(400, 1_500, 7)), mapOf(7 to FloatArray(0)))
        assertNull(assigned.single().stableSpeakerIndex)
    }

    @Test
    fun `temporal duration and coverage thresholds are inclusive`() {
        val assigner = StableDiarizationAssigner()
        assigner.assign(listOf(turn(0, 300, 0)), mapOf(0 to floatArrayOf(1f, 0f)))
        val assigned = assigner.assign(listOf(turn(0, 500, 7)), mapOf(7 to FloatArray(0)))
        assertEquals(0, assigned.single().stableSpeakerIndex)
    }

    @Test
    fun `duplicate previous intervals cannot inflate temporal coverage`() {
        val assigner = StableDiarizationAssigner()
        assigner.assign(listOf(turn(0, 400, 0), turn(0, 400, 0)), mapOf(0 to floatArrayOf(1f, 0f)))
        val assigned = assigner.assign(listOf(turn(0, 1_000, 7)), mapOf(7 to FloatArray(0)))
        assertNull(assigned.single().stableSpeakerIndex)
    }

    @Test
    fun `previous conflicting overlap cannot supply temporal identity evidence`() {
        val assigner = StableDiarizationAssigner()
        assigner.assign(
            listOf(turn(0, 1_500, 0), turn(500, 2_000, 1)),
            mapOf(0 to floatArrayOf(1f, 0f), 1 to floatArrayOf(0f, 1f))
        )
        val assigned = assigner.assign(listOf(turn(600, 1_400, 7)), mapOf(7 to FloatArray(0)))
        assertNull(assigned.single().stableSpeakerIndex)
    }

    @Test
    fun `insufficient native embedding never bootstraps a speaker`() {
        val assigned = StableDiarizationAssigner().assign(listOf(turn(0, 1_000, 7)), mapOf(7 to FloatArray(0)))
        assertNull(assigned.single().stableSpeakerIndex)
    }

    private fun assertPlan(planned: List<StableDiarizationSegment>, boundaries: List<Long>, identities: List<Int?>) {
        assertEquals(identities, planned.map { it.stableSpeakerIndex })
        assertEquals(boundaries.dropLast(1), planned.map { it.startOffsetMs })
        assertEquals(boundaries.drop(1), planned.map { it.endOffsetMs })
    }

    private fun seededAssigner(minimumTemporalCoverage: Float = 0.6f): StableDiarizationAssigner =
        StableDiarizationAssigner(temporalPolicy = TemporalSpeakerEvidencePolicy(minimumCoverage = minimumTemporalCoverage)).apply {
            assign(
                listOf(turn(0, 1_000, 0), turn(1_000, 2_000, 1)),
                mapOf(0 to floatArrayOf(1f, 0f), 1 to floatArrayOf(0f, 1f))
            )
        }

    private fun turn(start: Long, end: Long, cluster: Int) = DiarizationSegment(start, end, cluster)
    private fun stable(start: Long, end: Long, speaker: Int) = StableDiarizationSegment(start, end, speaker, speaker)
}

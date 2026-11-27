package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveCounselingRouterTest {

    @Test
    fun `safety override takes highest priority over journaling or assessments`() {
        val decision = AdaptiveCounselingRouter.evaluate(
            userMessage = "Saya ingin mengakhiri hidup",
            recommendedInstruments = listOf(instrument("bdi", "Beck Depression Inventory")),
            isCrisis = true
        )

        assertEquals(AdaptiveCounselingRoute.SAFETY_OVERRIDE, decision.route)
        assertNull(decision.assessmentRecommendation)
    }

    @Test
    fun `daily wellbeing messages route to journaling or goal suggestion`() {
        val journal = AdaptiveCounselingRouter.evaluate(
            userMessage = "Hari ini saya curhat dulu soal susah tidur belakangan ini",
            recommendedInstruments = emptyList()
        )
        assertEquals(AdaptiveCounselingRoute.DAILY_WELLBEING, journal.route)
        assertEquals(CounselorMessageType.JOURNAL_PROMPT, journal.messageType)
        assertNotNull(journal.journalPrompt)

        val goal = AdaptiveCounselingRouter.evaluate(
            userMessage = "Akhir-akhir ini saya sering menunda pekerjaan dan susah manajemen waktu",
            recommendedInstruments = emptyList()
        )
        assertEquals(AdaptiveCounselingRoute.DAILY_WELLBEING, goal.route)
        assertEquals(CounselorMessageType.GOAL_SUGGESTION, goal.messageType)
        assertNotNull(goal.goalSuggestion)

        val sleepOnly = AdaptiveCounselingRouter.evaluate(
            userMessage = "Belakangan ini saya susah tidur tiap malam",
            recommendedInstruments = emptyList()
        )
        assertEquals(AdaptiveCounselingRoute.DAILY_WELLBEING, sleepOnly.route)
        assertEquals(CounselorMessageType.JOURNAL_PROMPT, sleepOnly.messageType)

        val delayTaskOnly = AdaptiveCounselingRouter.evaluate(
            userMessage = "Saya sering menunda tugas kuliah",
            recommendedInstruments = emptyList()
        )
        assertEquals(AdaptiveCounselingRoute.DAILY_WELLBEING, delayTaskOnly.route)
        assertEquals(CounselorMessageType.GOAL_SUGGESTION, delayTaskOnly.messageType)
    }

    @Test
    fun `depression cues prefer BDI screener and avoid defaulting to MMPI`() {
        val decision = AdaptiveCounselingRouter.evaluate(
            userMessage = "Saya kehilangan semangat dan sulit menikmati aktivitas setiap hari",
            recommendedInstruments = listOf(
                instrument("mmpi", "MMPI-2"),
                instrument("bdi", "Beck Depression Inventory (BDI)")
            )
        )

        assertEquals(AdaptiveCounselingRoute.ASSESSMENT_SUPPORT, decision.route)
        assertEquals(CounselorMessageType.ASSESSMENT_RECOMMENDATION, decision.messageType)
        assertEquals("bdi", decision.assessmentRecommendation?.instrumentId)
    }

    @Test
    fun `projective and cognitive assessments enforce dedicated screen requirement`() {
        val decision = AdaptiveCounselingRouter.evaluate(
            userMessage = "Saya mau tes Wartegg",
            recommendedInstruments = listOf(instrument("wartegg", "Wartegg"))
        )

        val rec = decision.assessmentRecommendation
        assertNotNull(rec)
        assertTrue(rec!!.requiresDedicatedScreen)
        assertTrue(rec.requiresExaminerReview)
    }

    @Test
    fun `unregistered unknown instruments are never recommended for execution`() {
        val decision = AdaptiveCounselingRouter.evaluate(
            userMessage = "Tolong rekomendasikan tes",
            recommendedInstruments = listOf(instrument("unknown_unverified_id", "Unknown"))
        )

        assertNull(decision.assessmentRecommendation)
    }

    private fun instrument(id: String, title: String) = AssessmentInstrument(
        id = id,
        title = title,
        category = "Klinis",
        description = "Ringkasan $title",
        source = "Bank Psikotes",
        aliases = listOf(id)
    )
}

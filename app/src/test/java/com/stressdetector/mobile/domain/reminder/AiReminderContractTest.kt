package com.stressdetector.mobile.domain.reminder

import com.stressdetector.mobile.domain.engine.CounselorGenerationRequest
import com.stressdetector.mobile.domain.engine.LlmGenerationState
import com.stressdetector.mobile.domain.engine.LlmReadiness
import com.stressdetector.mobile.domain.engine.LlmRuntimeReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReminderContractTest {
    private val now = Instant.parse("2026-09-08T10:00:00Z")
    private val context = ReminderGroundingContext(
        sourceSessionId = "session-1",
        sessionEndedAt = Instant.parse("2026-09-08T09:00:00Z"),
        label = "Normal",
        qualityTier = "good",
        stressProbability = 0.2f,
        confidence = 0.8f
    )

    @Test
    fun nameIsTrimmedCollapsedAndCappedWithoutInventingAValue() {
        assertEquals("Alex Morgan", ReminderNamePolicy.normalize("  Alex\n  Morgan "))
        assertEquals(null, ReminderNamePolicy.normalize("\u0000 \t"))
        val longName = "A".repeat(ReminderNamePolicy.MAX_CODE_POINTS + 10)
        assertEquals(ReminderNamePolicy.MAX_CODE_POINTS, ReminderNamePolicy.normalize(longName)?.length)
    }

    @Test
    fun scheduleRequiresFutureWithinHorizonAndPreservesTimezone() {
        val zone = ZoneId.of("Asia/Jakarta")
        val valid = ReminderScheduleDecision(now.plusSeconds(3_600), "session-1", zone, "gemma-test")
        assertEquals(null, ReminderSchedulePolicy.validate(valid, now))
        assertEquals("SCHEDULE_NOT_IN_FUTURE", ReminderSchedulePolicy.validate(valid.copy(nextReminderAt = now), now))
        assertEquals(
            "SCHEDULE_OUTSIDE_48_HOUR_HORIZON",
            ReminderSchedulePolicy.validate(valid.copy(nextReminderAt = now.plusSeconds(48 * 3_600 + 1)), now)
        )
        assertEquals(
            "session-1:${valid.nextReminderAt.toEpochMilli()}",
            ReminderSchedulePolicy.occurrenceKey("session-1", valid.nextReminderAt)
        )
    }

    @Test
    fun relativeDateUsesLocalCalendarWithoutMakingClinicalClaims() {
        assertEquals(
            "kemarin",
            ReminderRelativeDate.describe(
                Instant.parse("2026-09-07T23:30:00Z"),
                Instant.parse("2026-09-08T00:30:00Z"),
                ZoneId.of("UTC")
            )
        )
    }

    @Test
    fun outputGuardRejectsUnrecordedGroundingClaim() {
        val result = ReminderOutputGuard.validate("Halo Alex, sudah melakukan tugas kemarin?", null)
        assertTrue(result is ReminderOutputGuard.Result.Rejected)
        assertEquals("REMINDER_UNRECORDED_ACTION_CLAIM", (result as ReminderOutputGuard.Result.Rejected).reason)
    }

    @Test
    fun outputGuardRejectsClaimsOfCompletedTherapyWithoutRecordedAction() {
        listOf(
            "Kemarin kamu sudah mencoba grounding, lanjutkan lagi.",
            "Latihan terapi kemarin sudah selesai.",
            "You completed your grounding practice yesterday."
        ).forEach { message ->
            val result = ReminderOutputGuard.validate(message, null)
            assertEquals("REMINDER_UNRECORDED_ACTION_CLAIM", (result as ReminderOutputGuard.Result.Rejected).reason)
        }
    }

    @Test
    fun outputGuardAcceptsNeutralActionQuestionsWithoutRecordedAction() {
        listOf(
            "Sudah mencoba beristirahat sebentar?",
            "Apakah kamu sudah mencoba beristirahat sebentar?",
            "Halo Alex. Sudah mencoba beristirahat sebentar?"
        ).forEach { message ->
            assertEquals(message, ReminderOutputGuard.Result.Accepted, ReminderOutputGuard.validate(message, null))
        }
    }

    @Test
    fun aQuestionDoesNotHideAnActionAssertionOrInventAnEarlierAssignment() {
        listOf(
            "Kamu sudah mencoba beristirahat sebentar.",
            "Kamu sudah mencoba beristirahat sebentar, bukan?",
            "Kamu telah menyelesaikan terapi. Bagaimana kabarmu?",
            "Sudah mencoba beristirahat sebentar? Kamu telah menyelesaikan terapi.",
            "Sudah mencoba beristirahat, kamu telah menyelesaikan terapi?",
            "Sudah mencoba tugas kemarin?"
        ).forEach { message ->
            val result = ReminderOutputGuard.validate(message, null) as ReminderOutputGuard.Result.Rejected
            assertEquals(message, "REMINDER_UNRECORDED_ACTION_CLAIM", result.reason)
        }
    }

    @Test
    fun contentGeneratorCollectsRealDeltasAndAcceptsNaturalSafeMessage() = runTest {
        val engine = FakeEngine(
            fragments = listOf(
                LlmGenerationState.Content("{\"message\":\"Halo Alex, "),
                LlmGenerationState.Content("bagaimana kabarmu hari ini? Sempat mencoba hal kecil yang membantu?\"}")
            )
        )
        val result = LiteRtReminderContentGenerator(engine).generate(now, context, "Alex")
        val generated = result as ReminderContentResult.Generated
        assertEquals("Alex", generated.content.displayNameUsed)
        assertTrue(generated.content.message.startsWith("Halo Alex"))
    }

    @Test
    fun schedulePlannerRejectsInvalidJsonWithoutFallbackTime() = runTest {
        val result = LiteRtReminderSchedulePlanner(
            FakeEngine(listOf(LlmGenerationState.Content("not-json")))
        ).plan(now, context)
        assertEquals("MODEL_OUTPUT_INVALID_JSON", (result as ReminderScheduleResult.InvalidOutput).reason)
    }

    @Test
    fun modelUnavailableDoesNotGenerateFallbackContent() = runTest {
        val engine = FakeEngine(
            fragments = emptyList(),
            preflightResult = LlmRuntimeReadiness.Unavailable("model unavailable", retryable = false)
        )
        val result = LiteRtReminderContentGenerator(engine).generate(now, context, null)
        assertTrue(result is ReminderContentResult.ModelUnavailable)
    }

    private class FakeEngine(
        private val fragments: List<LlmGenerationState>,
        private val preflightResult: LlmRuntimeReadiness = LlmRuntimeReadiness.Ready("runtime-test")
    ) : OnDeviceLlmEngine {
        override fun readiness(): LlmReadiness = LlmReadiness.Ready("model-test")

        override suspend fun preflight(): LlmRuntimeReadiness = preflightResult

        override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = flowOf(*fragments.toTypedArray())
    }
}

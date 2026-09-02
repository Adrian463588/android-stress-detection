package com.stressdetector.mobile

import com.stressdetector.mobile.domain.engine.ClinicalSafetyInterceptor
import com.stressdetector.mobile.domain.engine.CrisisInterceptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrisisEscalationTest {
    private val interceptor = ClinicalSafetyInterceptor()

    @Test
    fun `self harm phrasing produces a typed escalation after normalization`() {
        val result = interceptor.intercept("  Saya  INGIN   bunuh diri  ")

        assertTrue(result is CrisisInterceptionResult.Escalate)
        val escalation = result as CrisisInterceptionResult.Escalate
        assertEquals("bunuh diri", escalation.matchedKeyword)
        assertTrue(escalation.response.templateVersion.isNotBlank())
        assertTrue(escalation.response.text.contains("Keselamatanmu penting"))
        assertTrue(escalation.response.supportActions.any { it.uri == "tel:119" })
        assertTrue(escalation.response.supportActions.any { it.uri == "https://healing119.id" })
    }

    @Test
    fun `ordinary counseling question is not escalated`() {
        val result = interceptor.intercept("Bagaimana cara melakukan relaksasi napas?")

        assertEquals(CrisisInterceptionResult.NoCrisis, result)
    }

    @Test
    fun `crisis message never claims to be a diagnosis`() {
        val result = interceptor.intercept("Saya merasa ingin mati") as CrisisInterceptionResult.Escalate

        assertFalse(result.response.text.contains("diagnosis", ignoreCase = true))
    }
}

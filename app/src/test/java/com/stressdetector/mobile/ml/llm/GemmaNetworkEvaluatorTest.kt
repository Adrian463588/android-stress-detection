package com.stressdetector.mobile.ml.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaNetworkEvaluatorTest {
    @Test
    fun onlyValidatedWifiMayDownload() {
        assertTrue(
            GemmaNetworkEvaluation(
                GemmaNetworkState.WIFI_VALIDATED,
                "validated"
            ).mayDownload
        )
        assertFalse(
            GemmaNetworkEvaluation(
                GemmaNetworkState.WIFI_CONNECTED_UNVALIDATED,
                "unvalidated"
            ).mayDownload
        )
        assertFalse(
            GemmaNetworkEvaluation(
                GemmaNetworkState.CELLULAR,
                "cellular"
            ).mayDownload
        )
    }

    @Test
    fun networkMessagesKeepWifiAndTransferPoliciesDistinct() {
        assertEquals(
            "Wi-Fi tersambung, tetapi internet belum tervalidasi. Periksa captive portal atau koneksi internet.",
            GemmaNetworkEvaluation(
                GemmaNetworkState.WIFI_CONNECTED_UNVALIDATED,
                "unvalidated"
            ).userMessage
        )
        assertEquals(
            "Jaringan seluler terdeteksi. Gemma hanya dapat diunduh melalui Wi-Fi tervalidasi.",
            GemmaNetworkEvaluation(GemmaNetworkState.CELLULAR, "cellular").userMessage
        )
    }
}

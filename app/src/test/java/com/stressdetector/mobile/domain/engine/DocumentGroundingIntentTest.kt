package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentGroundingIntentTest {
    @Test
    fun personalVentingAndCopingAreNotDocumentRequests() {
        listOf(
            "Dalam keluarga saya merasa kurang didengar",
            "Menurut saya bagian yang paling sulit adalah bercerita",
            "Bagaimana saya mengatasi rasa sedih?",
            "Apa yang bisa saya lakukan ketika merasa lelah?",
            "What can I do when I feel sad?",
            "Apa perasaan yang saya ceritakan tadi?"
        ).forEach { assertFalse(it, DocumentGroundingIntentDetector.requiresLocalEvidence(it)) }
    }

    @Test
    fun factsAndExplicitDocumentRequestsStillRequireEvidence() {
        listOf(
            "Apa itu depresi?",
            "Menurut dokumen PDF ini, bagaimana coping bekerja?",
            "Berikan sumber penelitian tentang stres",
            "Saya sedih; apa gejala depresi?",
            "I feel anxious, what are the symptoms of depression?"
        )
            .forEach { assertTrue(it, DocumentGroundingIntentDetector.requiresLocalEvidence(it)) }
    }
}

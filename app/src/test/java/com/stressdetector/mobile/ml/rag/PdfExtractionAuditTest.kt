package com.stressdetector.mobile.ml.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfExtractionAuditTest {
    @Test
    fun `report preserves page status and indexes only readable pages`() {
        val report = PdfExtractionReport(
            listOf(
                DocumentPageText(1, "Teks PDF", PageExtractionStatus.COMPLETE),
                DocumentPageText(2, "", PageExtractionStatus.PARTIAL, "OCR lokal belum tersedia")
            )
        )

        assertEquals(listOf(1), report.indexablePages.map(DocumentPageText::pageNumber))
        assertTrue(report.auditSummary().contains("COMPLETE=1"))
        assertTrue(report.auditSummary().contains("PARTIAL=1"))
        assertTrue(report.pageAuditJson().contains("\"page\":2"))
        assertEquals(ExtractionStatus.PARTIAL, report.extractionStatus)
        assertTrue(!report.isRetrievalReady)
    }

    @Test
    fun `processing limit produces an explicit non-retrieval-ready partial report`() {
        val report = PdfExtractionReport(
            pages = listOf(DocumentPageText(1, "Teks awal", PageExtractionStatus.COMPLETE)),
            limitReached = true,
            totalPageCount = 8,
            firstUnexaminedPage = 2
        )

        assertEquals(ExtractionStatus.PARTIAL, report.extractionStatus)
        assertTrue(report.auditSummary().contains("PARTIAL_LIMIT_REACHED=true"))
        assertTrue(report.auditSummary().contains("UNEXAMINED_PAGES=2..8"))
        assertTrue(report.pageAuditJson().contains("\"unexamined_from\":2"))
        assertTrue(!report.isRetrievalReady)
    }
}

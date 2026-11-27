package com.stressdetector.mobile.domain.search

import com.google.gson.Gson
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.CounselingKnowledgeModule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchEngineTest {

    private val catalogFile = File("src/main/canonicalAssets/ai/psychometrics/catalog.json")
    private val assessmentItems: List<CatalogSearchItem> by lazy {
        if (catalogFile.exists()) {
            Gson().fromJson(catalogFile.readText(), Array<AssessmentInstrument>::class.java)
                .map { it.toCatalogSearchItem() }
        } else {
            emptyList()
        }
    }

    private val counselingItems: List<CatalogSearchItem> = listOf(
        CounselingKnowledgeModule(
            id = "bk-01",
            title = "Dasar, Asas & Kode Etik BK",
            pillar = "Pilar 1",
            category = "Etika & Regulasi",
            summary = "Asas kerahasiaan, 12 asas konseling Prayitno, kode etik ABKIN, dan prosedur referral.",
            assetFileName = "bk_01_dasar_dan_etika.md",
            keyTopics = listOf("Asas Kerahasiaan", "Kode Etik ABKIN")
        ),
        CounselingKnowledgeModule(
            id = "bk-03",
            title = "Pendekatan & Intervensi",
            pillar = "Pilar 3",
            category = "Pendekatan Klinis",
            summary = "CBT Restrukturisasi Kognitif, REBT ABCDE, Terapi Realitas WDEP, Person-Centered Rogers.",
            assetFileName = "bk_03_teori_dan_intervensi.md",
            keyTopics = listOf("CBT Kognitif", "REBT Kerangka ABCDE", "Reality Therapy WDEP")
        ),
        CounselingKnowledgeModule(
            id = "bk-05",
            title = "Keterampilan Mikro Konseling",
            pillar = "Pilar 5",
            category = "Teknik Komunikasi",
            summary = "Keterampilan attending SOLER, empati primer & lanjut, paraphrasing, refleksi perasaan.",
            assetFileName = "bk_05_keterampilan_mikro.md",
            keyTopics = listOf("Sikap Duduk SOLER", "Empati Lanjut")
        ),
        CounselingKnowledgeModule(
            id = "bk-07",
            title = "Asesmen Proyektif: Tes Rorschach",
            pillar = "Pilar 7",
            category = "Psikodiagnostik Proyektif",
            summary = "Sistem Komprehensif Exner & R-PAS dan eksplorasi proyektif konseling.",
            assetFileName = "rorschach_projective_assessment.md",
            keyTopics = listOf("Exner CS & R-PAS")
        )
    ).map { it.toCatalogSearchItem() }

    private val engine = CatalogSearchEngine()

    @Test
    fun wartPrefixMatchesWartegg() {
        if (assessmentItems.isEmpty()) return
        val suggestions = engine.buildSuggestions("wart", assessmentItems)
        assertTrue(suggestions.isNotEmpty())
        assertEquals("wartegg", suggestions.first().canonicalItemId)
        assertEquals("Wartegg", suggestions.first().canonicalTitle)
    }

    @Test
    fun wartegAndWateggTypoToleranceMatchesWartegg() {
        if (assessmentItems.isEmpty()) return
        val warteg = engine.buildSuggestions("warteg", assessmentItems)
        assertEquals("Wartegg", warteg.firstOrNull()?.canonicalTitle)

        val wategg = engine.buildSuggestions("wategg", assessmentItems)
        assertEquals("Wartegg", wategg.firstOrNull()?.canonicalTitle)
    }

    @Test
    fun mmpAndMmpiMatchMmpiWithExactOutrankingFuzzy() {
        if (assessmentItems.isEmpty()) return
        val prefix = engine.buildSuggestions("MMP", assessmentItems)
        assertEquals("MMPI", prefix.firstOrNull()?.canonicalTitle)

        val exact = engine.buildSuggestions("mmpi", assessmentItems)
        assertEquals("MMPI", exact.firstOrNull()?.canonicalTitle)
        assertEquals(SearchMatchType.EXACT_TITLE, exact.first().matchType)
    }

    @Test
    fun tiuAndCfMatchMultipleExpectedInstruments() {
        if (assessmentItems.isEmpty()) return
        val tiu = engine.buildSuggestions("TIU", assessmentItems)
        val tiuTitles = tiu.map { it.canonicalTitle }
        assertTrue(tiuTitles.contains("TIU 5"))
        assertTrue(tiuTitles.contains("TIU 6"))

        val cf = engine.buildSuggestions("CF", assessmentItems)
        val cfTitles = cf.map { it.canonicalTitle }
        assertTrue(cfTitles.any { it.startsWith("CFIT") })

        val cfit = engine.buildSuggestions("cfit", assessmentItems)
        assertTrue(cfit.any { it.canonicalTitle == "CFIT 3A" })
        assertTrue(cfit.any { it.canonicalTitle == "CFIT 2A" })
    }

    @Test
    fun counselingQueryMatchesRelevantCounselingMaterials() {
        val konsel = engine.buildSuggestions("konsel", counselingItems)
        assertTrue(konsel.isNotEmpty())
        assertTrue(konsel.all { it.category == SearchCategory.COUNSELING_MATERIAL })

        val cbtTypo = engine.buildSuggestions("konsseling", counselingItems)
        assertTrue(cbtTypo.isNotEmpty())
    }

    @Test
    fun unknownQueryReturnsEmptyResultsAndNoFakeEntries() {
        if (assessmentItems.isEmpty()) return
        val suggestions = engine.buildSuggestions("xyznonexistent", assessmentItems)
        assertTrue(suggestions.isEmpty())

        val results = engine.rankMatches("xyznonexistent", assessmentItems)
        assertTrue(results.isEmpty())
    }

    @Test
    fun emptyAndRepeatedWhitespaceAreNormalizedAndShortQueriesAvoidFuzzyNoise() {
        if (assessmentItems.isEmpty()) return
        assertTrue(engine.buildSuggestions("   ", assessmentItems).isEmpty())

        val spaced = engine.buildSuggestions("  tiu   5  ", assessmentItems)
        assertEquals("TIU 5", spaced.firstOrNull()?.canonicalTitle)

        // Single character query should not trigger broad fuzzy matching
        val oneChar = engine.buildSuggestions("x", assessmentItems)
        assertFalse(oneChar.any { it.matchType == SearchMatchType.FUZZY })
        assertTrue(oneChar.size <= 5)
    }

    @Test
    fun recentUsageBreaksTiesAndSuggestionCountIsCappedAtFive() {
        if (assessmentItems.isEmpty()) return
        val tiedDefault = engine.buildSuggestions("TIU", assessmentItems)
        val tiedWithRecent = engine.buildSuggestions(
            query = "TIU",
            items = assessmentItems,
            recentItemIds = listOf("tiu_6")
        )
        assertEquals("tiu_6", tiedWithRecent.first().canonicalItemId)
        assertEquals(2, tiedDefault.size)

        val broad = engine.buildSuggestions("a", assessmentItems)
        assertTrue(broad.size <= 5)
    }
}

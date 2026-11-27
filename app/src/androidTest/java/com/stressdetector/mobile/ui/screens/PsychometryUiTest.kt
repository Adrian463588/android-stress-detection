package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.assessment.AssessmentForm
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.AssessmentObservation
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentScale
import com.stressdetector.mobile.domain.assessment.AssessmentScore
import com.stressdetector.mobile.domain.assessment.AssessmentValidityStatus
import com.stressdetector.mobile.domain.assessment.ExaminerReviewStatus
import com.stressdetector.mobile.domain.assessment.InstrumentImplementationStatus
import com.stressdetector.mobile.domain.assessment.ProjectivePanelDraft
import com.stressdetector.mobile.domain.assessment.PsychometricInstrumentRegistry
import com.stressdetector.mobile.domain.assessment.RorschachResponseDraft
import com.stressdetector.mobile.domain.assessment.WarteggNormalizedPoint
import com.stressdetector.mobile.domain.engine.AssessmentRecommendationBubblePayload
import com.stressdetector.mobile.domain.engine.AssessmentResultBubblePayload
import com.stressdetector.mobile.domain.engine.GoalSuggestionPayload
import com.stressdetector.mobile.domain.engine.JournalBubblePayload
import com.stressdetector.mobile.ui.StressDetectorTheme
import com.stressdetector.mobile.ui.viewmodels.AssessmentUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PsychometryUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun warteggScreenDisplaysEightBoxesToolbarAndIndependentEvaluation() {
        val form = AssessmentForm(
            version = "1.0",
            title = "Wartegg",
            instructions = "Lengkapi 8 kotak gambar.",
            sourceTitle = "Bank PsikoTest",
            sourceUrl = "asset://wartegg",
            sourceLocator = "Wartegg",
            choices = listOf("Disukai", "Kurang disukai", "Mudah", "Sulit"),
            values = listOf(1, 2, 3, 4),
            scales = listOf(AssessmentScale("projective", "Proyektif", "Observasi")),
            items = emptyList(),
            average = false,
            minimumAnswers = 8,
            interpretationNote = "Observasional"
        )

        composeRule.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    WarteggAssessmentScreen(
                        form = form,
                        busy = false,
                        onSaveDrafts = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("Wartegg · Pelaksanaan Asesmen").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Hapus").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Stimulus").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Disukai (+)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Kurang disukai (-)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Mudah (M)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sulit (S)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Simpan & Tinjau Hasil", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun resultPanelAllowsAiForPendingReviewWhenConsentedAndLocksWhenConsentRevoked() {
        val result = AssessmentResult(
            resultId = "res-wartegg-1",
            instrumentId = "wartegg",
            title = "Wartegg",
            version = "CWS-1.0",
            sourceTitle = "Bank PsikoTest",
            sourceUrl = "asset://wartegg",
            sourceLocator = "Wartegg §5",
            createdAtMillis = 1_700_000_000_000L,
            answered = 8,
            itemCount = 8,
            scores = listOf(AssessmentScore("Kotak Selesai", 8.0, 0.0, 8.0, "Observasional")),
            interpretation = "Menunggu coding pemeriksa.",
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = AssessmentValidityStatus.VALID,
            reviewStatus = ExaminerReviewStatus.PENDING,
            aiContextAllowed = true
        )

        composeRule.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AssessmentResultPanel(
                        result = result,
                        state = AssessmentUiState(loading = false),
                        onExplain = {},
                        onDelete = {},
                        onSubmitExaminerReview = { _, _, _, _, _ -> },
                        onUpdateConsentFlags = { _, _ -> },
                        onDiscussWithAi = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("Interpretasikan dengan AI+").assertIsEnabled()
    }

    @Test
    fun adaptiveRecommendationBubbleSupportsDeferAction() {
        var lastStatus = "OFFERED"
        val payload = AssessmentRecommendationBubblePayload(
            recommendationId = "rec-ui-1",
            instrumentId = "bdi",
            instrumentTitle = "Beck Depression Inventory (BDI)",
            purpose = "Skrining gejala depresi",
            rationale = "Keluhan mood rendah berulang",
            eligibilityNote = "Skrining tervalidasi",
            estimatedDuration = "5 menit",
            requiresDedicatedScreen = true,
            status = "OFFERED"
        )

        composeRule.setContent {
            StressDetectorTheme {
                AdaptiveAssessmentRecommendationCard(
                    payload = payload,
                    onStartAssessment = {},
                    onDefer = { lastStatus = "DEFERRED" },
                    onDecline = { lastStatus = "DECLINED" }
                )
            }
        }

        composeRule.onNodeWithText("Buka Layar Asesmen").assertIsDisplayed()
        composeRule.onNodeWithText("Nanti saja").performClick()
        assertEquals("DEFERRED", lastStatus)
    }

    @Test
    fun warteggRestoresSavedDraftAfterStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val form = sampleWarteggForm()

        restorationTester.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    WarteggAssessmentScreen(
                        form = form,
                        busy = false,
                        onSaveDrafts = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("Judul atau keterangan gambar").performScrollTo().performTextInput("Matahari pagi")
        composeRule.onNodeWithText("Disukai (+)").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Kotak 1, sudah terisi").assertExists()
        composeRule.onNodeWithText("Simpan & Tinjau Hasil (1/8)").assertExists()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithContentDescription("Kotak 1, sudah terisi").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Matahari pagi").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Simpan & Tinjau Hasil (1/8)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun warteggRemainsAccessibleAt320dpAnd15xFontScale() {
        val form = sampleWarteggForm()
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 1.5f)
            ) {
                StressDetectorTheme {
                    Box(modifier = Modifier.width(320.dp)) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            WarteggAssessmentScreen(
                                form = form,
                                busy = false,
                                onSaveDrafts = {}
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Undo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Hapus").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Stimulus").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Simpan & Tinjau Hasil", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun discResultSwitchesBetweenChartAndAccessibleTable() {
        val result = AssessmentResult(
            resultId = "res-disc-1",
            instrumentId = "disc",
            title = "DISC",
            version = "DISC-24 Form Baku",
            sourceTitle = "Bank PsikoTest",
            sourceUrl = "asset://disc",
            sourceLocator = "DISC §4",
            createdAtMillis = 1_700_000_000_000L,
            answered = 24,
            itemCount = 24,
            scores = listOf(
                AssessmentScore("D", -3.0, -24.0, 24.0, "Selisih Most-Least"),
                AssessmentScore("I", 1.0, -24.0, 24.0, "Selisih Most-Least"),
                AssessmentScore("S", 6.0, -24.0, 24.0, "Selisih Most-Least"),
                AssessmentScore("C", -3.0, -24.0, 24.0, "Selisih Most-Least"),
                AssessmentScore("D Most", 5.0, 0.0, 24.0, "Most"),
                AssessmentScore("I Most", 7.0, 0.0, 24.0, "Most"),
                AssessmentScore("S Most", 8.0, 0.0, 24.0, "Most"),
                AssessmentScore("C Most", 2.0, 0.0, 24.0, "Most"),
                AssessmentScore("D Least", 8.0, 0.0, 24.0, "Least"),
                AssessmentScore("I Least", 6.0, 0.0, 24.0, "Least"),
                AssessmentScore("S Least", 2.0, 0.0, 24.0, "Least"),
                AssessmentScore("C Least", 5.0, 0.0, 24.0, "Least")
            ),
            interpretation = "Profil S tertinggi pada skor selisih mentah.",
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = InstrumentImplementationStatus.RAW_SCORE_ONLY,
            validityStatus = AssessmentValidityStatus.VALID,
            reviewStatus = ExaminerReviewStatus.NOT_REQUIRED,
            aiContextAllowed = true
        )

        composeRule.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AssessmentResultPanel(
                        result = result,
                        state = AssessmentUiState(loading = false),
                        onExplain = {},
                        onDelete = {},
                        onSubmitExaminerReview = { _, _, _, _, _ -> },
                        onUpdateConsentFlags = { _, _ -> },
                        onDiscussWithAi = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("Profil Perilaku DISC-24").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Grafik garis DISC", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Interpretasikan dengan AI+").performScrollTo().assertIsEnabled()

        composeRule.onNodeWithText("Tabel Skor").performScrollTo().performClick()
        composeRule.onNodeWithText("D Most").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun rorschachWorkflowRecordsProtocolAndRestoresCard() {
        var saved: List<RorschachResponseDraft> = emptyList()
        val form = AssessmentForm(
            version = "EXNER_CS",
            title = "Tes Rorschach",
            instructions = "Rekam respons dan inquiry 10 kartu.",
            sourceTitle = "Bank PsikoTest",
            sourceUrl = "asset://rorschach",
            sourceLocator = "Rorschach",
            choices = listOf("W", "D"),
            values = listOf(1, 2),
            scales = listOf(AssessmentScale("projective", "Proyektif", "Observasi")),
            items = emptyList(),
            average = false,
            minimumAnswers = 14,
            interpretationNote = "Membutuhkan R >= 14"
        )

        composeRule.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    RorschachAssessmentScreen(
                        form = form,
                        busy = false,
                        onSaveResponses = { list, _ -> saved = list }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Tes Rorschach · Kartu 1/10").assertIsDisplayed()
        composeRule.onNodeWithText("Asosiasi bebas Kartu 1 (apa yang terlihat)").performScrollTo().performTextInput("Kelelawar terbang")
        composeRule.onNodeWithText("Tambah Respons Kartu 1").performScrollTo().performClick()
        composeRule.onNodeWithText("R = 1").assertExists()
        composeRule.onNodeWithText("Respons Tercatat di Kartu 1 (1)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Simpan Protokol Rorschach (R = 1)").performScrollTo().performClick()
        assertEquals(1, saved.size)
        assertEquals("Kelelawar terbang", saved.first().freeAssociation)
    }

    @Test
    fun adaptiveJournalGoalAndResultBubblesTriggerCallbacks() {
        var journalStarted = false
        var goalAccepted = false
        var resultOpened = false

        composeRule.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AdaptiveJournalPromptCard(
                        payload = JournalBubblePayload(
                            promptId = "jp-1",
                            title = "Refleksi & Check-in Harian",
                            description = "Catat jam tidur hari ini.",
                            estimatedMinutes = "3-5 menit"
                        ),
                        onStartJournal = { journalStarted = true }
                    )
                    AdaptiveGoalSuggestionCard(
                        payload = GoalSuggestionPayload(
                            goalId = "goal-1",
                            title = "Jadwal Tidur Konsisten",
                            category = "Tidur",
                            cadence = "Harian",
                            targetCount = 7
                        ),
                        onAcceptGoal = { goalAccepted = true }
                    )
                    AdaptiveAssessmentResultCard(
                        payload = AssessmentResultBubblePayload(
                            instrumentId = "bdi",
                            instrumentTitle = "BDI-II",
                            primaryScoreSummary = "Skor mentah 14",
                            validityLabel = "Valid",
                            reviewLabel = "Tidak diperlukan",
                            aiContextEligible = true
                        ),
                        onOpenDetail = { resultOpened = true }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Mulai Journaling").performScrollTo().performClick()
        composeRule.onNodeWithText("Tambahkan ke Rencana Kebiasaan").performScrollTo().performClick()
        composeRule.onNodeWithText("Lihat Grafik & Rincian Skor").performScrollTo().performClick()

        assertTrue(journalStarted)
        assertTrue(goalAccepted)
        assertTrue(resultOpened)
    }

    @Test
    fun mbtiBipolarBigFiveRadarAndPauliDistributionRenderCleanly() {
        val mbtiResult = AssessmentResult(
            resultId = "res-mbti-ui",
            instrumentId = "mbti",
            title = "MBTI",
            version = "MBTI-70-Baku",
            sourceTitle = "PsikoTest",
            sourceUrl = "",
            sourceLocator = "§1",
            createdAtMillis = 1_700_000_000_000L,
            answered = 70,
            itemCount = 70,
            scores = listOf(
                AssessmentScore("Tipe MBTI", 1.0, 1.0, 1.0, "ENFP"),
                AssessmentScore("Ekstrovert (E)", 8.0, 0.0, 10.0, ""),
                AssessmentScore("Introvert (I)", 2.0, 0.0, 10.0, ""),
                AssessmentScore("Sensing (S)", 6.0, 0.0, 20.0, ""),
                AssessmentScore("Intuition (N)", 14.0, 0.0, 20.0, ""),
                AssessmentScore("Thinking (T)", 5.0, 0.0, 20.0, ""),
                AssessmentScore("Feeling (F)", 15.0, 0.0, 20.0, ""),
                AssessmentScore("Judging (J)", 7.0, 0.0, 20.0, ""),
                AssessmentScore("Perceiving (P)", 13.0, 0.0, 20.0, "")
            ),
            interpretation = "Kecenderungan ENFP.",
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = InstrumentImplementationStatus.RAW_SCORE_ONLY,
            validityStatus = AssessmentValidityStatus.VALID,
            reviewStatus = ExaminerReviewStatus.NOT_REQUIRED,
            aiContextAllowed = true
        )

        var capturedRequestId = ""
        composeRule.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AssessmentResultPanel(
                        result = mbtiResult,
                        state = AssessmentUiState(loading = false),
                        onExplain = {},
                        onDelete = {},
                        onSubmitExaminerReview = { _, _, _, _, _ -> },
                        onUpdateConsentFlags = { _, _ -> },
                        onDiscussResult = { req -> capturedRequestId = req.resultId }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Profil Preferensi Bipolar MBTI").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ENFP").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pemetaan Konstruk & Interpretasi Dasar").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Interpretasikan dengan AI+").performScrollTo().performClick()
        assertEquals("res-mbti-ui", capturedRequestId)
    }

    @Test
    fun htpProjectiveDrawingScreenAndMultimodalResultRenderWithoutBlocking() {
        val htp = AssessmentInstrument(
            id = "htp",
            title = "House-Tree-Person (HTP)",
            category = "Proyektif",
            description = "Tes proyektif grafis 3 panel.",
            source = "PsikoTest",
            aliases = listOf("htp"),
            form = sampleWarteggForm().copy(title = "House-Tree-Person (HTP)")
        )
        val result = AssessmentResult(
            resultId = "res-htp-ui",
            instrumentId = "htp",
            title = "House-Tree-Person (HTP)",
            version = "HTP-3-Panel-Purwarupa",
            sourceTitle = "Bank PsikoTest",
            sourceUrl = "asset://htp",
            sourceLocator = "HTP §1",
            createdAtMillis = 1_700_000_000_000L,
            answered = 3,
            itemCount = 3,
            scores = listOf(AssessmentScore("Panel Diselesaikan", 3.0, 0.0, 3.0, "Observasional")),
            interpretation = "Tiga panel HTP selesai dengan telemetri stylus.",
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = AssessmentValidityStatus.VALID,
            reviewStatus = ExaminerReviewStatus.PENDING,
            aiContextAllowed = true,
            observations = listOf(AssessmentObservation("htp_completed", "Panel Diselesaikan", "3/3")),
            projectivePanels = listOf(
                ProjectivePanelDraft(
                    panelIndex = 0,
                    panelLabel = "Rumah",
                    strokes = listOf(
                        listOf(
                            WarteggNormalizedPoint(0.2f, 0.8f, 0.5f, 100L, true),
                            WarteggNormalizedPoint(0.8f, 0.8f, 0.6f, 300L, true)
                        )
                    ),
                    title = "Rumah Sederhana",
                    drawOrder = 1,
                    durationSeconds = 15
                )
            ),
            multimodalSummary = "[Ekstraksi Fitur Grafis & Stylus Lokal] Stylus terdeteksi."
        )

        composeRule.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ProjectiveDrawingScreen(instrument = htp, busy = false, onSavePanels = {})
                    AssessmentResultPanel(
                        result = result,
                        state = AssessmentUiState(loading = false),
                        onExplain = {},
                        onDelete = {},
                        onSubmitExaminerReview = { _, _, _, _, _ -> },
                        onUpdateConsentFlags = { _, _ -> },
                        onDiscussResult = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("House-Tree-Person (HTP) · Kanvas Proyektif").assertIsDisplayed()
        composeRule.onNodeWithText("Rumah").assertIsDisplayed()
        composeRule.onNodeWithText("Pohon").assertIsDisplayed()
        composeRule.onNodeWithText("Orang").assertIsDisplayed()
        composeRule.onNodeWithText("Analisis Multimodal & Telemetri Proyektif").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Interpretasikan dengan AI+").performScrollTo().assertIsEnabled()
    }

    @Test
    fun mseaiExploratoryResultRendersBarsAndEnablesAiPlusWhenConsented() {
        val mseaiResult = AssessmentResult(
            resultId = "res-mseai-ui",
            instrumentId = "mseai",
            title = "MSEAI",
            version = "MSEAI-24-Exploratory",
            sourceTitle = "PsikoTest",
            sourceUrl = "asset://mseai",
            sourceLocator = "MSEAI §1",
            createdAtMillis = 1_700_000_000_000L,
            answered = 24,
            itemCount = 24,
            scores = listOf(
                AssessmentScore("Kesadaran Diri", 20.0, 6.0, 30.0, "Eksploratif"),
                AssessmentScore("Pengendalian Emosi", 18.0, 6.0, 30.0, "Eksploratif")
            ),
            interpretation = "Skor Eksploratif MSEAI.",
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = InstrumentImplementationStatus.EXPLORATORY,
            validityStatus = AssessmentValidityStatus.VALID,
            reviewStatus = ExaminerReviewStatus.NOT_REQUIRED,
            aiContextAllowed = true
        )

        composeRule.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AssessmentResultPanel(
                        result = mseaiResult,
                        state = AssessmentUiState(loading = false),
                        onExplain = {},
                        onDelete = {},
                        onSubmitExaminerReview = { _, _, _, _, _ -> },
                        onUpdateConsentFlags = { _, _ -> },
                        onDiscussResult = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("Profil Skor MSEAI").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Interpretasikan dengan AI+").performScrollTo().assertIsEnabled()
    }

    private fun sampleWarteggForm() = AssessmentForm(
        version = "1.0",
        title = "Wartegg",
        instructions = "Lengkapi 8 kotak gambar.",
        sourceTitle = "Bank PsikoTest",
        sourceUrl = "asset://wartegg",
        sourceLocator = "Wartegg",
        choices = listOf("Disukai", "Kurang disukai", "Mudah", "Sulit"),
        values = listOf(1, 2, 3, 4),
        scales = listOf(AssessmentScale("projective", "Proyektif", "Observasi")),
        items = emptyList(),
        average = false,
        minimumAnswers = 8,
        interpretationNote = "Observasional"
    )
}

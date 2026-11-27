package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.data.repository.LocalSearchRepository
import com.stressdetector.mobile.domain.assessment.AssessmentDiscussion
import com.stressdetector.mobile.domain.engine.AssetPromptAssetProvider
import com.stressdetector.mobile.domain.engine.CounselorGenerationRequest
import com.stressdetector.mobile.domain.engine.LlmGenerationState
import com.stressdetector.mobile.domain.engine.LlmReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import com.stressdetector.mobile.ui.StressDetectorTheme
import com.stressdetector.mobile.ui.viewmodels.AssessmentViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssessmentScreenCatalogSearchTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: StressDatabase
    private lateinit var viewModel: AssessmentViewModel

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, StressDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val assessmentRepo = AssessmentRepository(context) {
            error("Not needed for catalog search test")
        }
        val searchRepo = LocalSearchRepository(assessmentRepo, database.searchHistoryDao())
        val stubEngine = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.ModelMissing
            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = emptyFlow()
        }
        val discussion = AssessmentDiscussion(
            engine = stubEngine,
            prompts = AssetPromptAssetProvider(context),
            consent = { false }
        )
        viewModel = AssessmentViewModel(
            id = null,
            repository = assessmentRepo,
            discussion = discussion,
            searchRepository = searchRepo
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bankPsikotesAndMateriKonselingSearchHistoryAutocompleteAndCategorySeparationWork() {
        compose.setContent {
            StressDetectorTheme {
                AssessmentScreen(
                    isCatalog = true,
                    viewModel = viewModel,
                    onOpenInstrument = {},
                    onBack = {}
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                compose.onNodeWithText("Bank Psikotes (40)").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }

        // Static Rekomendasi is gone
        compose.onNodeWithText("Rekomendasi:", substring = true).assertDoesNotExist()

        // Search wart -> select Wartegg suggestion -> autofills & saves history
        val searchField = compose.onNodeWithText("Cari psikotes (contoh: Wartegg, MMPI, TIU 6)...")
        searchField.performClick()
        searchField.performTextInput("wart")

        compose.waitUntil(timeoutMillis = 3_000) {
            runCatching {
                compose.onNodeWithContentDescription("Saran Wartegg, Proyektif. Ketuk untuk mengisi.").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithContentDescription("Saran Wartegg, Proyektif. Ketuk untuk mengisi.").performClick()

        compose.onNodeWithText("Ditemukan 1 dari 40 psikotes").assertIsDisplayed()

        // Clear query restores full catalog & preserves history
        compose.onNodeWithContentDescription("Hapus pencarian").performClick()
        compose.onNodeWithText("Ditemukan 1 dari 40 psikotes").assertDoesNotExist()

        // Focus empty field shows Pencarian Terakhir with Wartegg chip
        searchField.performClick()
        compose.onNodeWithText("Pencarian Terakhir").assertIsDisplayed()
        compose.onNodeWithText("Wartegg").assertIsDisplayed()

        // Switch to Materi Konseling tab: must not show Bank Psikotes history or redundant intro card
        compose.onNodeWithText("Materi Konseling (7)").performClick()
        compose.onNodeWithText("Bank Pengetahuan & Kurikulum Konseling").assertDoesNotExist()
        val counselingSearch = compose.onNodeWithText("Cari materi konseling (contoh: CBT, etika, mikro)...")
        counselingSearch.performClick()
        compose.onNodeWithText("Belum ada riwayat pencarian untuk Materi Konseling.").assertIsDisplayed()

        counselingSearch.performTextInput("konsel")
        compose.waitUntil(timeoutMillis = 3_000) {
            runCatching {
                compose.onNodeWithText("Ditemukan", substring = true).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithContentDescription("Hapus pencarian").performClick()
        compose.onNodeWithText("Dasar, Asas & Kode Etik BK").assertIsDisplayed()
    }

    @Test
    fun tabletWidthRendersWithoutCrashOrOverflow() {
        compose.setContent {
            StressDetectorTheme {
                Box(Modifier.width(840.dp)) {
                    AssessmentScreen(
                        isCatalog = true,
                        viewModel = viewModel,
                        onOpenInstrument = {},
                        onBack = {}
                    )
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                compose.onNodeWithText("Bank Psikotes (40)").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithText("Materi Konseling (7)").assertIsDisplayed()
    }
}

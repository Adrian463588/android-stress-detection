package com.stressdetector.mobile.domain.assessment

import java.io.File
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmyAlphaDrawingAndRecognitionTest {

    private val catalogFile = File("src/main/canonicalAssets/ai/psychometrics/catalog.json")
    private val catalog: List<AssessmentInstrument> by lazy {
        if (catalogFile.exists()) {
            Gson().fromJson(catalogFile.readText(), Array<AssessmentInstrument>::class.java).toList()
        } else {
            emptyList()
        }
    }

    @Test
    fun givenItem1_whenStrokesInCircle2And5_thenEvaluatedCorrect() {
        // Circle 2 is around x: 0.35, Circle 5 is around x: 0.80
        val strokeCircle2 = listOf(
            ArmyAlphaPoint(320f, 280f),
            ArmyAlphaPoint(360f, 320f),
            ArmyAlphaPoint(340f, 300f)
        )
        val strokeCircle5 = listOf(
            ArmyAlphaPoint(780f, 280f),
            ArmyAlphaPoint(820f, 320f),
            ArmyAlphaPoint(800f, 300f)
        )

        val drawing = ArmyAlphaItemDrawing(
            itemIndex = 0,
            strokes = listOf(strokeCircle2, strokeCircle5),
            canvasWidth = 1000f,
            canvasHeight = 600f
        )

        val result = ArmyAlphaRecognitionEngine.evaluateItem(drawing)
        assertEquals(ArmyAlphaItemStatus.CORRECT, result.status)
        assertEquals(1, result.score)
        assertTrue(result.transcription.contains("BENAR"))
    }

    @Test
    fun givenItem1_whenOnlyCircle2Marked_thenEvaluatedIncorrect() {
        val strokeCircle2 = listOf(
            ArmyAlphaPoint(320f, 280f),
            ArmyAlphaPoint(360f, 320f),
            ArmyAlphaPoint(340f, 300f)
        )
        val drawing = ArmyAlphaItemDrawing(
            itemIndex = 0,
            strokes = listOf(strokeCircle2),
            canvasWidth = 1000f,
            canvasHeight = 600f
        )

        val result = ArmyAlphaRecognitionEngine.evaluateItem(drawing)
        assertEquals(ArmyAlphaItemStatus.INCORRECT, result.status)
        assertEquals(0, result.score)
        assertTrue(result.transcription.contains("SALAH"))
    }

    @Test
    fun givenItem2_whenCurvedLineUnder34AndOver56_thenEvaluatedCorrect() {
        // Line traversing from circle 1 (x: 0.15) to circle 9 (x: 0.85)
        // Under 3-4 (x ~ 0.40, y > 0.50 => 360f / 600f = 0.60)
        // Over 5-6 (x ~ 0.60, y < 0.50 => 200f / 600f = 0.33)
        val curvedStroke = listOf(
            ArmyAlphaPoint(150f, 300f),
            ArmyAlphaPoint(400f, 400f), // under 3-4
            ArmyAlphaPoint(600f, 180f), // over 5-6
            ArmyAlphaPoint(850f, 300f)  // end at circle 9
        )

        val drawing = ArmyAlphaItemDrawing(
            itemIndex = 1,
            strokes = listOf(curvedStroke),
            canvasWidth = 1000f,
            canvasHeight = 600f
        )

        val result = ArmyAlphaRecognitionEngine.evaluateItem(drawing)
        assertEquals(ArmyAlphaItemStatus.CORRECT, result.status)
        assertEquals(1, result.score)
    }

    @Test
    fun givenItem3_whenStrokeInCircle1_thenEvaluatedCorrect() {
        val strokeCircle1 = listOf(
            ArmyAlphaPoint(250f, 300f),
            ArmyAlphaPoint(260f, 320f),
            ArmyAlphaPoint(270f, 340f)
        )
        val drawing = ArmyAlphaItemDrawing(
            itemIndex = 2,
            strokes = listOf(strokeCircle1),
            canvasWidth = 1000f,
            canvasHeight = 600f
        )

        val result = ArmyAlphaRecognitionEngine.evaluateItem(drawing)
        assertEquals(ArmyAlphaItemStatus.CORRECT, result.status)
        assertEquals(1, result.score)
    }

    @Test
    fun givenFull12ItemsSession_whenEvaluated_thenCalculatesScoresAndLpsp3UiScale() {
        val drawings = mutableMapOf<Int, ArmyAlphaItemDrawing>()

        // Generate correct drawings for all 12 items
        drawings[0] = ArmyAlphaItemDrawing(0, listOf(
            listOf(ArmyAlphaPoint(350f, 300f, isStylus = true), ArmyAlphaPoint(360f, 310f, isStylus = true), ArmyAlphaPoint(370f, 320f, isStylus = true)),
            listOf(ArmyAlphaPoint(800f, 300f, isStylus = true), ArmyAlphaPoint(810f, 310f, isStylus = true), ArmyAlphaPoint(820f, 320f, isStylus = true))
        ))
        drawings[1] = ArmyAlphaItemDrawing(1, listOf(
            listOf(ArmyAlphaPoint(150f, 300f, isStylus = true), ArmyAlphaPoint(400f, 400f, isStylus = true), ArmyAlphaPoint(600f, 180f, isStylus = true), ArmyAlphaPoint(850f, 300f, isStylus = true))
        ))
        drawings[2] = ArmyAlphaItemDrawing(2, listOf(
            listOf(ArmyAlphaPoint(250f, 300f, isStylus = true), ArmyAlphaPoint(260f, 320f, isStylus = true), ArmyAlphaPoint(270f, 330f, isStylus = true))
        ))
        drawings[3] = ArmyAlphaItemDrawing(3, listOf(
            listOf(ArmyAlphaPoint(500f, 300f, isStylus = true), ArmyAlphaPoint(510f, 310f, isStylus = true), ArmyAlphaPoint(520f, 320f, isStylus = true)),
            listOf(ArmyAlphaPoint(800f, 300f, isStylus = true), ArmyAlphaPoint(810f, 310f, isStylus = true), ArmyAlphaPoint(820f, 320f, isStylus = true))
        ))
        drawings[4] = ArmyAlphaItemDrawing(4, listOf(
            listOf(ArmyAlphaPoint(500f, 300f, isStylus = true), ArmyAlphaPoint(510f, 310f, isStylus = true), ArmyAlphaPoint(520f, 320f, isStylus = true)),
            listOf(ArmyAlphaPoint(500f, 100f, isStylus = true), ArmyAlphaPoint(510f, 110f, isStylus = true), ArmyAlphaPoint(520f, 120f, isStylus = true))
        ))
        drawings[5] = ArmyAlphaItemDrawing(5, listOf(
            listOf(ArmyAlphaPoint(250f, 500f, isStylus = true), ArmyAlphaPoint(260f, 510f, isStylus = true), ArmyAlphaPoint(270f, 520f, isStylus = true)),
            listOf(ArmyAlphaPoint(500f, 300f, isStylus = true), ArmyAlphaPoint(510f, 310f, isStylus = true), ArmyAlphaPoint(520f, 320f, isStylus = true))
        ))
        drawings[6] = ArmyAlphaItemDrawing(6, listOf(
            listOf(ArmyAlphaPoint(300f, 300f, isStylus = true), ArmyAlphaPoint(310f, 310f, isStylus = true), ArmyAlphaPoint(320f, 320f, isStylus = true)),
            listOf(ArmyAlphaPoint(650f, 300f, isStylus = true), ArmyAlphaPoint(660f, 310f, isStylus = true), ArmyAlphaPoint(670f, 320f, isStylus = true))
        ))
        drawings[7] = ArmyAlphaItemDrawing(7, listOf(
            listOf(ArmyAlphaPoint(250f, 300f, isStylus = true), ArmyAlphaPoint(300f, 300f, isStylus = true), ArmyAlphaPoint(500f, 300f, isStylus = true), ArmyAlphaPoint(750f, 300f, isStylus = true), ArmyAlphaPoint(800f, 300f, isStylus = true), ArmyAlphaPoint(850f, 300f, isStylus = true), ArmyAlphaPoint(550f, 310f, isStylus = true), ArmyAlphaPoint(600f, 310f, isStylus = true))
        ))
        drawings[8] = ArmyAlphaItemDrawing(8, listOf(
            listOf(ArmyAlphaPoint(300f, 300f, isStylus = true), ArmyAlphaPoint(310f, 310f, isStylus = true), ArmyAlphaPoint(320f, 320f, isStylus = true)),
            listOf(ArmyAlphaPoint(600f, 300f, isStylus = true), ArmyAlphaPoint(610f, 310f, isStylus = true), ArmyAlphaPoint(620f, 320f, isStylus = true))
        ))
        drawings[9] = ArmyAlphaItemDrawing(9, listOf(
            listOf(ArmyAlphaPoint(200f, 300f, isStylus = true), ArmyAlphaPoint(210f, 310f, isStylus = true), ArmyAlphaPoint(220f, 320f, isStylus = true)),
            listOf(ArmyAlphaPoint(750f, 300f, isStylus = true), ArmyAlphaPoint(760f, 310f, isStylus = true), ArmyAlphaPoint(770f, 320f, isStylus = true))
        ))
        drawings[10] = ArmyAlphaItemDrawing(10, listOf(
            listOf(ArmyAlphaPoint(300f, 300f, isStylus = true), ArmyAlphaPoint(310f, 310f, isStylus = true), ArmyAlphaPoint(320f, 320f, isStylus = true), ArmyAlphaPoint(330f, 330f, isStylus = true)),
            listOf(ArmyAlphaPoint(600f, 300f, isStylus = true), ArmyAlphaPoint(610f, 310f, isStylus = true), ArmyAlphaPoint(620f, 320f, isStylus = true), ArmyAlphaPoint(630f, 330f, isStylus = true))
        ))
        drawings[11] = ArmyAlphaItemDrawing(11, listOf(
            listOf(ArmyAlphaPoint(420f, 300f, isStylus = true), ArmyAlphaPoint(430f, 310f, isStylus = true), ArmyAlphaPoint(440f, 320f, isStylus = true))
        ))

        val summary = ArmyAlphaRecognitionEngine.evaluateSession(drawings)
        assertEquals(12, summary.totalCorrect)
        assertEquals(5, summary.scale)
        assertEquals("Baik Sekali (Skala 5)", summary.scaleLabel)
        assertEquals(7, summary.dirCorrect)
        assertEquals(5, summary.speedCorrect)
        assertTrue(summary.inputMethod.contains("Stylus"))
        assertTrue(summary.fullTranscriptionText.contains("12 / 12"))
    }

    @Test
    fun givenCatalog_whenArmyAlphaInspected_thenFormHasNoRadioChoices() {
        val armyAlpha = catalog.firstOrNull { it.id == "army_alpha" } ?: return
        val form = requireNotNull(armyAlpha.form)
        assertEquals(12, form.items.size)
        // Ensure none of the 12 items contain multiple choice choices
        form.items.forEach { item ->
            assertTrue(item.choices.isNullOrEmpty())
            assertTrue(item.imageAssetPath?.endsWith(".png") == true)
        }
    }

    @Test
    fun givenAssessmentEnricher_whenEnrichingArmyAlpha_thenIncludesFullMultimodalTranscription() {
        val form = catalog.firstOrNull { it.id == "army_alpha" }?.form ?: return
        val answers = (0 until 12).associateWith { if (it < 10) 0 else 1 } // 10 correct, 2 incorrect -> Scale 5
        val (scores, interp) = AssessmentEnrichers.enrich("army_alpha", form, answers, emptyList())

        val totalScore = scores.first { it.label.contains("Total Benar") }
        val dirScore = scores.first { it.label.contains("Following Commands") }

        assertEquals(10.0, totalScore.value, 0.001)
        assertEquals(6.0, dirScore.value, 0.001)
        assertTrue(interp.contains("10/12"))
        assertTrue(interp.contains("Evaluasi coretan digital"))
        assertTrue(interp.contains("Ringkasan Deteksi per Butir"))
        assertTrue(interp.contains("Butir 1: Benar (1)"))
        assertTrue(interp.contains("Butir 11: Belum Tepat (0)"))
    }
}

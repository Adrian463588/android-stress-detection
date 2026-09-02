package com.stressdetector.mobile

import ai.onnxruntime.OnnxMap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.ml.FeatureSchemaTestHelper
import com.stressdetector.mobile.signal.PpgFeatureExtractor
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PpgPreprocessingContract
import com.stressdetector.mobile.signal.WearablePpgWindowPreprocessor
import com.stressdetector.shared.PpgFilterContract
import com.stressdetector.shared.PpgType
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM integration tests for the full ONNX inference pipeline.
 *
 * Tests in this class exercise:
 *  1. Model output type introspection (output type logging)
 *  2. Feature vector variability: different signals → different features → different probabilities
 */
class OnnxTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a synthetic PPG WindowSnapshot with a sinusoidal heart-rate signal.
     */
    private fun buildWindow(
        type: PpgType,
        heartRateBpm: Double,
        seconds: Int = 180,
        amplitudeScale: Double = 1.0,
        noiseLevel: Int = 0
    ): WindowSnapshot {
        val count = type.samplingRateHz * seconds
        val intervalMs = 1000L / type.samplingRateHz
        val start = System.currentTimeMillis() - seconds * 1000L
        val timestamps = LongArray(count) { start + it * intervalMs }
        val freqHz = heartRateBpm / 60.0
        val rng = java.util.Random(42L + heartRateBpm.toLong())
        val values = IntArray(count) { index ->
            val t = index.toDouble() / type.samplingRateHz
            val pulse = sin(2.0 * PI * freqHz * t)
            val harmonic = 0.35 * sin(4.0 * PI * freqHz * t)
            val noise = if (noiseLevel > 0) rng.nextInt(noiseLevel * 2) - noiseLevel else 0
            (
                (100_000 * amplitudeScale) + (4_000 * amplitudeScale * pulse) +
                    (1_000 * amplitudeScale * harmonic) + noise
                ).toInt()
        }
        return WindowSnapshot(type, timestamps, values)
    }

    // ── Test 1: output type introspection ─────────────────────────────────────

    @Test
    fun testOnnxOutput() {
        val env = OrtEnvironment.getEnvironment()
        val modelFile = File("../../data/artifacts/export/stress_ppg_green_model.onnx")
        if (!modelFile.exists()) {
            println("[SKIP] Model not found at: ${modelFile.absolutePath}")
            return
        }
        val session = env.createSession(modelFile.readBytes())
        val inputName = session.inputNames.first()
        val vector = FloatArray(99) { 0.5f }
        OnnxTensor.createTensor(env, arrayOf(vector)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                for (entry in result) {
                    val raw = entry.value
                    val value = raw.value
                    println("Output: name=${entry.key}, OnnxValue type=${raw.javaClass.simpleName}, .value type=${value?.javaClass?.name}")
                    if (value is List<*>) {
                        val first = value.firstOrNull()
                        println("  List[0] type: ${first?.javaClass?.name}")
                        if (first is OnnxMap) {
                            val mapVal = first.value
                            println("  OnnxMap.value type: ${mapVal?.javaClass?.name}")
                            if (mapVal is Map<*, *>) {
                                mapVal.entries.forEach { (k, v) ->
                                    val innerVal = if (v is OnnxValue) v.value else v
                                    println(
                                        "    key=$k(${k?.javaClass?.simpleName}), tensorVal type=${innerVal?.javaClass?.name}, content=${
                                            when (innerVal) {
                                                is Array<*> -> innerVal.map { r -> if (r is FloatArray) r.toList() else r }
                                                is FloatArray -> innerVal.toList()
                                                else -> innerVal
                                            }
                                        }"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Test 2: feature vector + probability variability ──────────────────────

    /**
     * Verifies end-to-end: two physiologically different windows →
     *   (a) different feature vectors, AND
     *   (b) different ONNX output probabilities.
     *
     * If (b) fails but (a) passes → parsing bug in OnnxStressPredictor.
     * If (a) fails → feature extraction or schema key mismatch.
     */
    @Test
    fun testModelOutputsVaryWithInputs() {
        listOf(PpgType.GREEN, PpgType.IR).forEach { type ->
            val modelFile = File("../../data/artifacts/export/stress_${type.assetSuffix}_model.onnx")
            val featureFile = File("src/main/assets/selected_features_${type.assetSuffix}.json")

            if (!modelFile.exists() || !featureFile.exists()) {
                println("[SKIP] Missing assets for $type — model=${modelFile.exists()}, features=${featureFile.exists()}")
                return@forEach
            }

            val extractor = PpgFeatureExtractor()
            val preprocessor = WearablePpgWindowPreprocessor(
                PpgPreprocessingContract(filterContract = PpgFilterContract.forChannel(type.assetSuffix))
            )
            val featureNames = FeatureSchemaTestHelper.loadNames(featureFile)

            // Contrasting signals: relaxed (low HR, smooth) vs stressed (high HR, noisy)
            val windowLow = buildWindow(type, heartRateBpm = 55.0, amplitudeScale = 0.8)
            val windowHigh = buildWindow(type, heartRateBpm = 105.0, amplitudeScale = 1.5, noiseLevel = 200)

            val featuresLow = extractor.extract(
                requireNotNull((preprocessor.prepare(windowLow) as? PpgPreprocessResult.Ready)?.window)
            )
            val featuresHigh = extractor.extract(
                requireNotNull((preprocessor.prepare(windowHigh) as? PpgPreprocessResult.Ready)?.window)
            )

            // ── (a) Feature vectors should differ ─────────────────────────────
            val vectorLow = featureNames.map { featuresLow.values[it] ?: 0.0f }.toFloatArray()
            val vectorHigh = featureNames.map { featuresHigh.values[it] ?: 0.0f }.toFloatArray()

            val mismatch = vectorLow.indices.count { i -> abs(vectorLow[i] - vectorHigh[i]) > 1e-4f }
            println("[$type] Feature mismatches: $mismatch / ${featureNames.size}")
            assertTrue("[$type] Feature vectors are identical!", mismatch > 0)

            val zeroLow = vectorLow.count { it == 0f }
            val zeroHigh = vectorHigh.count { it == 0f }
            println("[$type] Zero features — low: $zeroLow, high: $zeroHigh (of ${featureNames.size})")
            assertFalse("[$type] >50% of low features are zero — schema key mismatch", zeroLow > featureNames.size / 2)
            assertFalse("[$type] >50% of high features are zero — schema key mismatch", zeroHigh > featureNames.size / 2)

            // ── (b) ONNX probabilities should differ ──────────────────────────
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelFile.readBytes())
            val inputName = session.inputNames.first()

            fun infer(vec: FloatArray): Float {
                val safe = vec.map { if (it.isNaN()) 0f else it }.toFloatArray()
                OnnxTensor.createTensor(env, arrayOf(safe)).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        // Iterate backward: probability output is last in skl2onnx exports
                        for (i in result.size() - 1 downTo 0) {
                            val raw = result.get(i).value
                            val prob = extractProbability(raw)
                            if (prob != null) return prob.coerceIn(0f, 1f)
                        }
                    }
                }
                return Float.NaN
            }

            val probLow = infer(vectorLow)
            val probHigh = infer(vectorHigh)

            println("[$type] stress prob — low: ${"%.4f".format(probLow)}, high: ${"%.4f".format(probHigh)}")

            assertFalse("[$type] probLow is NaN — ONNX parsing failed", probLow.isNaN())
            assertFalse("[$type] probHigh is NaN — ONNX parsing failed", probHigh.isNaN())
            assertNotEquals("[$type] Both probs identical — model output is invariant!", probLow, probHigh, 1e-4f)

            session.close()
        }
    }

    // ── Probability extractor (mirrors fixed OnnxStressPredictor logic) ───────

    /**
     * Mirrors the dispatch logic in the patched OnnxStressPredictor, including OnnxMap handling.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractProbability(value: Any?): Float? = when (value) {
        is OnnxMap -> extractFromOnnxMap(value)
        is List<*> -> extractProbability(value.firstOrNull())
        is Map<*, *> -> extractFromMap(value as Map<Any?, Any?>)
        is FloatArray -> if (value.size >= 2) value[1] else value.getOrNull(0)
        is DoubleArray -> if (value.size >= 2) value[1].toFloat() else value.getOrNull(0)?.toFloat()
        is OnnxValue -> extractProbability(runCatching { value.value }.getOrNull())
        is Array<*> -> extractProbability(value.firstOrNull())
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFromOnnxMap(onnxMap: OnnxMap): Float? {
        val inner = runCatching { onnxMap.value }.getOrNull()
        return when (inner) {
            is Map<*, *> -> extractFromMap(inner as Map<Any?, Any?>)
            else -> extractProbability(inner)
        }
    }

    private fun extractFromMap(map: Map<Any?, Any?>): Float? {
        // Try Stress key 1L first, then fall back to Relax key 0L (invert)
        for (key in listOf(1L, 1, "1")) {
            val v = map[key] ?: continue
            val f = extractScalar(v)
            if (f != null) return f
        }
        for (key in listOf(0L, 0, "0")) {
            val v = map[key] ?: continue
            val f = extractScalar(v)
            if (f != null) return (1f - f).coerceIn(0f, 1f)
        }
        // Ordered 2-entry map [relax, stress]
        val vals = map.values.mapNotNull { extractScalar(it) }
        if (vals.size == 2) return vals[1]
        return null
    }

    /**
     * Extracts a scalar Float from an OnnxTensor (float[][]) or a plain numeric.
     * OnnxTensor from map values has shape [1, 1]: float[0][0] = the class probability.
     */
    private fun extractScalar(value: Any?): Float? = when {
        value == null -> null
        value is Float -> value
        value is Double -> value.toFloat()
        value is Number -> value.toFloat()
        value is OnnxValue -> extractScalar(runCatching { value.value }.getOrNull())
        value is Array<*> && value.isNotEmpty() && value[0] is FloatArray ->
            (value[0] as FloatArray).getOrNull(0)
        value is Array<*> && value.isNotEmpty() && value[0] is DoubleArray ->
            (value[0] as DoubleArray).getOrNull(0)?.toFloat()
        value is Array<*> && value.isNotEmpty() -> extractScalar(value[0])
        value is FloatArray && value.isNotEmpty() -> value[0]
        value is DoubleArray && value.isNotEmpty() -> value[0].toFloat()
        else -> null
    }
}

package com.stressdetector.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Small testable boundary around the native ONNX session. */
interface StressClassifier : AutoCloseable {
    fun predict(values: FloatArray): Float
}

class OnnxStressClassifier(
    private val resources: ModelResources,
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
) : StressClassifier {
    private val options =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
    private val session = environment.createSession(resources.modelBytes, options)

    /**
     * Runs inference on a single named input tensor.
     *
     * Requires the manifest to declare exactly one input.
     *
     * @param values Feature vector or waveform samples matching the manifest input shape.
     * @return Stress-class probability in [0, 1], or [Float.NaN] on output parse failure.
     */
    @Synchronized
    override fun predict(values: FloatArray): Float {
        require(resources.manifest.inputs.size == 1) { "named_multi_input_required" }
        return predict(
            mapOf(
                resources.manifest.inputs
                    .single()
                    .name to values
            )
        )
    }

    /**
     * Runs inference with a named map of input tensors.
     *
     * Input keys must match the manifest's declared input names exactly.
     *
     * @param values Map of input name → FloatArray matching each declared shape.
     * @return Stress-class probability in [0, 1], or [Float.NaN] on output parse failure.
     */
    @Synchronized
    fun predict(values: Map<String, FloatArray>): Float {
        val contracts = resources.manifest.inputs
        require(values.keys == contracts.map { it.name }.toSet()) { "input_name_mismatch" }
        val tensors =
            contracts.associate { contract ->
                val input = requireNotNull(values[contract.name])
                val expected = contract.shape.drop(1).fold(1, Int::times)
                require(input.size == expected) { "input_shape_mismatch:${contract.name}" }
                contract.name to
                    OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(input),
                        contract.shape.map(Int::toLong).toLongArray()
                    )
            }
        return try {
            session.run(tensors).use { output ->
                val rawValue =
                    output
                        .get(resources.manifest.output.name)
                        .orElseThrow { IllegalStateException("output_name_not_found") }
                        .value
                val probability = OnnxOutputParser.extractStressProbability(rawValue)
                if (probability.isFinite() && probability in 0f..1f) probability else Float.NaN
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }
}

/** Canonical output parser. Scalar output is already stress probability. */
internal object OnnxOutputParser {
    /**
     * Handles common sklearn/PyTorch ONNX output shapes:
     * scalar sigmoid [N], softmax [1, 2] with [relax, stress], and wrapped sigmoid [1, 1].
     */
    fun extractStressProbability(raw: Any?): Float = when {
        raw is FloatArray && raw.size == 1 -> raw[0]
        raw is Array<*> && raw.size == 1 && raw[0] is FloatArray -> {
            val row = raw[0] as FloatArray
            when (row.size) {
                2 -> row[1]
                1 -> row[0]
                else -> Float.NaN
            }
        }
        else -> Float.NaN
    }
}

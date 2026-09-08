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
                        arrayOf(input)
                    )
            }
        return try {
            session.run(tensors).use { output ->
                require(output.size() == 1) { "unexpected_output_count" }
                val outputValue = output[0]
                val rawValue =
                    if (outputValue is OnnxTensor) {
                        outputValue.value
                    } else {
                        outputValue.value
                    }
                val probability = OnnxOutputParser.extractStressProbability(rawValue)
                if (probability.isFinite() && probability in 0f..1f) {
                    probability
                } else {
                    Float.NaN
                }
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
    fun extractStressProbability(raw: Any?): Float = when (raw) {
        is FloatArray -> fromFloatArray(raw)
        is DoubleArray -> fromDoubleArray(raw)
        is FloatBuffer -> fromFloatBuffer(raw)
        is Array<*> -> fromCollection(raw.asList())
        is List<*> -> fromCollection(raw)
        is Number -> raw.toFloat()
        else -> Float.NaN
    }

    private fun fromFloatArray(values: FloatArray): Float = when (values.size) {
        1 -> values[0]
        2 -> values[1]
        else -> Float.NaN
    }

    private fun fromDoubleArray(values: DoubleArray): Float = when (values.size) {
        1 -> values[0].toFloat()
        2 -> values[1].toFloat()
        else -> Float.NaN
    }

    private fun fromFloatBuffer(buffer: FloatBuffer): Float {
        val copy = buffer.asReadOnlyBuffer()
        return when (copy.remaining()) {
            1 -> copy.get()
            2 -> {
                copy.get()
                copy.get()
            }
            else -> Float.NaN
        }
    }

    private fun fromCollection(values: List<*>): Float = when (values.size) {
        1 -> extractStressProbability(values[0])
        2 -> extractStressProbability(values[1])
        else -> Float.NaN
    }
}

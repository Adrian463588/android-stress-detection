@file:Suppress("MagicNumber")

package com.stressdetector.mobile.signal

import com.stressdetector.shared.PpgFilterContract
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runtime Butterworth SOS designer and zero-phase filter.
 *
 * The previous implementation selected serialized direct-form coefficients
 * only for exactly 25 Hz and 100 Hz.  Production manifests use fractional
 * model rates, so that lookup rejected every valid Watch session.  This class
 * follows scipy's Butterworth -> analog band-pass -> bilinear -> SOS path and
 * accepts the manifest rate without rounding it to a nominal rate.
 */
class ButterworthFilter(private val sosDesigner: ButterworthSosDesigner = ButterworthSosDesigner()) {
    fun filter(signal: DoubleArray, samplingRateHz: Double, channelType: String = "ppg_ir"): DoubleArray =
        filter(signal, samplingRateHz, PpgFilterContract.forChannel(channelType))

    fun filter(signal: DoubleArray, samplingRateHz: Double, contract: PpgFilterContract): DoubleArray {
        contract.requireCompatibleWith(samplingRateHz)
        return SosZeroPhaseFilter(sosDesigner.design(samplingRateHz, contract)).filter(signal)
    }

    fun contract(channelType: String): PpgFilterContract = PpgFilterContract.forChannel(channelType)
}

/** Pure, allocation-bounded filter design.  The production contract is order 4. */
class ButterworthSosDesigner {
    private val designCache = ConcurrentHashMap<DesignKey, Array<DoubleArray>>()

    fun design(samplingRateHz: Double, contract: PpgFilterContract): Array<DoubleArray> {
        contract.requireCompatibleWith(samplingRateHz)
        require(samplingRateHz.isFinite() && samplingRateHz > 0.0) {
            "invalid_ppg_filter_sampling_rate"
        }
        val key = DesignKey(
            samplingRateHz = samplingRateHz,
            algorithm = contract.algorithm,
            order = contract.order,
            lowCutHz = contract.lowCutHz,
            highCutHz = contract.highCutHz,
            designVersion = contract.designVersion,
            fingerprint = contract.fingerprint
        )
        return designCache[key] ?: synchronized(designCache) {
            designCache[key] ?: designUncached(samplingRateHz, contract).also { designed ->
                if (designCache.size >= MAX_DESIGN_CACHE_ENTRIES) {
                    designCache.remove(designCache.keys.firstOrNull())
                }
                designCache[key] = designed
            }
        }
    }

    private fun designUncached(samplingRateHz: Double, contract: PpgFilterContract): Array<DoubleArray> {
        val order = contract.order
        val warpedLow = 4.0 * kotlin.math.tan(Math.PI * contract.lowCutHz / samplingRateHz)
        val warpedHigh = 4.0 * kotlin.math.tan(Math.PI * contract.highCutHz / samplingRateHz)
        val bandwidth = warpedHigh - warpedLow
        val center = sqrt(warpedLow * warpedHigh)
        require(warpedLow > 0.0 && bandwidth > 0.0 && center.isFinite()) {
            "invalid_ppg_filter_warped_cutoffs"
        }

        val prototypePoles = (-(order - 1) until order step 2).map { m ->
            val angle = Math.PI * m.toDouble() / (2.0 * order.toDouble())
            Complex(-cos(angle), -sin(angle))
        }
        val analogPoles = prototypePoles.flatMap { pole ->
            val scaled = pole * (bandwidth / 2.0)
            val root = (scaled * scaled - Complex(center * center, 0.0)).sqrt()
            listOf(scaled + root, scaled - root)
        }

        // The band-pass transform contributes `order` zeros at s=0.  The
        // bilinear transform moves the relative-degree zeros at infinity to
        // z=-1, exactly as scipy.signal.bilinear_zpk does.
        val analogZeros = List(order) { Complex.ZERO }
        val digitalZeros = analogZeros.map(::bilinear).toMutableList().apply {
            repeat(order) { add(Complex(-1.0, 0.0)) }
        }
        val digitalPoles = analogPoles.map(::bilinear)

        var gain = bandwidth.pow(order)
        analogZeros.forEach { zero -> gain *= (Complex(4.0, 0.0) - zero).abs() }
        analogPoles.forEach { pole -> gain /= (Complex(4.0, 0.0) - pole).abs() }
        require(gain.isFinite() && gain > 0.0) { "invalid_ppg_filter_gain" }

        return pairIntoSos(digitalZeros, digitalPoles, gain)
    }

    private data class DesignKey(
        val samplingRateHz: Double,
        val algorithm: String,
        val order: Int,
        val lowCutHz: Double,
        val highCutHz: Double,
        val designVersion: String,
        val fingerprint: String?
    )

    private companion object {
        const val MAX_DESIGN_CACHE_ENTRIES = 8
    }

    private fun bilinear(value: Complex): Complex = (Complex(4.0, 0.0) + value) / (Complex(4.0, 0.0) - value)

    private fun pairIntoSos(zeros: MutableList<Complex>, poles: List<Complex>, gain: Double): Array<DoubleArray> {
        val remainingPoles = poles.toMutableList()
        val pairs = mutableListOf<SectionRoots>()
        while (remainingPoles.isNotEmpty()) {
            val firstIndex = remainingPoles.indices.maxByOrNull { remainingPoles[it].abs() }
                ?: error("ppg_filter_pole_pairing_failed")
            val first = remainingPoles.removeAt(firstIndex)
            val secondIndex = remainingPoles.indices.minByOrNull {
                (remainingPoles[it] - first.conjugate()).abs()
            } ?: error("ppg_filter_pole_pairing_failed")
            val second = remainingPoles.removeAt(secondIndex)

            val firstZeroIndex = zeros.indices.minByOrNull { (zeros[it] - first).abs() }
                ?: error("ppg_filter_zero_pairing_failed")
            val firstZero = zeros.removeAt(firstZeroIndex)
            val secondZeroIndex = zeros.indices.minByOrNull { (zeros[it] - first).abs() }
                ?: error("ppg_filter_zero_pairing_failed")
            val secondZero = zeros.removeAt(secondZeroIndex)
            pairs += SectionRoots(first, second, firstZero, secondZero)
        }

        // scipy assigns the worst (closest-to-unit-circle) pole pair to the
        // last section and applies the total gain to the first section.
        val ordered = pairs.sortedBy { maxOf(it.poleA.abs(), it.poleB.abs()) }
        return Array(ordered.size) { index ->
            val roots = ordered[index]
            val b = polynomial(roots.zeroA, roots.zeroB)
            val a = polynomial(roots.poleA, roots.poleB)
            if (index == 0) {
                doubleArrayOf(b[0] * gain, b[1] * gain, b[2] * gain, 1.0, a[1], a[2])
            } else {
                doubleArrayOf(b[0], b[1], b[2], 1.0, a[1], a[2])
            }
        }
    }

    private fun polynomial(first: Complex, second: Complex): DoubleArray = doubleArrayOf(
        1.0,
        -(first + second).real,
        (first * second).real
    )

    private data class SectionRoots(val poleA: Complex, val poleB: Complex, val zeroA: Complex, val zeroB: Complex)

    private data class Complex(val real: Double, val imaginary: Double) {
        operator fun plus(other: Complex) = Complex(real + other.real, imaginary + other.imaginary)
        operator fun minus(other: Complex) = Complex(real - other.real, imaginary - other.imaginary)
        operator fun times(other: Complex) = Complex(
            real * other.real - imaginary * other.imaginary,
            real * other.imaginary + imaginary * other.real
        )
        operator fun times(scalar: Double) = Complex(real * scalar, imaginary * scalar)
        operator fun div(other: Complex): Complex {
            val denominator = other.real * other.real + other.imaginary * other.imaginary
            return Complex(
                (real * other.real + imaginary * other.imaginary) / denominator,
                (imaginary * other.real - real * other.imaginary) / denominator
            )
        }

        fun conjugate() = Complex(real, -imaginary)
        fun abs() = hypot(real, imaginary)

        fun sqrt(): Complex {
            val radius = abs()
            val rootReal = sqrt((radius + real).coerceAtLeast(0.0) / 2.0)
            val rootImaginary = sqrt((radius - real).coerceAtLeast(0.0) / 2.0)
            return Complex(rootReal, if (imaginary < 0.0) -rootImaginary else rootImaginary)
        }

        companion object {
            val ZERO = Complex(0.0, 0.0)
        }
    }
}

/** scipy.signal.sosfiltfilt-compatible bounded zero-phase application. */
class SosZeroPhaseFilter(private val sections: Array<DoubleArray>) {
    fun filter(signal: DoubleArray): DoubleArray {
        require(signal.all(Double::isFinite)) { "Signal contains non-finite values" }
        val padlen = defaultPadLength()
        require(signal.size > padlen) {
            "Signal too short for filtfilt (length ${signal.size}, padlen $padlen)"
        }

        val padded = oddPad(signal, padlen)
        val zi = steadyStateInitialConditions()
        var forward = sosFilter(padded, zi, padded.first())
        forward.reverse()
        var backward = sosFilter(forward, zi, forward.first())
        backward.reverse()
        return backward.copyOfRange(padlen, padlen + signal.size)
    }

    private fun defaultPadLength(): Int {
        val zerosAtOrigin = sections.count { abs(it[2]) <= 1.0e-15 }
        val polesAtOrigin = sections.count { abs(it[5]) <= 1.0e-15 }
        return 3 * (2 * sections.size + 1 - minOf(zerosAtOrigin, polesAtOrigin))
    }

    private fun oddPad(signal: DoubleArray, padlen: Int): DoubleArray {
        val padded = DoubleArray(signal.size + 2 * padlen)
        val first = signal.first()
        val last = signal.last()
        for (index in 0 until padlen) padded[index] = 2.0 * first - signal[padlen - index]
        signal.copyInto(padded, destinationOffset = padlen)
        for (index in 0 until padlen) {
            padded[padlen + signal.size + index] = 2.0 * last - signal[signal.size - 2 - index]
        }
        return padded
    }

    private fun steadyStateInitialConditions(): Array<DoubleArray> {
        val initial = Array(sections.size) { DoubleArray(2) }
        var scale = 1.0
        sections.forEachIndexed { index, section ->
            val b0 = section[0]
            val b1 = section[1]
            val b2 = section[2]
            val a1 = section[4]
            val a2 = section[5]
            val rhs0 = b1 - a1 * b0
            val rhs1 = b2 - a2 * b0
            val m00 = 1.0 + a1
            val m01 = -1.0
            val m10 = a2
            val m11 = 1.0
            val determinant = m00 * m11 - m01 * m10
            require(abs(determinant) > 1.0e-15) { "ppg_filter_initial_state_singular" }
            initial[index][0] = scale * ((rhs0 * m11 - m01 * rhs1) / determinant)
            initial[index][1] = scale * ((m00 * rhs1 - m10 * rhs0) / determinant)
            scale *= (b0 + b1 + b2) / (1.0 + a1 + a2)
        }
        return initial
    }

    private fun sosFilter(input: DoubleArray, initial: Array<DoubleArray>, inputStart: Double): DoubleArray {
        var output = input
        sections.forEachIndexed { sectionIndex, section ->
            val b0 = section[0]
            val b1 = section[1]
            val b2 = section[2]
            val a1 = section[4]
            val a2 = section[5]
            var z0 = initial[sectionIndex][0] * inputStart
            var z1 = initial[sectionIndex][1] * inputStart
            val filtered = DoubleArray(output.size)
            output.forEachIndexed { sampleIndex, sample ->
                val value = b0 * sample + z0
                z0 = b1 * sample + z1 - a1 * value
                z1 = b2 * sample - a2 * value
                filtered[sampleIndex] = value
            }
            output = filtered
        }
        return output
    }
}

private fun Double.pow(exponent: Int): Double {
    var result = 1.0
    repeat(exponent) { result *= this }
    return result
}

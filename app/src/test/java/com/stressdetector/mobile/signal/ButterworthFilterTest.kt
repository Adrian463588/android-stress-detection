package com.stressdetector.mobile.signal

import com.stressdetector.shared.PpgFilterContract
import org.junit.Assert.assertEquals
import org.junit.Test

class ButterworthFilterTest {

    @Test
    fun testButterworthFilter_100Hz() {
        val filter = ButterworthFilter()
        // A simple step signal to test stability and output length
        val signal = DoubleArray(200) { if (it < 100) 1.0 else 0.0 }
        val filtered = filter.filter(signal, 100.0)

        assert(filtered.size == 200)

        // Assert no NaN or Inf
        for (v in filtered) {
            assert(!v.isNaN() && !v.isInfinite())
        }
    }

    @Test
    fun testButterworthFilter_25Hz() {
        val filter = ButterworthFilter()
        val signal = DoubleArray(100) { if (it < 50) 1.0 else 0.0 }
        val filtered = filter.filter(signal, 25.0)

        assert(filtered.size == 100)

        for (v in filtered) {
            assert(!v.isNaN() && !v.isInfinite())
        }
    }

    @Test
    fun testButterworthFilter_FractionalManifestRates() {
        val filter = ButterworthFilter()
        val signal = DoubleArray(1_000) { index ->
            kotlin.math.sin(index * 0.17) + 0.2 * kotlin.math.sin(index * 0.031)
        }

        val green = filter.filter(signal, 24.916666666666668, PpgFilterContract.forChannel("ppg_green"))
        val ir = filter.filter(signal, 99.67777777777778, PpgFilterContract.forChannel("ppg_ir"))

        assert(green.size == signal.size)
        assert(ir.size == signal.size)
        assert(green.all(Double::isFinite))
        assert(ir.all(Double::isFinite))
    }

    @Test
    fun sosDesignerMatchesSciPyFractionalGreenContract() {
        val actual = ButterworthSosDesigner().design(
            24.916666666666668,
            PpgFilterContract.forChannel("ppg_green")
        )
        val expected = arrayOf(
            doubleArrayOf(0.03370736067112607, 0.06741472134225214, 0.03370736067112607, 1.0, -0.4930911899749052, 0.11500713627202028),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -0.49200967667288859, 0.52732077258820353),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.749988911927906, 0.76929993472980374),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9037621222158168, 0.91958735029639305)
        )
        actual.zip(expected).forEach { (actualSection, expectedSection) ->
            actualSection.zip(expectedSection).forEach { (actualValue, expectedValue) ->
                assertEquals(expectedValue, actualValue, 1.0e-10)
            }
        }
    }

    @Test
    fun sosDesignerMatchesSciPyFractionalIrContract() {
        val actual = ButterworthSosDesigner().design(
            99.67777777777778,
            PpgFilterContract.forChannel("ppg_ir")
        )
        val expected = arrayOf(
            doubleArrayOf(
                0.0018029473274327489,
                0.0036058946548654979,
                0.0018029473274327489,
                1.0,
                -1.2897269695840279,
                0.43448173082726688
            ),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.5106607682892408, 0.71880500885094201),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9373193838590677, 0.93852180391653639),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9772914472949308, 0.97830316834399134)
        )
        actual.zip(expected).forEach { (actualSection, expectedSection) ->
            actualSection.zip(expectedSection).forEach { (actualValue, expectedValue) ->
                assertEquals(expectedValue, actualValue, 1.0e-10)
            }
        }
    }

    @Test
    fun zeroPhaseOutputMatchesSciPyFractionalGreenFixture() {
        val signal = DoubleArray(100) { index ->
            kotlin.math.sin(index * 0.17) + 0.2 * kotlin.math.sin(index * 0.031)
        }
        val actual = ButterworthFilter().filter(
            signal,
            24.916666666666668,
            PpgFilterContract.forChannel("ppg_green")
        )
        val expected = doubleArrayOf(
            0.09321985523846327,
            0.2439095541150466,
            0.3896622725874351,
            0.9374583681328352,
            0.8386242171384892,
            -0.15980944794977908
        )
        intArrayOf(0, 1, 2, 10, 50, 99).forEachIndexed { index, sampleIndex ->
            assertEquals(expected[index], actual[sampleIndex], 1.0e-5)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testButterworthFilter_InvalidHz() {
        val filter = ButterworthFilter()
        val signal = DoubleArray(100)
        // The default IR contract has an 8 Hz high cut; 10 Hz violates Nyquist.
        filter.filter(signal, 10.0, PpgFilterContract.forChannel("ppg_ir"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testButterworthFilter_TooShort() {
        val filter = ButterworthFilter()
        val signal = DoubleArray(10)
        filter.filter(signal, 100.0)
    }
}

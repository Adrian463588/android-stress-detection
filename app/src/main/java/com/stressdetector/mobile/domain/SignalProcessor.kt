package com.stressdetector.mobile.domain

import android.util.Log
import com.stressdetector.mobile.signal.ButterworthFilter
import kotlin.math.sqrt

/**
 * Canonical Kotlin preprocessing contract mirroring the Python pipeline's signal processing steps.
 *
 * All functions are pure, stateless, and operate on [FloatArray]. Internal filtering delegates to
 * [ButterworthFilter] which is a verified port of `scipy.signal.filtfilt` (zero-phase Butterworth).
 *
 * Pre-computed coefficient sets:
 * - **PPG Green**: 4th-order bandpass 0.5–5 Hz @ 25 Hz sampling rate.
 * - **PPG IR**: 4th-order bandpass 0.5–8 Hz @ 100 Hz sampling rate.
 *
 * @see com.stressdetector.mobile.signal.ButterworthFilter
 */
object SignalProcessor {

    private const val TAG = "SignalProcessor"

    /**
     * Applies a zero-phase Butterworth bandpass filter to [signal].
     *
     * The filter is identical to `scipy.signal.butter(order=4, Wn=[low, high], btype='band')`
     * followed by `scipy.signal.filtfilt`, achieving zero group-delay for exact numerical parity
     * with the Python pipeline.
     *
     * Supported (lowcut, highcut, fs) combinations matching the Python pipeline:
     * - Green channel: (0.5f, 5.0f, 25.0f) — ButterworthFilter.GREEN_25HZ.
     * - IR channel:    (0.5f, 8.0f, 100.0f) — ButterworthFilter.IR_100HZ.
     *
     * If an unsupported combination is requested, a warning is logged and the signal is returned
     * unfiltered so inference can still be attempted with a quality-tier downgrade.
     *
     * @param signal    Raw PPG samples.
     * @param lowcut    Lower cutoff frequency in Hz.
     * @param highcut   Upper cutoff frequency in Hz.
     * @param fs        Sampling rate in Hz.
     * @return Filtered signal of the same length, or [signal] unchanged on failure.
     */
    @JvmStatic
    fun bandpassFilter(signal: FloatArray, lowcut: Float, highcut: Float, fs: Float): FloatArray {
        val filter = ButterworthFilter()
        val inputDouble = DoubleArray(signal.size) { signal[it].toDouble() }
        return try {
            val filtered = filter.filter(inputDouble, fs.toDouble())
            FloatArray(filtered.size) { filtered[it].toFloat() }
        } catch (e: IllegalArgumentException) {
            Log.w(
                TAG,
                "bandpassFilter: unsupported parameters (low=$lowcut, high=$highcut, fs=$fs): ${e.message}. " +
                    "Returning unfiltered signal."
            )
            signal
        }
    }

    /**
     * Applies per-signal Z-score normalization: `(x − mean) / std`.
     *
     * Matches `sklearn.preprocessing.StandardScaler` applied to a single 1-D array.
     * If the standard deviation is zero (flat signal), the signal is returned unchanged so that
     * downstream SQA can correctly flag and reject the window.
     *
     * @param signal Input samples.
     * @return Normalized samples of the same length.
     */
    @JvmStatic
    fun zScoreNormalize(signal: FloatArray): FloatArray {
        if (signal.isEmpty()) return signal
        val mean = signal.average().toFloat()
        val variance = signal.sumOf { ((it - mean) * (it - mean)).toDouble() } / signal.size
        val std = sqrt(variance).toFloat()
        if (std == 0f) return signal
        return FloatArray(signal.size) { (signal[it] - mean) / std }
    }

    /**
     * Extracts all overlapping windows from [signal] using a sliding-window approach.
     *
     * Matches the Python pipeline's `window_seconds=180, stride_seconds=10` contract when called
     * with `windowSize = fs * 180` and `strideSize = fs * 10`.
     *
     * Windows that would extend beyond the end of the signal are silently dropped to maintain
     * exact shape contracts expected by the ONNX model.
     *
     * @param signal     Input PPG samples.
     * @param windowSize Number of samples per window (e.g. 180 s × fs Hz).
     * @param strideSize Number of samples to advance between windows (e.g. 10 s × fs Hz).
     * @return Ordered list of non-overlapping windows, each of [windowSize] samples.
     */
    @JvmStatic
    fun extractWindows(signal: FloatArray, windowSize: Int, strideSize: Int): List<FloatArray> {
        require(windowSize > 0) { "windowSize must be positive" }
        require(strideSize > 0) { "strideSize must be positive" }
        if (signal.size < windowSize) return emptyList()
        val windows = mutableListOf<FloatArray>()
        var start = 0
        while (start + windowSize <= signal.size) {
            windows += signal.copyOfRange(start, start + windowSize)
            start += strideSize
        }
        return windows
    }
}

package com.stressdetector.inference

internal fun morphologySupport(peaks: IntArray, troughs: IntArray): Int {
    if (peaks.isEmpty() || troughs.size < 2) return 0

    // Detector outputs are sorted. Advance once through troughs instead of
    // rescanning the full array for every peak. This preserves the original
    // nearest-left/nearest-right semantics while keeping 100 Hz windows
    // bounded when the adaptive detector returns many candidates.
    var troughIndex = 0
    var leftTrough: Int? = null
    var supported = 0
    peaks.forEach { peak ->
        while (troughIndex < troughs.size && troughs[troughIndex] < peak) {
            leftTrough = troughs[troughIndex]
            troughIndex += 1
        }
        val left = leftTrough
        val rightTrough = troughs.getOrNull(troughIndex)
        if (isSupportedPeak(peak, left, rightTrough)) {
            supported += 1
        }
    }
    return supported
}

private fun isSupportedPeak(peak: Int, leftTrough: Int?, rightTrough: Int?): Boolean = when {
    leftTrough == null -> false
    rightTrough == null -> false
    else -> leftTrough < peak && peak < rightTrough
}

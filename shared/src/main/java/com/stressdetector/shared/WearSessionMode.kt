package com.stressdetector.shared

/**
 * Identifies the feature that owns the physical PPG session on the Watch.
 * A Watch can have only one active owner at a time.
 */
enum class WearSessionMode {
    HOME_STRESS,
    VERBATIM_AROUSAL
}

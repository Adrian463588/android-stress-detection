package com.stressdetector.wear.sensor

/** PDSatu-compatible listener for the Samsung individual Green tracker. */
class PpgGreenListener(onSamples: (List<PpgSensorSample>) -> Unit, onError: (String) -> Unit) :
    PpgIndividualListener(PpgTrackerSpec.green, onSamples, onError)

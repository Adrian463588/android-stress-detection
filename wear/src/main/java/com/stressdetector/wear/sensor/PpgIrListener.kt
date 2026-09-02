package com.stressdetector.wear.sensor

/** PDSatu-compatible listener for the Samsung individual IR tracker. */
class PpgIrListener(onSamples: (List<PpgSensorSample>) -> Unit, onError: (String) -> Unit) :
    PpgIndividualListener(PpgTrackerSpec.ir, onSamples, onError)

package com.stressdetector.wear.sensor

/** PDSatu-compatible diagnostic listener for the Samsung individual Red tracker. */
class PpgRedListener(onSamples: (List<PpgSensorSample>) -> Unit, onError: (String) -> Unit) :
    PpgIndividualListener(PpgTrackerSpec.red, onSamples, onError)

package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import com.google.gson.JsonObject

/** Reads only observed native evidence; current transcript edits remain authoritative in Room. */
internal object VerbatimProsodyArchive {
    fun parse(sessionId: String, lines: Sequence<String>): Map<String, VerbatimProsodyEvidence> {
        val gson = Gson()
        val evidence = mutableMapOf<String, VerbatimProsodyEvidence>()
        lines.filter(String::isNotBlank).forEach { line ->
            val row = gson.fromJson(line, JsonObject::class.java)
            require(row["sessionId"]?.asString == sessionId) { "Prosody archive belongs to another session." }
            val native = row["prosodyEvidence"]?.takeUnless { it.isJsonNull }
            if (native != null) {
                require(native.isJsonObject && native.asJsonObject["features"]?.isJsonObject == true) {
                    "Missing native feature map in prosody archive."
                }
                require(row["segmentId"]?.isJsonPrimitive == true) { "Missing segment identity in prosody archive." }
                val id = row["segmentId"].asString
                val observed = gson.fromJson(native, VerbatimProsodyEvidence::class.java)
                require(observed.frameCount >= 0 && observed.voicedFrameCount in 0..observed.frameCount)
                require(observed.features.values.all(Double::isFinite)) { "Non-finite archived openSMILE feature." }
                require(evidence.put(id, observed) == null) { "Duplicate prosody segment in archive." }
            }
        }
        return evidence
    }
}

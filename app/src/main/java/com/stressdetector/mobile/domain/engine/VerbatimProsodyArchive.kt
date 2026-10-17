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

    /** Final native decoder metadata only; absence is legacy, not fabricated DECODED evidence. */
    fun parseDecoderEvidence(sessionId: String, lines: Sequence<String>): Map<String, SpeechDecoderEvidence> {
        require(sessionId.isNotBlank()) { "Missing decoder archive session identity." }
        val gson = Gson()
        val evidence = mutableMapOf<String, SpeechDecoderEvidence>()
        lines.filter(String::isNotBlank).forEach { line ->
            val row = requireNotNull(gson.fromJson(line, JsonObject::class.java)) { "Missing decoder archive row." }
            require(stringField(row, "sessionId") == sessionId) { "Decoder archive belongs to another session." }
            val native = row["decoderEvidence"]?.takeUnless { it.isJsonNull }
            if (native != null) {
                require(native.isJsonObject) { "Decoder evidence must be an object." }
                val id = stringField(row, "segmentId")
                require(id.isNotBlank()) { "Missing decoder segment identity." }
                val observed = decoderEvidence(native.asJsonObject)
                validateDecoderRow(row, observed)
                require(evidence.put(id, observed) == null) { "Duplicate decoder segment in archive." }
            }
        }
        return evidence
    }

    private fun decoderEvidence(value: JsonObject): SpeechDecoderEvidence {
        val observed = SpeechDecoderEvidence(
            wholeUtterance = requireNotNull(decoderResult(value, "wholeUtterance")) { "Missing whole-utterance decoder evidence." },
            crop = decoderResult(value, "crop"),
            precedingPartial = decoderResult(value, "precedingPartial"),
            streamingFinal = decoderResult(value, "streamingFinal"),
            status = SpeechRecognitionStatus.valueOf(stringField(value, "status"))
        )
        val wholeBlank = observed.wholeUtterance.rawText.isBlank()
        val validStatus = when (observed.status) {
            SpeechRecognitionStatus.PARTIAL -> false // A live hypothesis cannot be restored as a final transcript row.
            SpeechRecognitionStatus.DECODED -> !wholeBlank && (observed.crop?.rawText?.isNotBlank() != false)
            SpeechRecognitionStatus.EMPTY_FINAL -> wholeBlank && observed.crop == null
            SpeechRecognitionStatus.EMPTY_CROP -> !wholeBlank && observed.crop?.rawText?.isBlank() == true
            SpeechRecognitionStatus.SPEAKER_SPANS_UNAVAILABLE -> !wholeBlank && observed.crop == null
        }
        require(validStatus) { "Decoder status contradicts raw whole/crop evidence." }
        return observed
    }

    private fun decoderResult(parent: JsonObject, key: String): SpeechDecoderResult? {
        val value = parent[key]?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonObject) { "Decoder result must be an object: $key." }
        val row = value.asJsonObject
        val backend = stringField(row, "backend")
        val revision = stringField(row, "modelRevision")
        val validRevision = when (backend) {
            "whisper_tiny_int8", "zipformer2_transducer" -> modelRevision.matches(revision)
            "omnilingual_ctc" -> modelChecksum.matches(revision)
            else -> false
        }
        require(validRevision) { "Unknown native decoder backend or invalid model revision." }
        val start = offsetField(row, "startOffsetMs")
        val end = offsetField(row, "endOffsetMs")
        require(end > start) { "Decoder PCM bounds must have positive duration." }
        return SpeechDecoderResult(backend, revision, start, end, stringField(row, "rawText"))
    }

    private fun validateDecoderRow(row: JsonObject, observed: SpeechDecoderEvidence) {
        val whole = observed.wholeUtterance
        val selected = observed.crop ?: whole
        require(offsetField(row, "startOffsetMs") == selected.startOffsetMs) { "Decoder start differs from transcript bounds." }
        require(offsetField(row, "endOffsetMs") == selected.endOffsetMs) { "Decoder end differs from transcript bounds." }
        require(stringField(row, "rawText") == selected.rawText) { "Transcript raw text differs from selected decoder evidence." }
        val language = stringField(row, "language")
        require(language == "id" || language == "en") { "Unsupported archived decoder language." }
        val legacyBackend = if (language == "id") "zipformer2_transducer" else "omnilingual_ctc"
        require(whole.backend == "whisper_tiny_int8" || whole.backend == legacyBackend) { "Decoder backend contradicts session language." }
        observed.crop?.let { crop ->
            require(crop.startOffsetMs >= whole.startOffsetMs && crop.endOffsetMs <= whole.endOffsetMs) {
                "Decoder crop falls outside whole-utterance PCM."
            }
            require(crop.backend == whole.backend && crop.modelRevision == whole.modelRevision) { "Crop decoder identity differs." }
        }
        validateDecoderCompanions(observed, language)
    }

    private fun validateDecoderCompanions(observed: SpeechDecoderEvidence, language: String) {
        val whole = observed.wholeUtterance
        observed.precedingPartial?.let { partial ->
            require(partial.startOffsetMs == whole.startOffsetMs && partial.endOffsetMs <= whole.endOffsetMs) {
                "Partial decoder falls outside its whole utterance."
            }
            require(partial.rawText.isNotBlank()) { "A retained preceding hypothesis must contain observed decoder text." }
            val expectedBackend = if (language == "id") "zipformer2_transducer" else whole.backend
            require(partial.backend == expectedBackend) { "Partial decoder contradicts the native language path." }
            if (partial.backend == whole.backend) {
                require(partial.modelRevision == whole.modelRevision) { "Partial decoder model revision differs." }
            }
        }
        observed.streamingFinal?.let { streaming ->
            require(language == "id" && whole.backend == "whisper_tiny_int8" && streaming.backend == "zipformer2_transducer") {
                "Unexpected native streaming final decoder."
            }
            require(streaming.startOffsetMs == whole.startOffsetMs && streaming.endOffsetMs == whole.endOffsetMs) {
                "Streaming final decoder bounds differ from the whole utterance."
            }
            observed.precedingPartial?.let { partial ->
                require(partial.modelRevision == streaming.modelRevision) { "Streaming partial/final model revisions differ." }
            }
        }
    }

    private fun stringField(row: JsonObject, key: String): String {
        val value = row[key]
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) { "Missing or non-string decoder field: $key." }
        return value.asString
    }

    private fun offsetField(row: JsonObject, key: String): Long {
        val value = row[key]
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber) { "Missing or non-numeric decoder offset: $key." }
        val offset = value.asString.toLongOrNull()
        require(offset != null && offset >= 0L) { "Decoder offset must be a non-negative integer: $key." }
        return offset
    }

    private val modelRevision = Regex("[a-fA-F0-9]{40}")
    private val modelChecksum = Regex("sha256:[a-fA-F0-9]{64}")
}

package com.stressdetector.mobile.ml.speech

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.stressdetector.mobile.ml.artifacts.LocalArtifactCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SpeechSttContractTest {
    private val descriptor = SpeechBundleCatalog.from(
        LocalArtifactCatalog.parse(
            listOf(
                File("src/main/canonicalAssets/ai/artifact_catalog.json"),
                File("app/src/main/canonicalAssets/ai/artifact_catalog.json")
            ).first(File::isFile).readText()
        )
    )

    @Test
    fun currentAndExactLegacyVersionsSelectDifferentPinnedFileSets() {
        assertEquals(descriptor.components, validateSpeechSttContract(manifest(), descriptor))
        val legacy = manifest(legacy = true)
        assertNull(legacy.whisperStt)
        val known = validateSpeechSttContract(legacy, descriptor)
        assertEquals(SpeechBundleCatalog.LEGACY_COMPONENT_IDS, known.map { it.id }.toSet())
        assertEquals(18, known.sumOf { it.extractedFiles.size })
        assertEquals(21, descriptor.components.sumOf { it.extractedFiles.size })
    }

    @Test
    fun onlyExactLegacyVersionWithoutWhisperIsAllowed() {
        val current = manifest()
        val legacy = manifest(legacy = true)
        reject(current.copy(whisperStt = null))
        reject(legacy.copy(whisperStt = current.whisperStt))
        reject(legacy.copy(bundleVersion = "sherpa-onnx-1.13.4-omnilingual-ctc-v2-supertonic3"))
        reject(legacy.copy(bundleVersion = "${SpeechBundleCatalog.LEGACY_BUNDLE_VERSION}-other"))
    }

    @Test
    fun whisperManifestSerializesEveryFieldAndRejectsOmittedOrNullFields() {
        val gson = Gson()
        val current = manifest()
        val fields = setOf("encoder", "decoder", "tokens", "revision", "languages", "task")
        val document = gson.toJsonTree(current).asJsonObject
        assertEquals(fields, document.getAsJsonObject("whisper_stt").keySet())
        assertEquals(current, gson.fromJson(document, SpeechBundleManifest::class.java))
        fields.forEach { field ->
            val omitted = document.deepCopy()
            omitted.getAsJsonObject("whisper_stt").remove(field)
            reject(gson.fromJson(omitted, SpeechBundleManifest::class.java))
            val explicitNull = document.deepCopy()
            explicitNull.getAsJsonObject("whisper_stt").add(field, JsonNull.INSTANCE)
            reject(gson.fromJson(explicitNull, SpeechBundleManifest::class.java))
        }
    }

    @Test
    fun whisperRolesRevisionLanguagesAndTaskAreBoundToDescriptor() {
        val current = manifest()
        val whisper = requireNotNull(current.whisperStt)
        listOf(
            whisper.copy(encoder = whisper.decoder, decoder = whisper.encoder),
            whisper.copy(decoder = whisper.tokens),
            whisper.copy(tokens = whisper.encoder),
            whisper.copy(encoder = requireNotNull(current.indonesianStt).encoder),
            whisper.copy(revision = "unapproved-revision"),
            whisper.copy(languages = listOf("en", "id")),
            whisper.copy(languages = listOf("id")),
            whisper.copy(languages = listOf("id", "en", "de")),
            whisper.copy(task = "translate")
        ).forEach { reject(current.copy(whisperStt = it)) }
        reject(current.copy(files = current.files - whisper.tokens))
    }

    @Test
    fun indonesianRolesAndMetadataCannotBeSwappedOrOmitted() {
        val current = manifest()
        val indonesian = requireNotNull(current.indonesianStt)
        listOf(
            indonesian.copy(encoder = indonesian.decoder, decoder = indonesian.encoder),
            indonesian.copy(joiner = indonesian.encoder),
            indonesian.copy(tokens = requireNotNull(current.whisperStt).tokens),
            indonesian.copy(revision = "unapproved-revision"),
            indonesian.copy(language = "en"),
            indonesian.copy(modelType = "omnilingual_ctc")
        ).forEach { reject(current.copy(indonesianStt = it)) }
        reject(current.copy(indonesianStt = null))
        reject(current.copy(files = current.files - indonesian.tokens))
        reject(manifest(legacy = true).copy(indonesianStt = indonesian.copy(encoder = indonesian.decoder)))
    }

    @Test
    fun omnilingualAndDiarizationModelRolesRemainPinned() {
        val current = manifest()
        reject(current.copy(stt = current.stt.copy(model = current.diarization.embedding)))
        reject(current.copy(stt = current.stt.copy(tokens = requireNotNull(current.whisperStt).tokens)))
        reject(current.copy(vad = current.vad.copy(model = current.diarization.segmentation)))
        reject(
            current.copy(
                diarization = current.diarization.copy(
                    segmentation = current.diarization.embedding,
                    embedding = current.diarization.segmentation
                )
            )
        )
    }

    // Contract-only metadata fixtures: no model execution or recognition-quality claim.
    private fun manifest(legacy: Boolean = false): SpeechBundleManifest {
        val components = descriptor.components.filter { !legacy || it.id in SpeechBundleCatalog.LEGACY_COMPONENT_IDS }
        val files = components.flatMap { it.extractedFiles }.associateBy { it.bundlePath }
        val indonesian = requireNotNull(descriptor.indonesianStt)
        val whisper = requireNotNull(descriptor.whisperStt)
        return SpeechBundleManifest(
            schemaVersion = SpeechModelBundleManager.SUPPORTED_SCHEMA,
            bundleVersion = if (legacy) SpeechBundleCatalog.LEGACY_BUNDLE_VERSION else descriptor.bundleVersion,
            runtimeVersion = descriptor.runtimeVersion,
            sampleRateHz = descriptor.sampleRateHz,
            languages = descriptor.languages,
            stt = SpeechSttManifest("omnilingual_ctc", "asr/model.int8.onnx", "asr/tokens.txt"),
            indonesianStt = IndonesianStreamingSttManifest(
                "zipformer2_transducer",
                indonesian.encoderPath,
                indonesian.decoderPath,
                indonesian.joinerPath,
                indonesian.tokensPath,
                "id",
                indonesian.revision
            ),
            vad = SpeechVadManifest(true, "vad/silero_vad.onnx"),
            diarization = SpeechDiarizationManifest(true, 2, "diarization/segmentation.onnx", "diarization/embedding.onnx"),
            files = files.mapValues { requireNotNull(it.value.sha256) },
            fileSizesBytes = files.mapValues { requireNotNull(it.value.expectedBytes) },
            sources = components.associate { it.id to it.upstreamSource },
            licenses = components.associate { it.id to it.license },
            whisperStt = if (legacy) {
                null
            } else {
                WhisperSttManifest(
                    whisper.encoderPath,
                    whisper.decoderPath,
                    whisper.tokensPath,
                    whisper.revision,
                    whisper.languages,
                    whisper.task
                )
            }
        )
    }

    private fun reject(manifest: SpeechBundleManifest) {
        try {
            validateSpeechSttContract(manifest, descriptor)
            fail("Tampered STT contract must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected contract rejection.
        }
    }
}

package com.stressdetector.mobile.ml.speech

import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

data class SpeechAssemblyProgress(val component: String, val bytesProcessed: Long, val totalBytes: Long, val stage: SpeechAssemblyStage)

/** Extracts only catalogued files and creates the canonical, self-describing ZIP. */
class SpeechBundleArchiveAssembler(private val gson: Gson = Gson()) {
    fun assemble(
        descriptor: SpeechBundleDescriptor,
        downloadedFiles: Map<String, File>,
        destination: File,
        onProgress: (SpeechAssemblyProgress) -> Unit = {}
    ): SpeechBundleManifest {
        val workingDirectory = File(destination.parentFile, ".assemble-${System.nanoTime()}")
        require(workingDirectory.mkdirs()) { "Speech bundle assembly directory could not be created." }
        try {
            val materializedFiles = linkedMapOf<String, File>()
            val materializedChecksums = linkedMapOf<String, String>()
            descriptor.components.forEach { component ->
                val downloaded = downloadedFiles[component.id]
                    ?: error("Downloaded component is missing: ${component.id}")
                component.extractedFiles.forEach { fileSpec ->
                    requireSafeArchivePath(fileSpec.bundlePath)
                    val materialized = File(workingDirectory, fileSpec.bundlePath)
                    materialized.parentFile?.mkdirs()
                    onProgress(
                        SpeechAssemblyProgress(
                            component = component.id,
                            bytesProcessed = 0L,
                            totalBytes = fileSpec.expectedBytes ?: downloaded.length(),
                            stage = SpeechAssemblyStage.EXTRACTING
                        )
                    )
                    if (fileSpec.archiveEntrySuffix == null) {
                        copyFileWithProgress(
                            source = downloaded,
                            destination = materialized,
                            component = component.id,
                            onProgress = onProgress
                        )
                    } else {
                        extractTarBz2Entry(
                            archive = downloaded,
                            suffix = fileSpec.archiveEntrySuffix,
                            destination = materialized,
                            component = component.id,
                            expectedBytes = fileSpec.expectedBytes,
                            onProgress = onProgress
                        )
                    }
                    materializedChecksums[fileSpec.bundlePath] = validateExtractedFile(
                        file = materialized,
                        spec = fileSpec,
                        component = component.id,
                        onProgress = onProgress
                    )
                    materializedFiles[fileSpec.bundlePath] = materialized
                }
            }

            val fileChecksums = materializedFiles.keys.associateWith { materializedChecksums.getValue(it) }
            val fileSizes = materializedFiles.mapValues { (_, file) -> file.length() }
            val manifest = SpeechBundleManifest(
                schemaVersion = SpeechModelBundleManager.SUPPORTED_SCHEMA,
                bundleVersion = descriptor.bundleVersion,
                runtimeVersion = descriptor.runtimeVersion,
                sampleRateHz = descriptor.sampleRateHz,
                languages = descriptor.languages,
                stt = SpeechSttManifest(
                    modelType = "omnilingual_ctc",
                    model = "asr/model.int8.onnx",
                    tokens = "asr/tokens.txt"
                ),
                indonesianStt = descriptor.indonesianStt?.let { profile ->
                    IndonesianStreamingSttManifest(
                        modelType = "zipformer2_transducer",
                        encoder = profile.encoderPath,
                        decoder = profile.decoderPath,
                        joiner = profile.joinerPath,
                        tokens = profile.tokensPath,
                        language = "id",
                        revision = profile.revision
                    )
                },
                vad = SpeechVadManifest(
                    enabled = true,
                    model = "vad/silero_vad.onnx"
                ),
                diarization = SpeechDiarizationManifest(
                    enabled = true,
                    speakerCount = descriptor.speakerCount,
                    segmentation = "diarization/segmentation.onnx",
                    embedding = "diarization/embedding.onnx"
                ),
                ttsProfiles = descriptor.ttsProfiles.associate { profile ->
                    profile.id to SpeechTtsProfileManifest(
                        language = profile.language,
                        voiceId = profile.voiceId,
                        engineType = profile.engineType,
                        archive = profile.archivePath,
                        model = profile.modelPath,
                        tokens = profile.tokensPath,
                        dataDir = profile.dataDirPath,
                        sampleRateHz = profile.sampleRateHz,
                        supertonic = profile.supertonic?.let { model ->
                            SpeechSupertonicModelManifest(
                                durationPredictor = model.durationPredictorPath,
                                textEncoder = model.textEncoderPath,
                                vectorEstimator = model.vectorEstimatorPath,
                                vocoder = model.vocoderPath,
                                ttsJson = model.ttsJsonPath,
                                unicodeIndexer = model.unicodeIndexerPath,
                                voiceStyle = model.voiceStylePath,
                                speakerId = model.speakerId,
                                speakerCount = model.speakerCount,
                                numSteps = model.numSteps,
                                speed = model.speed
                            )
                        }
                    )
                },
                files = fileChecksums,
                fileSizesBytes = fileSizes,
                sources = descriptor.components.associate { it.id to it.upstreamSource },
                licenses = descriptor.components.associate { it.id to it.license }
            )
            writeZip(destination, manifest, materializedFiles, onProgress)
            return manifest
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    private fun extractTarBz2Entry(
        archive: File,
        suffix: String,
        destination: File,
        component: String,
        expectedBytes: Long?,
        onProgress: (SpeechAssemblyProgress) -> Unit
    ) {
        var matchCount = 0
        BufferedInputStream(FileInputStream(archive)).use { input ->
            BZip2CompressorInputStream(input).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val normalized = entry.name.replace('\\', '/')
                        requireSafeArchivePath(normalized)
                        require(!entry.isSymbolicLink && !entry.isLink) {
                            "Pinned archive contains a link entry: $normalized"
                        }
                        if (!entry.isFile || !normalized.endsWith(suffix)) continue
                        matchCount += 1
                        require(matchCount == 1) { "Archive contains duplicate entries for $suffix." }
                        FileOutputStream(destination).use { output ->
                            copyBounded(
                                input = tar,
                                output = output,
                                maxBytes = MAX_EXTRACTED_FILE_BYTES,
                                component = component,
                                totalBytes = expectedBytes ?: 0L,
                                stage = SpeechAssemblyStage.EXTRACTING,
                                onProgress = onProgress
                            )
                        }
                    }
                }
            }
        }
        require(matchCount == 1) { "Pinned archive does not contain required file $suffix." }
    }

    private fun validateExtractedFile(
        file: File,
        spec: SpeechBundleFileSpec,
        component: String,
        onProgress: (SpeechAssemblyProgress) -> Unit
    ): String {
        require(file.isFile && file.length() > 0L) { "Extracted speech file is empty: ${spec.bundlePath}" }
        spec.expectedBytes?.let { expected ->
            require(file.length() == expected) {
                "Extracted size mismatch for ${spec.bundlePath}: expected $expected bytes."
            }
        }
        val actualHash = sha256(
            file = file,
            component = component,
            stage = SpeechAssemblyStage.EXTRACTED_SHA256,
            onProgress = onProgress
        )
        spec.sha256?.let { expected ->
            require(actualHash.equals(expected, ignoreCase = true)) {
                "Extracted checksum mismatch for ${spec.bundlePath}."
            }
        }
        return actualHash
    }

    private fun writeZip(
        destination: File,
        manifest: SpeechBundleManifest,
        files: Map<String, File>,
        onProgress: (SpeechAssemblyProgress) -> Unit
    ) {
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        temporary.parentFile?.mkdirs()
        val totalBytes = files.values.sumOf(File::length)
        var bytesWritten = 0L
        var lastProgress = 0L
        ZipOutputStream(FileOutputStream(temporary)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_PATH))
            zip.write(gson.toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            files.forEach { (path, file) ->
                requireSafeArchivePath(path)
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                        bytesWritten += count
                        if (bytesWritten - lastProgress >= PROGRESS_INTERVAL_BYTES || bytesWritten == totalBytes) {
                            onProgress(
                                SpeechAssemblyProgress(
                                    component = "canonical ZIP",
                                    bytesProcessed = bytesWritten,
                                    totalBytes = totalBytes,
                                    stage = SpeechAssemblyStage.BUILDING_ZIP
                                )
                            )
                            lastProgress = bytesWritten
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (destination.exists()) {
            require(destination.delete()) { "Previous candidate speech bundle could not be replaced." }
        }
        require(temporary.renameTo(destination)) { "Speech bundle ZIP could not be published." }
    }

    private fun copyFileWithProgress(source: File, destination: File, component: String, onProgress: (SpeechAssemblyProgress) -> Unit) {
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                copyBounded(
                    input = input,
                    output = output,
                    maxBytes = Long.MAX_VALUE,
                    component = component,
                    totalBytes = source.length(),
                    stage = SpeechAssemblyStage.EXTRACTING,
                    onProgress = onProgress
                )
            }
        }
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
        component: String,
        totalBytes: Long,
        stage: SpeechAssemblyStage,
        onProgress: (SpeechAssemblyProgress) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        var lastProgress = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= maxBytes) { "Speech archive entry exceeds the extraction limit." }
            output.write(buffer, 0, count)
            if (copied - lastProgress >= PROGRESS_INTERVAL_BYTES || copied == totalBytes) {
                onProgress(
                    SpeechAssemblyProgress(
                        component = component,
                        bytesProcessed = copied,
                        totalBytes = totalBytes,
                        stage = stage
                    )
                )
                lastProgress = copied
            }
        }
    }

    private fun sha256(file: File, component: String, stage: SpeechAssemblyStage, onProgress: (SpeechAssemblyProgress) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var processed = 0L
        var lastProgress = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                processed += count
                if (processed - lastProgress >= PROGRESS_INTERVAL_BYTES || processed == file.length()) {
                    onProgress(
                        SpeechAssemblyProgress(
                            component = component,
                            bytesProcessed = processed,
                            totalBytes = file.length(),
                            stage = stage
                        )
                    )
                    lastProgress = processed
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireSafeArchivePath(path: String) {
        require(
            path.isNotBlank() &&
                !path.startsWith('/') &&
                path.split('/').none { it == ".." || it.isBlank() && path.contains("//") }
        ) {
            "Unsafe archive path: $path"
        }
    }

    private companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val MAX_EXTRACTED_FILE_BYTES = 512L * 1024L * 1024L
        const val PROGRESS_INTERVAL_BYTES = 16L * 1024L * 1024L
    }
}

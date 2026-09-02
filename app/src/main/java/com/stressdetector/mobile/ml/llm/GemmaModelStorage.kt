package com.stressdetector.mobile.ml.llm

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.stressdetector.mobile.ml.assets.AiAssetEntry
import com.stressdetector.mobile.ml.assets.AiAssetKind
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface ModelStorage {
    fun activeModelFile(): File
    fun candidateModelFile(): File

    /** Candidate name used before the LiteRT-LM filename contract was enforced. */
    fun legacyDownloadCandidateFile(): File? = null
    fun resumeModelFile(): File
    fun legacyPartialModelFile(): File
    fun deleteActiveAndLegacyMetadata(): Boolean
    suspend fun inspectActive(): ModelReadiness
    suspend fun verifyCandidate(candidate: File, onProgress: suspend (ModelVerificationProgress) -> Unit = {}): Result<Unit>
    suspend fun publishAtomically(candidate: File): ModelReadiness
    suspend fun snapshotCandidate(candidate: File, resume: File, bytes: Long)
    suspend fun stageDownloadedUri(uri: Uri, resolver: ContentResolver): File
    fun deleteCandidates()
}

class GemmaModelStorage(context: Context, private val contract: OfficialModelContract) : ModelStorage {
    private val applicationContext = context.applicationContext

    private fun modelDir(): File = File(
        requireNotNull(applicationContext.getExternalFilesDir("models")) {
            "App-specific model storage is unavailable"
        },
        "llm"
    ).also { it.mkdirs() }

    override fun activeModelFile(): File = File(modelDir(), contract.fileName)

    // LiteRT-LM dispatches the container from the path suffix. Keep the
    // resumable candidate hidden, but make the final suffix unambiguously
    // `.litertlm` so preflight sees the same format as the published file.
    override fun candidateModelFile(): File = File(modelDir(), ".${contract.fileName}.download.litertlm")

    override fun legacyDownloadCandidateFile(): File = File(modelDir(), ".${contract.fileName}.download.part")

    override fun resumeModelFile(): File = File(modelDir(), ".${contract.fileName}.resume.part")

    override fun legacyPartialModelFile(): File = File(modelDir(), ".${contract.fileName}.part")

    override fun deleteActiveAndLegacyMetadata(): Boolean {
        val activeDeleted = !activeModelFile().exists() || activeModelFile().delete()
        val legacyMetadata = File(modelDir(), LEGACY_SIGNED_MANIFEST_FILE)
        val metadataDeleted = !legacyMetadata.exists() || legacyMetadata.delete()
        return activeDeleted && metadataDeleted
    }

    override suspend fun inspectActive(): ModelReadiness = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val active = activeModelFile()
        if (!active.isFile) return@withContext ModelReadiness.Missing

        verifyOfficialModelFile(active, contract, requireCanonicalName = true).fold(
            onSuccess = { ModelReadiness.Ready(officialMetadata()) },
            onFailure = { ModelReadiness.Corrupted(it.message ?: "Gemma 4 E2B validation failed") }
        )
    }

    override suspend fun verifyCandidate(candidate: File, onProgress: suspend (ModelVerificationProgress) -> Unit): Result<Unit> =
        ModelIntegrityVerifier(contract).verify(candidate, onProgress = onProgress)

    override suspend fun publishAtomically(candidate: File): ModelReadiness =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            forceFileToDisk(candidate)

            val target = activeModelFile()
            val previous = File(target.parentFile, ".${target.name}.previous")
            previous.delete()
            val hadPrevious = target.exists() && target.renameTo(previous)
            check(!target.exists() || hadPrevious) { "Could not stage the active Gemma 4 E2B model" }
            try {
                moveAtomicallyOrRename(candidate, target)
                File(modelDir(), LEGACY_SIGNED_MANIFEST_FILE).delete()
                previous.delete()
                ModelReadiness.Ready(officialMetadata())
            } catch (error: Throwable) {
                target.delete()
                if (hadPrevious) previous.renameTo(target)
                throw error
            }
        }

    override suspend fun snapshotCandidate(candidate: File, resume: File, bytes: Long) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!candidate.isFile) return@withContext
            val copyLength = candidate.length().coerceAtMost(bytes.coerceAtLeast(0L))
            if (copyLength <= 0L) {
                resume.delete()
                return@withContext
            }

            val temporary = File(resume.parentFile, ".${resume.name}.tmp")
            temporary.delete()
            FileInputStream(candidate).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (copied < copyLength) {
                        val requested = minOf(buffer.size.toLong(), copyLength - copied).toInt()
                        val count = input.read(buffer, 0, requested)
                        check(count > 0) { "Candidate changed while preserving download progress" }
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    output.fd.sync()
                }
            }
            moveAtomicallyOrRename(temporary, resume)
        }

    override suspend fun stageDownloadedUri(uri: Uri, resolver: ContentResolver): File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val candidate = candidateModelFile()
            val temporary = File(candidate.parentFile, ".${candidate.name}.uri.tmp")
            temporary.delete()
            val input = resolver.openInputStream(uri)
                ?: error("DownloadManager returned an unreadable model URI")
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            moveAtomicallyOrRename(temporary, candidate)
            candidate
        }

    override fun deleteCandidates() {
        val candidate = candidateModelFile()
        candidate.delete()
        File(candidate.parentFile, "${candidate.name}.part").delete()
        File(candidate.parentFile, "${candidate.name}.part.properties").delete()

        val legacyCandidate = legacyDownloadCandidateFile()
        legacyCandidate.delete()
        File(legacyCandidate.parentFile, "${legacyCandidate.name}.part").delete()
        File(legacyCandidate.parentFile, "${legacyCandidate.name}.part.properties").delete()

        resumeModelFile().delete()
        legacyPartialModelFile().delete()
    }

    private fun moveAtomicallyOrRename(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            target.delete()
            check(source.renameTo(target)) { "Could not publish ${source.name}" }
        }
    }

    private fun forceFileToDisk(file: File) {
        FileOutputStream(file, true).use { output -> output.fd.sync() }
    }

    private fun officialMetadata(): AiAssetEntry = AiAssetEntry(
        kind = AiAssetKind.LLM,
        fileName = contract.fileName,
        source = ModelManager.MODEL_SOURCE,
        license = "Apache-2.0",
        version = "gemma-4-E2B-it-litertlm",
        sizeBytes = contract.sizeBytes,
        sha256 = contract.sha256
    )

    private companion object {
        const val LEGACY_SIGNED_MANIFEST_FILE = "ai_asset_manifest.json"
    }
}

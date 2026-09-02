package com.stressdetector.mobile.ml.artifacts

import android.net.Network
import com.stressdetector.mobile.ml.download.ArtifactDownloadPolicy
import com.stressdetector.mobile.ml.download.ArtifactIntegrityVerifier
import com.stressdetector.mobile.ml.download.ArtifactVerificationProgress
import com.stressdetector.mobile.ml.download.ResumableDownloadFailure
import com.stressdetector.mobile.ml.download.ResumableHttpFileDownloader
import java.io.File

/** One verified transfer boundary shared by all catalog-backed local AI artifacts. */
class VerifiedArtifactDownloadManager(
    private val downloader: ResumableHttpFileDownloader = ResumableHttpFileDownloader(),
    private val verifier: ArtifactIntegrityVerifier = ArtifactIntegrityVerifier()
) {
    suspend fun download(
        artifact: CatalogArtifactEntry,
        file: CatalogArtifactFile,
        destination: File,
        onProgress: suspend (Long) -> Unit = {},
        onVerificationProgress: suspend (ArtifactVerificationProgress) -> Unit = {},
        network: Network? = null
    ): File {
        val source = requireNotNull(file.url) { "${artifact.id}/${file.path} has no download URL." }
        val isTokenizer = file.role == "tokenizer" || file.path.substringAfterLast('/') == "tokenizer.json"
        val basePolicy = ArtifactDownloadPolicy(
            allowedRedirectHosts = artifact.allowedRedirectHosts.toSet(),
            allowMixedPublicAndPrivateAddresses = true
        )
        val policy = if (isTokenizer) {
            basePolicy.copy(
                allowedContentTypes = basePolicy.allowedContentTypes +
                    ArtifactDownloadPolicy.TOKENIZER_CONTENT_TYPES
            )
        } else {
            basePolicy
        }
        downloader.download(
            url = source,
            destination = destination,
            expectedBytes = file.sizeBytes,
            label = "${artifact.id}/${file.path}",
            policy = policy,
            network = network,
            onProgress = onProgress
        )
        verifier.verify(
            destination,
            file.sizeBytes,
            file.sha256,
            "${artifact.id}/${file.path}",
            onVerificationProgress
        )
            .getOrElse { error ->
                destination.delete()
                throw ResumableDownloadFailure(
                    error.message ?: "${artifact.id}/${file.path} failed catalog verification.",
                    retryable = false
                )
            }
        return destination
    }
}

package com.stressdetector.mobile.ml.llm

import android.app.DownloadManager
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadStateTest {

    @Test
    fun runningStatePreservesRealBytesAndTotal() {
        val state = mapGemmaDownloadSnapshot(
            DownloadSnapshot(
                id = 17L,
                status = DownloadTransportStatus.Running,
                bytesDownloaded = 384_095_005L,
                totalBytes = ModelManager.MODEL_SIZE_BYTES
            )
        )

        assertEquals(
            ModelDownloadState.Downloading(384_095_005L, ModelManager.MODEL_SIZE_BYTES),
            state
        )
    }

    @Test
    fun pendingAndWaitingNetworkAreTypedUntilConnectivityIsEvaluated() {
        assertEquals(
            ModelDownloadState.WaitingForNetwork(
                GemmaNetworkState.UNKNOWN,
                "Download menunggu evaluasi jaringan."
            ),
            mapGemmaDownloadSnapshot(
                DownloadSnapshot(1L, DownloadTransportStatus.Pending, 0L, ModelManager.MODEL_SIZE_BYTES)
            )
        )
        assertEquals(
            ModelDownloadState.WaitingForNetwork(
                GemmaNetworkState.UNKNOWN,
                "Download menunggu jaringan yang memenuhi policy Wi-Fi."
            ),
            mapGemmaDownloadSnapshot(
                DownloadSnapshot(
                    2L,
                    DownloadTransportStatus.Paused,
                    0L,
                    ModelManager.MODEL_SIZE_BYTES,
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK
                )
            )
        )
    }

    @Test
    fun successfulTransportTransitionsToVerification() {
        val state = mapGemmaDownloadSnapshot(
            DownloadSnapshot(3L, DownloadTransportStatus.Successful, 512L, ModelManager.MODEL_SIZE_BYTES)
        )

        assertEquals(
            ModelDownloadState.Verifying(512L, ModelManager.MODEL_SIZE_BYTES),
            state
        )
    }

    @Test
    fun failedTransportIsRetryable() {
        val state = mapGemmaDownloadSnapshot(
            DownloadSnapshot(4L, DownloadTransportStatus.Failed, 128L, ModelManager.MODEL_SIZE_BYTES, reason = 1004)
        )

        assertTrue(state is ModelDownloadState.Failed && state.retryable)
    }

    @Test
    fun storageFailureIsNotRetryableWithoutUserAction() {
        val state = mapGemmaDownloadSnapshot(
            DownloadSnapshot(
                5L,
                DownloadTransportStatus.Failed,
                128L,
                ModelManager.MODEL_SIZE_BYTES,
                reason = DownloadManager.ERROR_INSUFFICIENT_SPACE
            )
        )

        assertTrue(state is ModelDownloadState.Failed && !state.retryable)
    }

    @Test
    fun runtimePreflightFailureIsDistinguishedFromTransferFailure() {
        val state = ModelDownloadState.Failed(
            "Gemma lulus checksum tetapi gagal runtime preflight: INVALID_ARGUMENT",
            retryable = false
        )

        assertTrue(state.isRuntimePreflightFailure())
        assertTrue(
            !ModelDownloadState.Failed("HTTP transfer failed", retryable = false)
                .isRuntimePreflightFailure()
        )
    }

    @Test
    fun resumableCandidatePathKeepsLitertLmSuffix() {
        assertTrue(ModelManager.DOWNLOAD_CANDIDATE_FILE_NAME.endsWith(".litertlm"))
    }

    @Test
    fun failedFinalizerWinsOverSuccessfulDownloadManagerState() {
        val state = mapFinalizerTerminalState(
            state = WorkInfo.State.FAILED,
            persisted = PersistedModelDownload(
                downloadId = 12L,
                replacesActiveModel = false,
                contractSha256 = ModelManager.MODEL_SHA256,
                candidateFileName = ".${ModelManager.MODEL_FILE_NAME}.download.part",
                stage = ModelDownloadStage.FAILED,
                errorMessage = "SHA-256 verification failed"
            ),
            outputMessage = null
        )

        assertEquals(
            ModelDownloadState.Failed("SHA-256 verification failed", retryable = false),
            state
        )
    }
}

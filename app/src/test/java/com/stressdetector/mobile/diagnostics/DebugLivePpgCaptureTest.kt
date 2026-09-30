package com.stressdetector.mobile.diagnostics

import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgType
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLivePpgCaptureTest {
    @Test
    fun recordsOnlyAcknowledgedBatchAndLeavesSensorParityPending() {
        val root = Files.createTempDirectory("debug-live-ppg-capture").toFile()
        try {
            val capture = DebugLivePpgCapture(
                rootDirectory = root,
                enabled = { true },
                executor = Executor { task -> task.run() }
            )
            val batch = acknowledgedBatch(PpgType.GREEN, "acknowledged-watch-session")

            capture.appendAcknowledged(listOf(batch))
            capture.markSessionFinalized(batch.sessionId)

            val manifest = root.walkTopDown()
                .first { it.name == "capture_manifest.json" }
                .readText()
            val capturedBatch = root.walkTopDown()
                .first { it.name.startsWith("ppg_green_") && it.extension == "json" }
                .readText()

            assertTrue(manifest.contains("\"source\":\"acknowledged_watch_transport\""))
            assertTrue(manifest.contains("\"actualSensorAcquisition\":false"))
            assertTrue(manifest.contains("\"verificationStatus\":\"PENDING_PYTHON_ORACLE\""))
            assertTrue(capturedBatch.contains("\"rawValues\""))
            assertTrue(capturedBatch.contains("\"timestampsMillis\""))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun disabledCaptureDoesNotCreateFiles() {
        val root = Files.createTempDirectory("debug-live-ppg-capture-disabled").toFile()
        try {
            val capture = DebugLivePpgCapture(
                rootDirectory = root,
                enabled = { false },
                executor = Executor { task -> task.run() }
            )

            capture.appendAcknowledged(
                listOf(acknowledgedBatch(PpgType.IR, "disabled-session"))
            )

            assertFalse(root.walkTopDown().any { it.name == "capture_manifest.json" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun finalizesPersistedCaptureAfterProcessRegistryRecreation() {
        val root = Files.createTempDirectory("debug-live-ppg-capture-recreated").toFile()
        try {
            val batch = acknowledgedBatch(PpgType.GREEN, "recreated-session")
            DebugLivePpgCapture(
                rootDirectory = root,
                enabled = { true },
                executor = Executor { task -> task.run() }
            ).appendAcknowledged(listOf(batch))

            val recreatedCapture = DebugLivePpgCapture(
                rootDirectory = root,
                enabled = { true },
                executor = Executor { task -> task.run() }
            )

            assertTrue(recreatedCapture.markSessionFinalized(batch.sessionId, batch.sessionNonce))
            val manifest = root.walkTopDown()
                .first { it.name == "capture_manifest.json" }
                .readText()
            assertTrue(manifest.contains("\"finalized\":true"))
            assertTrue(manifest.contains("\"verificationStatus\":\"PENDING_PYTHON_ORACLE\""))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun acknowledgedBatch(type: PpgType, sessionId: String): PpgBatch = PpgBatch(
        type = type,
        timestampsMillis = longArrayOf(0L, 40L, 80L),
        rawValues = intArrayOf(100_000, 100_100, 100_050),
        sessionId = sessionId,
        elapsedMillis = 80L,
        batchId = "$sessionId-${type.assetSuffix}-1",
        sequenceNumber = 1L,
        elapsedStartMillis = 0L,
        elapsedEndMillis = 80L,
        sessionNonce = "nonce-$sessionId",
        peerNodeId = "watch-test-node",
        generation = 1L
    )
}

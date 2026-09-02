package com.stressdetector.mobile.ml.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableSyncReportTest {
    @Test
    fun `missing Sherpa source keeps connected folder successful`() {
        val report = DurableSyncReport(
            storage = ModelStorageState(connected = true),
            artifacts = mapOf(
                DurableArtifact.GEMMA to DurableArtifactSyncStatus.AlreadyCurrent,
                DurableArtifact.SPEECH_BUNDLE to DurableArtifactSyncStatus.MissingLocalSource(
                    "Bundle Verbatim belum tersedia. Unduh terlebih dahulu."
                ),
                DurableArtifact.MULTILINGUAL_EMBEDDING to DurableArtifactSyncStatus.MissingLocalSource(
                    "Embedding multilingual belum tersedia."
                )
            )
        )

        assertTrue(report.storage.connected)
        assertTrue(report.statusOf(DurableArtifact.SPEECH_BUNDLE) is DurableArtifactSyncStatus.MissingLocalSource)
        assertFalse(report.userMessage().contains("gagal", ignoreCase = true))
    }

    @Test
    fun `permission loss is reported separately from an artifact problem`() {
        val report = DurableSyncReport(
            storage = ModelStorageState(error = "Permission revoked"),
            artifacts = emptyMap()
        )

        assertEquals("Hubungkan kembali folder", report.userMessage())
        assertTrue(report.statusOf(DurableArtifact.GEMMA) is DurableArtifactSyncStatus.MissingLocalSource)
        assertTrue(report.technicalMessages().contains("Permission revoked"))
    }

    @Test
    fun `restored legacy artifact records verified provenance without a download`() {
        val report = DurableSyncReport(
            storage = ModelStorageState(connected = true),
            artifacts = mapOf(DurableArtifact.GEMMA to DurableArtifactSyncStatus.Restored),
            recovery = mapOf(
                DurableArtifact.GEMMA to DurableArtifactRecovery(
                    source = DurableArtifactSource.LEGACY_SAF,
                    state = DurableArtifactRecoveryState.READY,
                    sizeBytes = 2_588_147_712L,
                    checksumSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                    checksumVerified = true,
                    networkDownloadRequired = false
                )
            )
        )

        val recovery = report.recoveryOf(DurableArtifact.GEMMA)
        assertEquals(DurableArtifactSource.LEGACY_SAF, recovery.source)
        assertEquals(DurableArtifactRecoveryState.READY, recovery.state)
        assertEquals(2_588_147_712L, recovery.sizeBytes)
        assertTrue(recovery.checksumVerified)
        assertFalse(recovery.networkDownloadRequired)
    }

    @Test
    fun `restore in progress exposes a short recovery state`() {
        val report = DurableSyncReport.restoring(ModelStorageState())

        assertTrue(report.isRestoring())
        assertEquals("Memulihkan artefak offline", report.userMessage())
        assertEquals(
            DurableArtifactRecoveryState.RESTORING,
            report.recoveryOf(DurableArtifact.SPEECH_BUNDLE).state
        )
    }

    @Test
    fun `runtime incompatible artifact never requests another download`() {
        val report = DurableSyncReport(
            storage = ModelStorageState(connected = true),
            artifacts = mapOf(
                DurableArtifact.GEMMA to DurableArtifactSyncStatus.RuntimeIncompatible("ABI tidak didukung")
            )
        )

        assertEquals(
            DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE,
            report.recoveryOf(DurableArtifact.GEMMA).state
        )
        assertFalse(report.networkDownloadRequired(DurableArtifact.GEMMA))
        assertEquals("Model tersedia, runtime tidak kompatibel", report.userMessage())
    }

    @Test
    fun `only missing and corrupt artifacts allow network download`() {
        val report = DurableSyncReport(
            storage = ModelStorageState(connected = true),
            artifacts = mapOf(
                DurableArtifact.GEMMA to DurableArtifactSyncStatus.MissingLocalSource("missing"),
                DurableArtifact.SPEECH_BUNDLE to DurableArtifactSyncStatus.Corrupt("corrupt"),
                DurableArtifact.MULTILINGUAL_EMBEDDING to DurableArtifactSyncStatus.Failed("provider busy")
            )
        )

        assertTrue(report.networkDownloadRequired(DurableArtifact.GEMMA))
        assertTrue(report.networkDownloadRequired(DurableArtifact.SPEECH_BUNDLE))
        assertFalse(report.networkDownloadRequired(DurableArtifact.MULTILINGUAL_EMBEDDING))
        assertEquals(
            DurableArtifactRecoveryState.ERROR,
            report.recoveryOf(DurableArtifact.MULTILINGUAL_EMBEDDING).state
        )
    }
}

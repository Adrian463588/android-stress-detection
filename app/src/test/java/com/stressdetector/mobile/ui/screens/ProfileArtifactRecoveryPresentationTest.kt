package com.stressdetector.mobile.ui.screens

import com.stressdetector.mobile.ml.storage.DurableArtifactRecovery
import com.stressdetector.mobile.ml.storage.DurableArtifactRecoveryState
import com.stressdetector.mobile.ml.storage.DurableArtifactSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileArtifactRecoveryPresentationTest {
    @Test
    fun runtimeIncompatibleIsAvailableButNeverPresentedAsDownloadRequired() {
        val recovery = DurableArtifactRecovery(
            source = DurableArtifactSource.CANONICAL_SAF,
            state = DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE,
            checksumVerified = true,
            networkDownloadRequired = true
        )

        assertTrue(recovery.profileLabel().contains("runtime tidak kompatibel"))
        assertFalse(recovery.profileLabel().contains("unduh diperlukan"))
        assertFalse(recovery.shouldOfferNetworkDownload())
    }

    @Test
    fun checksumReadyIsNotTreatedAsMissing() {
        val recovery = DurableArtifactRecovery(
            source = DurableArtifactSource.LEGACY_SAF,
            state = DurableArtifactRecoveryState.CHECKSUM_READY,
            checksumVerified = true
        )

        assertTrue(recovery.profileLabel().contains("checksum siap"))
        assertFalse(recovery.shouldOfferNetworkDownload())
    }

    @Test
    fun networkDownloadIsOnlyOfferedForExplicitMissingOrCorruptState() {
        val missing = DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.MISSING,
            networkDownloadRequired = true
        )
        val corrupt = DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.CORRUPT,
            networkDownloadRequired = true
        )
        val ready = DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.READY,
            networkDownloadRequired = true
        )

        assertTrue(missing.shouldOfferNetworkDownload())
        assertTrue(corrupt.shouldOfferNetworkDownload())
        assertFalse(ready.shouldOfferNetworkDownload())
    }
}

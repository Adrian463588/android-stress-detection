package com.stressdetector.mobile.ml.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableModelStoragePolicyTest {
    @Test
    fun `rebuild and uninstall never delete external model files`() {
        assertFalse(
            DurableModelStoragePolicy.mayDeleteExternalArtifacts(
                ModelStorageDeletionTrigger.APPLICATION_REBUILD
            )
        )
        assertFalse(
            DurableModelStoragePolicy.mayDeleteExternalArtifacts(
                ModelStorageDeletionTrigger.APPLICATION_UNINSTALL
            )
        )
        assertTrue(
            DurableModelStoragePolicy.mayDeleteExternalArtifacts(
                ModelStorageDeletionTrigger.EXPLICIT_USER_DELETE
            )
        )
    }

    @Test
    fun `uninstall requires user to reselect SAF folder`() {
        assertTrue(DurableModelStoragePolicy.requiresFolderReselectionAfterUninstall())
    }
}

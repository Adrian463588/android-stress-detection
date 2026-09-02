package com.stressdetector.mobile.ml.storage

import android.content.Intent
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafTreeDocumentPolicyTest {
    @Test
    fun `provider validation is scoped to the selected tree document`() {
        assertEquals(
            SafProviderInspectionScope.SELECTED_TREE_DOCUMENT,
            SafTreeDocumentPolicy.inspectionScope
        )
        assertEquals(
            "primary:StressDetection",
            SafTreeDocumentPolicy.STRESS_DETECTION_TREE_DOCUMENT_ID
        )
        assertEquals(
            "StressDetector/Model/VoiceAI/capability.json",
            LegacySafPaths.VOICE_CAPABILITY_PATH
        )
    }

    @Test
    fun `capacity unavailable does not invalidate a valid selected tree`() {
        SafTreeDocumentPolicy.validateSelectedTree(
            authority = SafTreeDocumentPolicy.LOCAL_STORAGE_AUTHORITY,
            requestedDocumentId = "primary:StressDetection",
            returnedDocumentId = "primary:StressDetection",
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            documentFlags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
            availableBytes = null,
            requiredBytes = Long.MAX_VALUE
        )
    }

    @Test
    fun `insufficient measured capacity is distinct from a permission error`() {
        val error = runCatching {
            SafTreeDocumentPolicy.validateSelectedTree(
                authority = SafTreeDocumentPolicy.LOCAL_STORAGE_AUTHORITY,
                requestedDocumentId = "primary:StressDetection",
                returnedDocumentId = "primary:StressDetection",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                documentFlags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
                availableBytes = 10L,
                requiredBytes = 11L
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(SafStorageStatus.INSUFFICIENT_SPACE, classifySafFailure(error!!))
        assertEquals(SafStorageStatus.PERMISSION_LOST, classifySafFailure(SecurityException("grant revoked")))
    }

    @Test
    fun `picker retains only the exact read write flags returned by DocumentsUI`() {
        val returned = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        assertEquals(
            SafTreeDocumentPolicy.READ_WRITE_GRANT_FLAGS,
            SafTreeDocumentPolicy.pickerReadWriteFlags(returned)
        )
        assertTrue(
            SafTreeDocumentPolicy.pickerReadWriteFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) !=
                SafTreeDocumentPolicy.READ_WRITE_GRANT_FLAGS
        )
    }
}

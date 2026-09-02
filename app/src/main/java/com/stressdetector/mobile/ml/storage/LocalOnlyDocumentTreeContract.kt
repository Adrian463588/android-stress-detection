package com.stressdetector.mobile.ml.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/** Retains the exact read/write flags returned by DocumentsUI for persisted SAF access. */
class LocalOnlyDocumentTreeContract(
    private val initialTreeUri: Uri? = DocumentsContract.buildTreeDocumentUri(
        SafTreeDocumentPolicy.LOCAL_STORAGE_AUTHORITY,
        SafTreeDocumentPolicy.STRESS_DETECTION_TREE_DOCUMENT_ID
    )
) : ActivityResultContract<Unit?, SafTreeSelection?>() {
    override fun createIntent(context: Context, input: Unit?): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                initialTreeUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        }
        .addFlags(
            SafTreeDocumentPolicy.READ_WRITE_GRANT_FLAGS or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )

    override fun parseResult(resultCode: Int, intent: Intent?): SafTreeSelection? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        val flags = SafTreeDocumentPolicy.pickerReadWriteFlags(intent.flags)
        return SafTreeSelection(uri, flags)
    }
}

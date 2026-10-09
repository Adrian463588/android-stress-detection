package com.stressdetector.mobile.lifecycle

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Never writes fixtures into Artifacts/user-data-backup or changes the selected tree/grants. */
internal class SafRecoveryTestFolder(private val resolver: ContentResolver, tree: Uri) : Closeable {
    private val name = "SafRecoveryAcceptance-${UUID.randomUUID()}"
    private val expectedId = "${DocumentsContract.getTreeDocumentId(tree)}/$name"

    init {
        require(tree.authority == LOCAL_AUTHORITY) { "Only a selected local provider is supported by this test." }
    }

    private val folder = requireNotNull(
        DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        )
    ) { "Test folder could not be created." }

    private lateinit var payload: Uri

    init {
        check(folder.authority == LOCAL_AUTHORITY)
        check(DocumentsContract.getDocumentId(folder) == expectedId) { "Unexpected provider folder; do not delete it." }
    }

    fun output(): OutputStream {
        payload = requireNotNull(DocumentsContract.createDocument(resolver, folder, "application/octet-stream", "test-only.sdb"))
        check(DocumentsContract.getDocumentId(payload) == "$expectedId/test-only.sdb")
        return requireNotNull(resolver.openOutputStream(payload, "wt"))
    }

    fun input(): InputStream = requireNotNull(resolver.openInputStream(payload))

    override fun close() {
        check(folder.authority == LOCAL_AUTHORITY && DocumentsContract.getDocumentId(folder) == expectedId)
        check(DocumentsContract.deleteDocument(resolver, folder)) { "Owned SAF test folder was not removed." }
    }

    companion object {
        const val LOCAL_AUTHORITY = "com.android.externalstorage.documents"
    }
}

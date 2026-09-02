package com.stressdetector.mobile.ml.download

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StorageSpaceProviderTest {

    @Test
    fun missingRuntimeDirectoryUsesExistingParentAsStorageAnchor() {
        val root = Files.createTempDirectory("storage-provider-test").toFile()
        try {
            val appFiles = root.resolve("files").also { it.mkdirs() }
            val missingRuntime = appFiles.resolve("speech_runtime")

            val anchor = resolveExistingStorageAnchor(missingRuntime, appFiles)

            assertNotNull(anchor)
            assertEquals(appFiles.canonicalFile, anchor?.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingRequestedTreeUsesExistingAncestor() {
        val root = Files.createTempDirectory("storage-provider-fallback").toFile()
        try {
            val fallback = root.resolve("files").also { it.mkdirs() }
            val missingTree = root.resolve("not-created").resolve("speech_runtime")

            val anchor = resolveExistingStorageAnchor(missingTree, fallback)

            assertEquals(root.canonicalFile, anchor?.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }
}

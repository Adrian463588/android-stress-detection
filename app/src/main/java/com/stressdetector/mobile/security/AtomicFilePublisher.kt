package com.stressdetector.mobile.security

import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** Atomic create-only move within app-private storage; never degrades to an overwriting rename. */
internal object AtomicFilePublisher {
    private val nativeReady by lazy {
        try {
            System.loadLibrary("secure_file_ops")
            true
        } catch (error: LinkageError) {
            throw IOException("Create-only file publication is unavailable", error)
        }
    }

    fun moveNew(source: File, target: File) {
        require(source.isFile && source.canonicalFile != target.canonicalFile) { "Invalid publication source" }
        check(nativeReady)
        when (val error = renameNew(source.absolutePath, target.absolutePath)) {
            0 -> Unit
            OsConstants.EEXIST -> throw FileAlreadyExistsException(target.name)
            else -> throw IOException("Create-only file publication failed (errno=$error)")
        }
    }

    private external fun renameNew(source: String, target: String): Int
}

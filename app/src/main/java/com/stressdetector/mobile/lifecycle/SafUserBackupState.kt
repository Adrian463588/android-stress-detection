package com.stressdetector.mobile.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class SafUserBackupState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val pendingChanges: Boolean = false,
    val lastSuccessfulAtMillis: Long? = null,
    val lastGeneration: String? = null,
    val error: String? = null
)

/** Cache availability is not the outcome of the already committed archive operation. */
internal fun SafUserBackupState.withAutomationCache(cacheError: Throwable?): SafUserBackupState = copy(
    enabled = cacheError == null,
    running = false,
    error = cacheError?.let {
        "Data tersimpan atau dipulihkan, tetapi cadangan otomatis belum aktif. " +
            "Kunci lokal tidak dapat disimpan; coba buat cadangan lagi melalui Profile."
    }
)

/** Cancellation releases UI controls without discarding the previous receipt or pending edits. */
internal fun MutableStateFlow<SafUserBackupState>.reportOperationFailure(error: Throwable) {
    update {
        it.copy(
            running = false,
            error = if (error is CancellationException) null else error.message ?: "Cadangan SAF gagal."
        )
    }
    if (error is CancellationException) throw error
}

package com.stressdetector.mobile.ml.storage

enum class ModelStorageDeletionTrigger {
    APPLICATION_REBUILD,
    APPLICATION_UNINSTALL,
    EXPLICIT_USER_DELETE
}

/** External model files are deleted only by an explicit user action. */
object DurableModelStoragePolicy {
    fun mayDeleteExternalArtifacts(trigger: ModelStorageDeletionTrigger): Boolean =
        trigger == ModelStorageDeletionTrigger.EXPLICIT_USER_DELETE

    fun requiresFolderReselectionAfterUninstall(): Boolean = true
}

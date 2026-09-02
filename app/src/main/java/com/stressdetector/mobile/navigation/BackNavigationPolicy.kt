package com.stressdetector.mobile.navigation

/**
 * Pure back-navigation policy for the single-shell application.
 *
 * Keeping this decision outside Compose prevents each destination from
 * implementing a subtly different back behavior.
 */
enum class BackNavigationAction {
    DISMISS_OVERLAY,
    POP_NESTED,
    GO_HOME,
    STAY
}

object BackNavigationPolicy {
    fun decide(currentRoute: String?, documentLibraryVisible: Boolean, recording: Boolean): BackNavigationAction = when {
        recording -> BackNavigationAction.STAY
        documentLibraryVisible -> BackNavigationAction.DISMISS_OVERLAY
        currentRoute.isNestedRoute() -> BackNavigationAction.POP_NESTED
        currentRoute != HOME_ROUTE -> BackNavigationAction.GO_HOME
        else -> BackNavigationAction.STAY
    }

    private fun String?.isNestedRoute(): Boolean = this == PERSONA_ROUTE ||
        this == HISTORY_DETAIL_ROUTE ||
        this?.startsWith("history/") == true ||
        this == DOCUMENT_LIBRARY_ROUTE ||
        this == DOCUMENT_DETAIL_ROUTE ||
        this?.startsWith("documents/") == true

    private const val HOME_ROUTE = "home"
    private const val PERSONA_ROUTE = "counselor-persona"
    private const val HISTORY_DETAIL_ROUTE = "history/{sessionId}"
    private const val DOCUMENT_LIBRARY_ROUTE = "documents"
    private const val DOCUMENT_DETAIL_ROUTE = "documents/{documentId}"
}

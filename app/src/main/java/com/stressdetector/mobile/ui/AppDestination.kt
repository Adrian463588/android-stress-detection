package com.stressdetector.mobile.ui

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Outlined.Home)
    object History : Screen("history", "History", Icons.Outlined.History)
    object HistoryDetail : Screen("history/{sessionId}", "Session report", Icons.Outlined.History) {
        fun createRoute(sessionId: String): String = "history/${Uri.encode(sessionId)}"
    }
    object Verbatim : Screen("verbatim", "Verbatim+", Icons.Outlined.Mic)
    object Chat : Screen("chat", "AI+", Icons.Outlined.Chat)
    object Profile : Screen("profile", "Profile", Icons.Outlined.Person)
    object CounselorPersona : Screen("counselor-persona", "Persona", Icons.Outlined.Person)
    object DocumentLibrary : Screen("documents", "Knowledge Base", Icons.Outlined.Description)
    object DocumentDetail : Screen("documents/{documentId}", "Document detail", Icons.Outlined.Description) {
        fun createRoute(documentId: String): String = "documents/${Uri.encode(documentId)}"
    }
}

val APP_DESTINATIONS = listOf(
    Screen.Home,
    Screen.History,
    Screen.Verbatim,
    Screen.Chat,
    Screen.Profile
)

/** Keeps the primary shell selection stable while a nested screen is open. */
fun parentDestinationFor(route: String?): Screen? = when {
    route == null -> null
    route == Screen.History.route || route.startsWith("history/") -> Screen.History
    route == Screen.Verbatim.route -> Screen.Verbatim
    route == Screen.Chat.route -> Screen.Chat
    route == Screen.Profile.route || route == Screen.CounselorPersona.route -> Screen.Profile
    route == Screen.DocumentLibrary.route || route.startsWith("documents/") -> Screen.Chat
    else -> Screen.Home
}

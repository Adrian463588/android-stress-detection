package com.stressdetector.app.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.stressdetector.mobile.ui.Screen

@Composable
@Suppress("LongParameterList")
fun AppNavGraph(
    navController: NavHostController,
    innerPadding: PaddingValues,
    homeScreen: @Composable () -> Unit,
    historyScreen: @Composable () -> Unit,
    historyDetailScreen: @Composable (String) -> Unit,
    verbatimScreen: @Composable () -> Unit,
    counselorScreen: @Composable () -> Unit,
    profileScreen: @Composable () -> Unit,
    counselorPersonaScreen: @Composable () -> Unit,
    documentLibraryScreen: @Composable () -> Unit,
    documentDetailScreen: @Composable (String) -> Unit,
    assessmentScreen: @Composable (String?) -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            homeScreen()
        }
        composable(Screen.History.route) {
            historyScreen()
        }
        composable(
            route = Screen.HistoryDetail.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { entry ->
            historyDetailScreen(entry.arguments?.getString("sessionId").orEmpty())
        }
        composable(Screen.Verbatim.route) {
            verbatimScreen()
        }
        composable(Screen.Chat.route) {
            counselorScreen()
        }
        composable(Screen.AssessmentCatalog.route) {
            assessmentScreen(null)
        }
        composable(
            route = Screen.AssessmentDetail.route,
            arguments = listOf(navArgument("instrumentId") { type = NavType.StringType })
        ) { entry ->
            assessmentScreen(entry.arguments?.getString("instrumentId").orEmpty())
        }
        composable(Screen.Profile.route) {
            profileScreen()
        }
        composable(Screen.CounselorPersona.route) {
            counselorPersonaScreen()
        }
        composable(Screen.DocumentLibrary.route) {
            documentLibraryScreen()
        }
        composable(
            route = Screen.DocumentDetail.route,
            arguments = listOf(navArgument("documentId") { type = NavType.StringType })
        ) { entry ->
            documentDetailScreen(entry.arguments?.getString("documentId").orEmpty())
        }
    }
}

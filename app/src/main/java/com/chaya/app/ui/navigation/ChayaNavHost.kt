package com.chaya.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.chaya.app.browser.BrowserScreen
import com.chaya.app.downloads.DownloadsScreen

@Composable
fun ChayaNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Browser.route
    ) {
        composable(Screen.Browser.route) {
            BrowserScreen(onNavigateToDownloads = {
                navController.navigate(Screen.Downloads.route)
            })
        }
        composable(Screen.Downloads.route) {
            DownloadsScreen(onNavigateBack = {
                navController.popBackStack()
            })
        }
    }
}

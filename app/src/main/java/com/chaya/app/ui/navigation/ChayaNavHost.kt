package com.chaya.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.chaya.app.ChayaApplication
import com.chaya.app.SharedLinks
import com.chaya.app.browser.BrowserScreen
import com.chaya.app.diagnostics.DiagnosticsScreen
import com.chaya.app.downloads.DownloadsScreen
import com.chaya.app.ui.player.PlayerScreen
import com.chaya.app.ui.theme.ChayaMotion

/** Fade-through transitions: soft rise + scale on enter, quick fade on exit. */
private fun <T> enterTween() = tween<T>(ChayaMotion.DurationMedium, easing = ChayaMotion.EasingStandard)
private fun exitTween() = tween<Float>(ChayaMotion.DurationShort, easing = ChayaMotion.EasingExit)

@Composable
fun ChayaNavHost(navController: NavHostController) {
    // A link shared to Chaya is opened by the browser; bring it forward if another screen is showing.
    val sharedLink by SharedLinks.pending.collectAsState()
    LaunchedEffect(sharedLink) {
        if (sharedLink != null) navController.popBackStack(Screen.Browser.route, inclusive = false)
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Browser.route,
        enterTransition = {
            fadeIn(enterTween()) + scaleIn(initialScale = 0.965f, animationSpec = enterTween())
        },
        exitTransition = { fadeOut(exitTween()) },
        popEnterTransition = {
            fadeIn(enterTween()) + scaleIn(initialScale = 0.965f, animationSpec = enterTween())
        },
        popExitTransition = { fadeOut(exitTween()) }
    ) {
        composable(Screen.Browser.route) {
            BrowserScreen(onNavigateToDownloads = {
                navController.navigate(Screen.Downloads.route)
            })
        }
        composable(Screen.Downloads.route) {
            val themeSettings = (LocalContext.current.applicationContext as ChayaApplication).themeSettings
            val themeMode by themeSettings.mode.collectAsState()
            DownloadsScreen(
                onNavigateBack = { navController.popBackStack() },
                onPlayStream = { taskId ->
                    navController.navigate(Screen.playerRoute(taskId))
                },
                onNavigateToDiagnostics = {
                    navController.navigate(Screen.Diagnostics.route)
                },
                themeMode = themeMode,
                onThemeModeChange = themeSettings::set,
            )
        }
        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument(Screen.PLAYER_ARGS) { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getLong(Screen.PLAYER_ARGS) ?: -1L
            PlayerScreen(taskId = taskId, onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}

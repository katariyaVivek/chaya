package com.chaya.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
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
            DownloadsScreen(
                onNavigateBack = { navController.popBackStack() },
                onPlayStream = { taskId ->
                    navController.navigate(Screen.playerRoute(taskId))
                },
                onNavigateToDiagnostics = {
                    navController.navigate(Screen.Diagnostics.route)
                },
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

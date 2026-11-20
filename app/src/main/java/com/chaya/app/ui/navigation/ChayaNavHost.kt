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
import com.chaya.app.history.BookmarksScreen
import com.chaya.app.history.HistoryScreen
import com.chaya.app.history.PagesToOpen
import com.chaya.app.home.HomeScreen
import com.chaya.app.home.Place
import com.chaya.app.home.PlaceStep
import com.chaya.app.ui.player.PlayerScreen
import com.chaya.app.ui.theme.ChayaMotion

/** Fade-through transitions: soft rise + scale on enter, quick fade on exit. */
private fun <T> enterTween() = tween<T>(ChayaMotion.DurationMedium, easing = ChayaMotion.EasingStandard)
private fun exitTween() = tween<Float>(ChayaMotion.DurationShort, easing = ChayaMotion.EasingExit)

@Composable
fun ChayaNavHost(navController: NavHostController) {
    // Chaya's places (Stage): Home first; Browse and the Library over it, one at a time, so Back from either is Home.
    // Which is shown is the back stack's top, never a flag kept beside it.
    fun go(place: Place) {
        val route = when (place) {
            Place.HOME -> Screen.Home.route
            Place.BROWSE -> Screen.Browser.route
            Place.LIBRARY -> Screen.Downloads.route
        }
        val shown = navController.currentDestination?.route == route
        val onStack = runCatching { navController.getBackStackEntry(route) }.isSuccess
        when (PlaceStep.of(shown, onStack)) {
            PlaceStep.STAY -> Unit
            PlaceStep.GO_BACK -> navController.popBackStack(route, inclusive = false)
            PlaceStep.OPEN -> navController.navigate(route) {
                popUpTo(Screen.Home.route)
                launchSingleTop = true
            }
        }
    }

    // A link shared to Chaya is opened by the browser; brought forward, or opened, whatever is showing. The browser's
    // pages live in WebViewHolder, not on its screen, so they are as they were left.
    val sharedLink by SharedLinks.pending.collectAsState()
    LaunchedEffect(sharedLink) {
        if (sharedLink != null) go(Place.BROWSE)
    }
    // A page picked on the History or Bookmarks screen, or typed into Home's "+", is opened by the browser too.
    val pageToOpen by PagesToOpen.pending.collectAsState()
    LaunchedEffect(pageToOpen) {
        if (pageToOpen != null) go(Place.BROWSE)
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = {
            fadeIn(enterTween()) + scaleIn(initialScale = 0.965f, animationSpec = enterTween())
        },
        exitTransition = { fadeOut(exitTween()) },
        popEnterTransition = {
            fadeIn(enterTween()) + scaleIn(initialScale = 0.965f, animationSpec = enterTween())
        },
        popExitTransition = { fadeOut(exitTween()) }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onPlace = ::go,
                onPlay = { taskId -> navController.navigate(Screen.playerRoute(taskId)) },
            )
        }
        composable(Screen.Browser.route) {
            BrowserScreen(
                // Over the page, so Back goes back to it
                onNavigateToDownloads = { navController.navigate(Screen.Downloads.route) { launchSingleTop = true } },
                onNavigateHome = { go(Place.HOME) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToBookmarks = { navController.navigate(Screen.Bookmarks.route) },
            )
        }
        composable(Screen.Downloads.route) {
            val themeSettings = (LocalContext.current.applicationContext as ChayaApplication).themeSettings
            val themeMode by themeSettings.mode.collectAsState()
            DownloadsScreen(
                onNavigateBack = { navController.popBackStack() },
                onPlay = { taskId ->
                    navController.navigate(Screen.playerRoute(taskId))
                },
                onPlayAt = { taskId, atMs -> navController.navigate(Screen.playerRoute(taskId, atMs)) },
                onNavigateToDiagnostics = {
                    navController.navigate(Screen.Diagnostics.route)
                },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToBookmarks = { navController.navigate(Screen.Bookmarks.route) },
                onNavigateToAiModel = { navController.navigate(Screen.AiModel.route) },
                themeMode = themeMode,
                onThemeModeChange = themeSettings::set,
                onPlace = ::go,
            )
        }
        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument(Screen.PLAYER_ARGS) { type = NavType.LongType },
                navArgument(Screen.PLAYER_AT) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            )
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getLong(Screen.PLAYER_ARGS) ?: -1L
            val at = backStackEntry.arguments?.getLong(Screen.PLAYER_AT)?.takeIf { it >= 0 }
            PlayerScreen(taskId = taskId, startAtMs = at, onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.History.route) {
            HistoryScreen(onNavigateBack = { navController.popBackStack() }, onOpen = PagesToOpen::open)
        }
        composable(Screen.AiModel.route) {
            com.chaya.app.ai.AiModelScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Bookmarks.route) {
            BookmarksScreen(onNavigateBack = { navController.popBackStack() }, onOpen = PagesToOpen::open)
        }
    }
}

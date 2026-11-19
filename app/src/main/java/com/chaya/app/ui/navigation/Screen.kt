package com.chaya.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object Browser : Screen("browser", "Browser", Icons.Default.Public)
    data object Downloads : Screen("downloads", "Downloads", Icons.Default.CloudDownload)
    data object Player : Screen("player/{taskId}?at={at}", "Player", Icons.Default.PlayArrow)
    data object Diagnostics : Screen("diagnostics", "Diagnostics", Icons.Default.Description)
    data object History : Screen("history", "History", Icons.Default.History)
    data object Bookmarks : Screen("bookmarks", "Bookmarks", Icons.Default.Star)
    data object AiModel : Screen("ai-model", "AI model", Icons.Default.AutoAwesome)

    companion object {
        const val PLAYER_ARGS = "taskId"
        /** Where the player starts, in milliseconds; left out, where the video was left. */
        const val PLAYER_AT = "at"
        fun playerRoute(taskId: Long, atMs: Long? = null) = if (atMs == null) "player/$taskId" else "player/$taskId?at=$atMs"
    }
}

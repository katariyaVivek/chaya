package com.chaya.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Browser : Screen("browser", "Browser", Icons.Default.Public)
    data object Downloads : Screen("downloads", "Downloads", Icons.Default.CloudDownload)
    data object Player : Screen("player/{taskId}", "Player", Icons.Default.PlayArrow)
    data object Diagnostics : Screen("diagnostics", "Diagnostics", Icons.Default.Description)
    data object History : Screen("history", "History", Icons.Default.History)
    data object Bookmarks : Screen("bookmarks", "Bookmarks", Icons.Default.Star)

    companion object {
        const val PLAYER_ARGS = "taskId"
        fun playerRoute(taskId: Long) = "player/$taskId"
    }
}

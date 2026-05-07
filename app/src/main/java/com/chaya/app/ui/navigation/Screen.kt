package com.chaya.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Public
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Browser : Screen("browser", "Browser", Icons.Default.Public)
    data object Downloads : Screen("downloads", "Downloads", Icons.Default.CloudDownload)
}

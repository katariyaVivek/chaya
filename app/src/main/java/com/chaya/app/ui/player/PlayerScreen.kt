package com.chaya.app.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.chaya.app.ChayaApplication
import com.chaya.app.ui.theme.LocalChayaColors

/**
 * Minimal in-app player for completed HLS/DASH downloads.
 *
 * Media3 stores stream downloads in its segment cache (there is no single
 * merged file), so playback goes through the same CacheDataSource the
 * downloader used — already-downloaded segments play offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    taskId: Long,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as ChayaApplication

    val fileName = remember {
        app.downloadManager.downloads.value.firstOrNull { it.id == taskId }?.fileName ?: "Playback"
    }
    val playback = remember { app.downloadManager.streamPlaybackFor(taskId) }

    if (playback == null) {
        DisposableEffect(Unit) { onDispose { onNavigateBack() } }
        return
    }

    val player = remember {
        ExoPlayer.Builder(context, DefaultRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(playback.factory))
            .build()
    }

    DisposableEffect(player) {
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(playback.uri)
                .apply { playback.mimeType?.let { setMimeType(it) } }
                .build()
        )
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }

    Scaffold(
        containerColor = LocalChayaColors.current.playerBackdrop,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        fileName,
                        maxLines = 1,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White.copy(alpha = 0.92f),
                    navigationIconContentColor = Color.White.copy(alpha = 0.92f)
                )
            )
        }
    ) { padding ->
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setShowSubtitleButton(true)
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}

package com.chaya.app.ui.player

import android.net.Uri
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.chaya.app.ChayaApplication
import com.chaya.app.R
import com.chaya.app.download.DownloadManager
import com.chaya.app.download.DownloadTask
import com.chaya.app.downloads.displayTitle
import com.chaya.app.library.SourceSite
import com.chaya.app.library.siteOf
import com.chaya.app.streaming.StreamMime
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant
import com.chaya.app.ui.theme.PlayerBackdrop
import kotlinx.coroutines.delay
import java.io.File

/** How far −10 and +10 move. */
private const val SKIP_MS = 10_000L

/**
 * Chaya's player (Stage, after the approved phone design): a saved video plays here, its picture with a glow under it,
 * its name and what it is, and a bar of controls floating at the foot: the seek bar, −10, play in ink, +10. It starts
 * where it was left ([Watched]) and keeps where it is left again, for next time and for Home's "Continue".
 *
 * A saved file plays from the file; a stream saved only in Media3's cache plays through the cache the download used.
 * Always dark, whichever theme: the picture is the light.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    taskId: Long,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as ChayaApplication
    val task = remember { app.downloadManager.downloads.value.firstOrNull { it.id == taskId } }
    val positions = app.playbackPositions
    val source = remember { task?.let { sourceOf(app, it) } }

    if (task == null || source == null) {
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }

    val player = remember {
        ExoPlayer.Builder(context, DefaultRenderersFactory(context))
            .apply { source.cached?.let { setMediaSourceFactory(DefaultMediaSourceFactory(it.factory)) } }
            .build()
    }
    var status by remember { mutableStateOf(PlayerStatus.PLAYING) }
    var atMs by remember { mutableLongStateOf(0L) }
    var lengthMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }

    fun keepWhere() {
        // A file that would not play keeps where it was left before
        if (status == PlayerStatus.FAILED) return
        val length = player.duration.takeIf { it > 0 } ?: lengthMs
        positions.put(taskId, Watched(player.currentPosition, length, System.currentTimeMillis()))
    }

    DisposableEffect(player) {
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(source.uri)
                .apply { StreamMime.forMedia3(source.mimeType)?.let { setMimeType(it) } }
                .build()
        )
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status = PlayerStatus.FAILED
            }
        }
        player.addListener(listener)
        positions.watched.value[taskId]?.resumeAtMs?.takeIf { it > 0 }?.let { player.seekTo(it) }
        player.prepare()
        player.playWhenReady = true
        onDispose {
            keepWhere()
            player.removeListener(listener)
            player.release()
        }
    }

    // Leaving the app pauses it, and keeps where it was left
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                player.pause()
                keepWhere()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The bar follows the video: read four times a second
    LaunchedEffect(player) {
        while (true) {
            atMs = player.currentPosition
            lengthMs = player.duration.takeIf { it > 0 } ?: lengthMs
            if (status != PlayerStatus.FAILED) {
                val going = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
                status = if (going) PlayerStatus.PLAYING else PlayerStatus.PAUSED
            }
            delay(250)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(NightGround)) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(onClick = onNavigateBack, label = "Close player") {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = NightInk)
                }
                if (task.filePath != null || task.exportedUri != null) {
                    RoundButton(onClick = { share(app, task) }, label = "Share") {
                        Icon(Icons.Default.Share, contentDescription = null, tint = NightInk, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // The picture, the light of the screen
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(PlayerBackdrop),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    // A TextureView inside (the layout says so), so the picture keeps the frame's rounded corners
                    // and fades with the screen, which a SurfaceView does not
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.stage_player_view, FrameLayout(ctx), false) as PlayerView).apply {
                            this.player = player
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (status == PlayerStatus.FAILED) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                        Text("Chaya can't play this file.", color = NightInk, style = TextStyle(fontSize = 15.sp))
                        TextButton(onClick = { openElsewhere(app, task) }) {
                            Text("Open in another app", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = displayTitle(task),
                    color = NightInk,
                    style = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = factsOf(task),
                    color = NightMuted,
                    style = TextStyle(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
        }

        Controls(
            atMs = seeking?.let { (it * lengthMs).toLong() } ?: atMs,
            lengthMs = lengthMs,
            playing = status == PlayerStatus.PLAYING,
            onSeeking = { seeking = it },
            onSeekDone = {
                seeking?.let { player.seekTo((it * lengthMs).toLong()) }
                seeking = null
            },
            onBack = { player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0)) },
            onForward = { player.seekTo((player.currentPosition + SKIP_MS).coerceAtMost(lengthMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) },
            onPlayPause = {
                if (player.isPlaying) {
                    player.pause()
                    keepWhere()
                } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** What the player is doing: one value, so it cannot be playing and failed at once. */
private enum class PlayerStatus { PLAYING, PAUSED, FAILED }

/** The bar at the foot: the seek bar, then the time, −10, play in ink, +10 and the length. */
@Composable
private fun Controls(
    atMs: Long,
    lengthMs: Long,
    playing: Boolean,
    onSeeking: (Float) -> Unit,
    onSeekDone: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = NightContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, NightOutlineVariant),
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
            Slider(
                value = if (lengthMs > 0) (atMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f,
                onValueChange = onSeeking,
                onValueChangeFinished = onSeekDone,
                enabled = lengthMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = NightInk,
                    activeTrackColor = NightInk,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                    disabledThumbColor = NightMuted,
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f),
                ),
                modifier = Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "Seek" },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    clockOf(atMs),
                    color = NightInk,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.width(56.dp),
                )
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back 10 seconds" }) {
                    Text("−10", color = NightInk, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
                Surface(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    color = NightInk,
                    contentColor = NightGround,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = if (playing) "Pause" else "Play" },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                    }
                }
                TextButton(onClick = onForward, modifier = Modifier.semantics { contentDescription = "Forward 10 seconds" }) {
                    Text("+10", color = NightInk, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
                Text(
                    if (lengthMs > 0) clockOf(lengthMs) else "",
                    color = NightMuted,
                    style = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.width(56.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .semantics { contentDescription = label },
    ) { content() }
}

/** What plays: a saved file from the file; a stream saved only in Media3's cache through that cache. */
private data class Source(
    val uri: Uri,
    val mimeType: String?,
    /** How Media3's cache is read, for a stream only the cache holds. */
    val cached: DownloadManager.StreamPlayback? = null,
)

private fun sourceOf(app: ChayaApplication, task: DownloadTask): Source? {
    // A file is read for what it is: a stream saved as one MP4 still carries the stream's type
    task.filePath?.let(::File)?.takeIf { it.isFile }?.let { return Source(it.toUri(), null) }
    task.exportedUri?.let { return Source(it.toUri(), null) }
    return app.downloadManager.streamPlaybackFor(task.id)?.let { Source(it.uri, it.mimeType, it) }
}

/** "YouTube · 1080p": where it came from, and its quality when known. */
private fun factsOf(task: DownloadTask): String =
    listOfNotNull(siteOf(task).takeIf { it != SourceSite.OTHER }?.label, task.qualityHeight?.takeIf { it >= 144 }?.let { "${it}p" }).joinToString(" · ")

private fun openElsewhere(app: ChayaApplication, task: DownloadTask) {
    val intent = app.downloadManager.openIntentFor(task) ?: return
    runCatching { app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun share(app: ChayaApplication, task: DownloadTask) {
    val uri = runCatching { com.chaya.app.library.LibraryIntents.uriFor(app, task) }.getOrNull() ?: return
    val intent = com.chaya.app.library.LibraryIntents.shareIntent(
        listOf(uri),
        listOf(com.chaya.app.library.LibraryIntents.mimeOf(task.fileName, task.mimeType)),
    ) ?: return
    runCatching { app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

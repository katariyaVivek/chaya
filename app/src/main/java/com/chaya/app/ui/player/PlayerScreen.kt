package com.chaya.app.ui.player

import android.net.Uri
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.chaya.app.subtitles.Chapter
import com.chaya.app.subtitles.Chapters
import com.chaya.app.subtitles.SavedSubtitles
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant
import com.chaya.app.ui.theme.PlayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    onNavigateBack: () -> Unit,
    /** Where to start, in milliseconds (a line found by the Library's search); null starts where it was left. */
    startAtMs: Long? = null,
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
    // The site's subtitles kept with the video, read from their file off the main thread; null without any
    val subtitles by produceState<SavedSubtitles?>(null, taskId) {
        value = withContext(Dispatchers.IO) { app.subtitles.load(taskId) }
    }
    // Its chapters, as the site named them; none for most videos
    val chapters by produceState(emptyList<Chapter>(), taskId) {
        value = withContext(Dispatchers.IO) { app.subtitles.loadChapters(taskId) }
    }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showSubtitles by rememberSaveable { mutableStateOf(true) }
    val hasSubtitles by rememberUpdatedState(subtitles != null)

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
        (startAtMs ?: positions.watched.value[taskId]?.resumeAtMs)?.takeIf { it > 0 }?.let { player.seekTo(it) }
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

    // The bar follows the video: read four times a second, ten while subtitles show, so a line comes on time
    LaunchedEffect(player) {
        while (true) {
            atMs = player.currentPosition
            lengthMs = player.duration.takeIf { it > 0 } ?: lengthMs
            if (status != PlayerStatus.FAILED) {
                val going = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
                status = if (going) PlayerStatus.PLAYING else PlayerStatus.PAUSED
            }
            delay(if (hasSubtitles) 100 else 250)
        }
    }
    val currentLine = subtitles?.lineAt(atMs)
    val saying = currentLine?.let { subtitles?.lines?.getOrNull(it)?.text }?.takeIf { showSubtitles && status != PlayerStatus.FAILED }

    // Upright or across, from the screen's shape; the full-screen button turns the phone, and leaving the player lets
    // it turn as it likes again
    val layout = PlayerLayout.of(LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE)
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // Across, the picture has the whole screen: the status and navigation bars come back with a swipe
    DisposableEffect(activity, layout) {
        val window = activity?.window
        val bars = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (layout == PlayerLayout.ACROSS) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Across, the controls step aside while the video plays untouched, and a tap on the picture brings them back
    var controlsShown by remember { mutableStateOf(true) }
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(layout, controlsShown, status, touched) {
        if (layout.controlsStepAside && controlsShown && status == PlayerStatus.PLAYING) {
            delay(PlayerLayout.CONTROLS_STAY_MS)
            controlsShown = false
        }
    }
    LaunchedEffect(layout) { controlsShown = true }

    val toggleSubtitles: () -> Unit = {
        if (subtitles != null) {
            showSubtitles = !showSubtitles
        } else {
            Toast.makeText(context, "No subtitles were saved with this video", Toast.LENGTH_SHORT).show()
        }
    }
    val turn: () -> Unit = { activity?.requestedOrientation = layout.turnTo }
    val controls = ControlActions(
        onSeeking = { seeking = it; touched = System.currentTimeMillis() },
        onSeekDone = {
            seeking?.let { player.seekTo((it * lengthMs).toLong()) }
            seeking = null
        },
        onBack = {
            player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0))
            touched = System.currentTimeMillis()
        },
        onForward = {
            player.seekTo((player.currentPosition + SKIP_MS).coerceAtMost(lengthMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
            touched = System.currentTimeMillis()
        },
        onPlayPause = {
            if (player.isPlaying) {
                player.pause()
                keepWhere()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
            touched = System.currentTimeMillis()
        },
        onSubtitles = toggleSubtitles,
        onTurn = turn,
    )
    val shownAt = seeking?.let { (it * lengthMs).toLong() } ?: atMs
    val playing = status == PlayerStatus.PLAYING
    // The chapter the seek bar is at, while it is dragged too, so the person sees where they will land
    val currentChapter = Chapters.at(chapters, shownAt)
    val marks = chapterMarks(chapters, lengthMs)

    @Composable
    fun Picture(modifier: Modifier, subtitlesLift: androidx.compose.ui.unit.Dp) {
        Box(modifier = modifier.background(PlayerBackdrop), contentAlignment = Alignment.Center) {
            AndroidView(
                // A TextureView inside (the layout says so), so the picture keeps the frame's rounded corners
                // and fades with the screen, which a SurfaceView does not
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.stage_player_view, FrameLayout(ctx), false) as PlayerView).apply {
                        this.player = player
                    }
                },
                // Turned, the picture is drawn by a new view: the old one lets the player go
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (saying != null) {
                SubtitleOverlay(saying, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = subtitlesLift))
            }
            if (status == PlayerStatus.FAILED) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text("Chaya can't play this file.", color = NightInk, style = TextStyle(fontSize = 15.sp))
                    TextButton(onClick = { openElsewhere(app, task) }) {
                        Text("Open in another app", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }

    when (layout) {
        PlayerLayout.ACROSS -> Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Picture(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null) {
                        controlsShown = !controlsShown
                        touched = System.currentTimeMillis()
                    },
                // Above the slim bar while it shows
                subtitlesLift = if (controlsShown) 72.dp else 8.dp,
            )
            AnimatedVisibility(
                visible = controlsShown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundButton(onClick = onNavigateBack, label = "Close player") {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = NightInk)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayTitle(task),
                                color = NightInk,
                                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            currentChapter?.let { chapters.getOrNull(it) }?.let { chapter ->
                                Text(
                                    text = chapter.title,
                                    color = NightMuted,
                                    style = TextStyle(fontSize = 13.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable(onClickLabel = "Chapters") { showChapters = true },
                                )
                            }
                        }
                        if (task.filePath != null || task.exportedUri != null) {
                            RoundButton(onClick = { share(app, task) }, label = "Share") {
                                Icon(Icons.Default.Share, contentDescription = null, tint = NightInk, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    SlimControls(
                        atMs = shownAt,
                        lengthMs = lengthMs,
                        marks = marks,
                        playing = playing,
                        subtitles = if (subtitles == null) null else showSubtitles,
                        actions = controls,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        PlayerLayout.UPRIGHT -> Box(modifier = Modifier.fillMaxSize().background(NightGround)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubtitlesButton(subtitles = if (subtitles == null) null else showSubtitles, onClick = toggleSubtitles)
                        if (task.filePath != null || task.exportedUri != null) {
                            RoundButton(onClick = { share(app, task) }, label = "Share") {
                                Icon(Icons.Default.Share, contentDescription = null, tint = NightInk, modifier = Modifier.size(20.dp))
                            }
                        }
                        RoundButton(onClick = turn, label = "Full screen") {
                            Icon(Icons.Default.Fullscreen, contentDescription = null, tint = NightInk, modifier = Modifier.size(22.dp))
                        }
                    }
                }

                // The picture, the light of the screen
                Picture(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)),
                    subtitlesLift = 0.dp,
                )

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
                    if (chapters.isNotEmpty()) {
                        ChapterButton(
                            chapters = chapters,
                            current = currentChapter,
                            onClick = { showChapters = true },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                val said = subtitles
                if (said != null) {
                    Transcript(
                        lines = said.lines,
                        language = said.language,
                        current = currentLine,
                        onSeek = { player.seekTo(it) },
                        // Clear of the controls floating at the foot
                        bottomPadding = 150.dp,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }

            Controls(
                atMs = shownAt,
                lengthMs = lengthMs,
                marks = marks,
                playing = playing,
                actions = controls,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showChapters && chapters.isNotEmpty()) {
        ChapterSheet(
            chapters = chapters,
            current = currentChapter,
            onSeek = { at ->
                player.seekTo(at)
                showChapters = false
                touched = System.currentTimeMillis()
            },
            onDismiss = { showChapters = false },
        )
    }
}

/** What the player is doing: one value, so it cannot be playing and failed at once. */
private enum class PlayerStatus { PLAYING, PAUSED, FAILED }

/** What the controls do, the same in either layout. */
private class ControlActions(
    val onSeeking: (Float) -> Unit,
    val onSeekDone: () -> Unit,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onPlayPause: () -> Unit,
    val onSubtitles: () -> Unit,
    val onTurn: () -> Unit,
)

/** The seek bar; a video with chapters has its track in pieces, one a chapter ([marks], from 0 to 1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(atMs: Long, lengthMs: Long, marks: List<Float>, actions: ControlActions, modifier: Modifier = Modifier) {
    val value = if (lengthMs > 0) (atMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
    val enabled = lengthMs > 0
    val colors = SliderDefaults.colors(
        thumbColor = NightInk,
        activeTrackColor = NightInk,
        inactiveTrackColor = Color.White.copy(alpha = 0.22f),
        disabledThumbColor = NightMuted,
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f),
    )
    val seekBar = modifier.height(28.dp).semantics { contentDescription = "Seek" }
    if (marks.isEmpty()) {
        Slider(
            value = value,
            onValueChange = actions.onSeeking,
            onValueChangeFinished = actions.onSeekDone,
            enabled = enabled,
            colors = colors,
            modifier = seekBar,
        )
    } else {
        Slider(
            value = value,
            onValueChange = actions.onSeeking,
            onValueChangeFinished = actions.onSeekDone,
            enabled = enabled,
            colors = colors,
            track = { state -> ChapterTrack(fraction = state.value, marks = marks, enabled = enabled) },
            modifier = seekBar,
        )
    }
}

@Composable
private fun PlayButton(playing: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = NightInk,
        contentColor = NightGround,
        modifier = Modifier.size(size).semantics { contentDescription = if (playing) "Pause" else "Play" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
        }
    }
}

@Composable
private fun SkipButton(text: String, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Text(text, color = NightInk, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}

/** The bar at the foot, upright: the seek bar, then the time, −10, play in ink, +10 and the length. */
@Composable
private fun Controls(
    atMs: Long,
    lengthMs: Long,
    marks: List<Float>,
    playing: Boolean,
    actions: ControlActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = NightContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, NightOutlineVariant),
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp)) {
            SeekBar(atMs, lengthMs, marks, actions, Modifier.fillMaxWidth())
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
                SkipButton("−10", "Back 10 seconds", actions.onBack)
                PlayButton(playing, 48.dp, actions.onPlayPause)
                SkipButton("+10", "Forward 10 seconds", actions.onForward)
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

/**
 * The bar across the foot of a full-screen picture: one row, so it covers a strip of the picture rather than half of
 * it. Play, −10, +10, the time, the seek bar, the length, subtitles and back to upright.
 */
@Composable
private fun SlimControls(
    atMs: Long,
    lengthMs: Long,
    marks: List<Float>,
    playing: Boolean,
    /** Whether subtitles show; null when the video has none. */
    subtitles: Boolean?,
    actions: ControlActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(playing, 40.dp, actions.onPlayPause)
        SkipButton("−10", "Back 10 seconds", actions.onBack)
        SkipButton("+10", "Forward 10 seconds", actions.onForward)
        Text(clockOf(atMs), color = NightInk, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        SeekBar(atMs, lengthMs, marks, actions, Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(if (lengthMs > 0) clockOf(lengthMs) else "", color = NightMuted, style = TextStyle(fontSize = 13.sp))
        SubtitlesButton(subtitles = subtitles, onClick = actions.onSubtitles)
        RoundButton(onClick = actions.onTurn, label = "Exit full screen") {
            Icon(Icons.Default.FullscreenExit, contentDescription = null, tint = NightInk, modifier = Modifier.size(22.dp))
        }
    }
}

/** Subtitles on or off; dimmed, and saying so when tapped, for a video saved without any. */
@Composable
private fun SubtitlesButton(subtitles: Boolean?, onClick: () -> Unit) {
    RoundButton(
        onClick = onClick,
        label = when (subtitles) {
            true -> "Hide subtitles"
            false -> "Show subtitles"
            null -> "No subtitles"
        },
    ) {
        Icon(
            if (subtitles == true) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
            contentDescription = null,
            tint = if (subtitles == null) NightMuted else NightInk,
            modifier = Modifier.size(20.dp),
        )
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

/** The activity a composable's context belongs to, through any wrappers. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.chaya.app.download.isOwn
import com.chaya.app.downloads.displayTitle
import com.chaya.app.library.SourceSite
import com.chaya.app.library.siteOf
import com.chaya.app.streaming.StreamMime
import com.chaya.app.subtitles.Chapter
import com.chaya.app.subtitles.Chapters
import com.chaya.app.subtitles.Retime
import com.chaya.app.subtitles.Retiming
import com.chaya.app.subtitles.SavedSubtitles
import com.chaya.app.subtitles.SubtitleEdits
import com.chaya.app.subtitles.SubtitleFixes
import com.chaya.app.speech.Making
import com.chaya.app.speech.Whisper
import com.chaya.app.subtitles.Clip
import com.chaya.app.subtitles.Clips
import com.chaya.app.subtitles.Finding
import com.chaya.app.subtitles.NewClip
import com.chaya.app.subtitles.FoundHighlights
import com.chaya.app.subtitles.Translating
import com.chaya.app.subtitles.VideoTranslations
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant
import com.chaya.app.ui.theme.PlayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** Opens Settings › AI model, from *Translate* before a model is set up. */
    onOpenAiModel: () -> Unit = {},
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
    // The site's subtitles kept with the video, read from their file off the main thread; null without any. Putting
    // them right replaces them
    var subtitles by remember { mutableStateOf<SavedSubtitles?>(null) }
    LaunchedEffect(taskId) {
        subtitles = withContext(Dispatchers.IO) { app.subtitles.load(taskId) }
    }
    // Its translations, a version for each language and model, read again as a translation goes on
    val translating by app.translations.states.collectAsState()
    val translationState = translating[taskId] ?: Translating.Idle
    var translations by remember { mutableStateOf(VideoTranslations()) }
    LaunchedEffect(taskId, translationState) {
        translations = withContext(Dispatchers.IO) { app.translationStore.load(taskId) }
    }
    var showTranslate by rememberSaveable { mutableStateOf(false) }
    // The moments worth watching, found by the person's model, read again when a search ends
    val findingAll by app.highlights.states.collectAsState()
    val finding = findingAll[taskId] ?: Finding.Idle
    var found by remember { mutableStateOf<FoundHighlights?>(null) }
    LaunchedEffect(taskId, finding) {
        found = withContext(Dispatchers.IO) { app.highlightStore.load(taskId) }
    }
    var showHighlights by rememberSaveable { mutableStateOf(false) }
    // A video saved without subtitles can have them made on the phone; when that ends, they are read again
    val makingAll by app.speech.states.collectAsState()
    val making = makingAll[taskId] ?: Making.Idle
    LaunchedEffect(making) {
        if (making == Making.Idle && subtitles == null) subtitles = withContext(Dispatchers.IO) { app.subtitles.load(taskId) }
    }
    // Its clips; lines being chosen in the transcript (from where the long press was, to the line tapped last); the
    // clip open on its timeline; and the clip playing, which stops at its end
    var clips by remember { mutableStateOf(emptyList<Clip>()) }
    LaunchedEffect(taskId) { clips = withContext(Dispatchers.IO) { app.clipStore.load(taskId) } }
    var choosingFrom by remember { mutableStateOf<Int?>(null) }
    var choosingTo by remember { mutableStateOf<Int?>(null) }
    var showClips by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Clip?>(null) }
    var playingClip by remember { mutableStateOf<Clip?>(null) }
    // A clip being saved as a video or SRT, and the work doing it (Stop cancels it)
    var exporting by remember { mutableStateOf<Exporting>(Exporting.Idle) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // The line being put right, by its index; where a line was last said to be "now" (for a stretch); and what the last
    // change did
    var fixing by remember { mutableStateOf<Int?>(null) }
    var synced by remember { mutableStateOf<Long?>(null) }
    var fixed by remember { mutableStateOf<Fixed?>(null) }
    val scope = rememberCoroutineScope()
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
    // The version shown under the original, when it still fits the lines (a line taken out since changes their count)
    val translated = subtitles?.let { translations.shown(it.lines.size) }?.lines
    val sayingTranslated = currentLine?.let { translated?.getOrNull(it) }?.takeIf { saying != null }

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

    /** Changes the subtitles by [change], shows them as they are now, and says [said] with Undo. */
    fun changeSubtitles(said: String, change: SubtitleEdits.Change, after: () -> Unit = {}) {
        val language = subtitles?.language ?: return
        scope.launch {
            val lines = withContext(Dispatchers.IO) { app.subtitleEdits.change(taskId, change) }
            fixed = if (lines == null) {
                Fixed("Chaya couldn't change the subtitles.", canUndo = false)
            } else {
                subtitles = SavedSubtitles(language, lines)
                after()
                Fixed(said, canUndo = app.subtitleEdits.undoable(taskId) > 0)
            }
        }
    }

    fun undoChange() {
        val language = subtitles?.language ?: return
        scope.launch {
            val lines = withContext(Dispatchers.IO) { app.subtitleEdits.undo(taskId) }
            if (lines != null) {
                subtitles = SavedSubtitles(language, lines)
                synced = null
            }
            fixed = Fixed(if (lines == null) "Nothing to undo." else "Undone.", canUndo = app.subtitleEdits.undoable(taskId) > 0)
        }
    }

    // What a change did shows for a while, then goes
    LaunchedEffect(fixed) {
        if (fixed != null) {
            delay(FIXED_SHOWS_MS)
            fixed = null
        }
    }
    // The chapter the seek bar is at, while it is dragged too, so the person sees where they will land
    val currentChapter = Chapters.at(chapters, shownAt)
    val marks = chapterMarks(chapters, lengthMs)
    val spans = highlightSpans(found?.items.orEmpty(), lengthMs)
    val chosen = subtitles?.let { said -> choosingFrom?.let { from -> Clips.chosen(said.lines.size, from, choosingTo ?: from) } }
    val duration = lengthMs.takeIf { it > 0 }

    // A clip played stops at its end
    LaunchedEffect(atMs, playingClip) {
        val clip = playingClip ?: return@LaunchedEffect
        if (atMs >= clip.end) {
            player.pause()
            playingClip = null
        }
    }

    /** Keeps [wanted] as clips and opens the first on its timeline. */
    fun makeClips(wanted: List<NewClip>) {
        val (kept, ids) = Clips.add(clips, wanted, duration)
        clips = kept
        scope.launch(Dispatchers.IO) { app.clipStore.keep(taskId, kept) }
        editing = ids.firstOrNull()?.let { id -> kept.firstOrNull { it.id == id } }
        if (ids.firstOrNull() == null) Toast.makeText(context, "Chaya couldn't make that clip.", Toast.LENGTH_SHORT).show()
    }

    fun keepClips(changed: List<Clip>?) {
        val kept = changed ?: return
        clips = kept
        scope.launch(Dispatchers.IO) { app.clipStore.keep(taskId, kept) }
    }

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
                SubtitleOverlay(saying, under = sayingTranslated, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = subtitlesLift))
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
                        spans = spans,
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
                        if (subtitles != null) {
                            RoundButton(onClick = { showHighlights = true }, label = "Highlights") {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NightInk, modifier = Modifier.size(20.dp))
                            }
                            RoundButton(onClick = { showTranslate = true }, label = "Translate") {
                                Icon(Icons.Default.Translate, contentDescription = null, tint = NightInk, modifier = Modifier.size(20.dp))
                            }
                        }
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
                    if (chapters.isNotEmpty() || clips.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                            if (chapters.isNotEmpty()) {
                                ChapterButton(chapters = chapters, current = currentChapter, onClick = { showChapters = true })
                            }
                            if (clips.isNotEmpty()) ClipsButton(count = clips.size, onClick = { showClips = true })
                        }
                    }
                }
                val said = subtitles
                if (said != null) {
                    Transcript(
                        lines = said.lines,
                        language = said.language,
                        translated = translated,
                        chosen = chosen,
                        onChoose = { index ->
                            if (choosingFrom == null) choosingFrom = index
                            choosingTo = index
                        },
                        current = currentLine,
                        onSeek = { player.seekTo(it) },
                        onFix = { index ->
                            // Held still while it is put right, so "said now" is where the person stopped it
                            player.pause()
                            fixing = index
                        },
                        // Clear of the controls floating at the foot
                        bottomPadding = 150.dp,
                        modifier = Modifier.weight(1f),
                    )
                } else if (source.cached == null && Whisper.available) {
                    Column(Modifier.weight(1f)) {
                        MakeSubtitlesCard(
                            state = making,
                            fetched = app.speechModels::isFetched,
                            onMake = { model -> app.speech.start(taskId, model, lengthMs) },
                            onStop = { app.speech.stop(taskId) },
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }

            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                val lines = subtitles?.lines
                if (chosen != null && lines != null) {
                    ChosenBar(
                        count = chosen.last - chosen.first + 1,
                        start = lines[chosen.first].start,
                        end = lines[chosen.last].end,
                        onClip = {
                            makeClips(listOf(NewClip(Clips.nameFrom(lines[chosen.first].text), lines[chosen.first].start, lines[chosen.last].end)))
                            choosingFrom = null
                        },
                        onFix = if (chosen.first == chosen.last) {
                            {
                                player.pause()
                                fixing = chosen.first
                                choosingFrom = null
                            }
                        } else null,
                        onCancel = { choosingFrom = null },
                    )
                }
                fixed?.let { shown ->
                    FixedBar(said = shown.said, canUndo = shown.canUndo, onUndo = ::undoChange)
                }
                Controls(
                    atMs = shownAt,
                    lengthMs = lengthMs,
                    marks = marks,
                    spans = spans,
                    playing = playing,
                    actions = controls,
                )
            }
        }
    }

    val fixingLine = fixing?.let { subtitles?.lines?.getOrNull(it) }
    if (fixingLine != null) {
        val index = fixing ?: 0
        FixSheet(
            line = fixingLine,
            atMs = atMs,
            synced = synced,
            onKeep = { text ->
                fixing = null
                changeSubtitles(if (text.isBlank()) "Line taken out." else "Line corrected.", { SubtitleFixes.withText(it, index, text) })
            },
            onSaidNow = {
                fixing = null
                val at = player.currentPosition
                val before = synced
                val asked = if (before != null && saidNowStretches(before, fixingLine.start)) {
                    Retiming.Through(fixed = before, from = fixingLine.start, to = at)
                } else {
                    Retiming.Shift(at - fixingLine.start)
                }
                val said = if (asked is Retiming.Through) {
                    "Lines stretched to fit between ${clockOf(asked.fixed)} and ${clockOf(at)}."
                } else {
                    SubtitleFixes.moved(at - fixingLine.start)
                }
                changeSubtitles(said, { Retime.of(asked).apply(it) }, after = { synced = at })
            },
            onNudge = { by ->
                fixing = null
                changeSubtitles(SubtitleFixes.moved(by), { Retime.shift(by).apply(it) })
            },
            onDismiss = { fixing = null },
        )
    }

    val from = subtitles?.language
    if (showTranslate && from != null) {
        TranslateSheet(
            from = from,
            translations = translations,
            state = translationState,
            onTranslate = { to -> app.translations.start(taskId, displayTitle(task), to) },
            onStop = { app.translations.stop(taskId) },
            onShow = { index ->
                scope.launch { translations = withContext(Dispatchers.IO) { app.translationStore.show(taskId, index) } }
            },
            onSetUp = {
                showTranslate = false
                onOpenAiModel()
            },
            onDismiss = { showTranslate = false },
        )
    }

    if (showHighlights && subtitles != null) {
        HighlightsSheet(
            found = found,
            state = finding,
            onPlay = { highlight ->
                showHighlights = false
                player.seekTo(highlight.start)
                player.play()
            },
            onFind = { count -> app.highlights.start(taskId, displayTitle(task), count) },
            onStop = { app.highlights.stop(taskId) },
            onSetUp = {
                showHighlights = false
                onOpenAiModel()
            },
            onDismiss = { showHighlights = false },
            onClip = { highlight ->
                showHighlights = false
                makeClips(listOf(NewClip(highlight.title, highlight.start, highlight.end)))
            },
        )
    }

    if (showClips && clips.isNotEmpty()) {
        ClipsSheet(
            clips = clips,
            onOpen = { clip ->
                showClips = false
                editing = clip
            },
            onDismiss = { showClips = false },
        )
    }

    editing?.let { clip ->
        val lines = subtitles?.lines.orEmpty()
        ClipEditor(
            clip = clip,
            durationMs = lengthMs,
            atMs = atMs,
            edges = Clips.edges(lines, chapters, start = true) to Clips.edges(lines, chapters, start = false),
            videoId = taskId,
            file = source.uri.takeIf { source.cached == null },
            onPlay = { played ->
                player.seekTo(played.start)
                player.play()
                playingClip = played
            },
            onSeek = { player.seekTo(it) },
            onSave = { changed ->
                keepClips(Clips.change(clips, changed, duration))
                editing = null
            },
            onDelete = {
                keepClips(Clips.remove(clips, clip.id))
                editing = null
            },
            onDismiss = {
                exportJob?.cancel()
                exporting = Exporting.Idle
                editing = null
            },
            extra = { cut ->
                val said = subtitles
                ExportPanel(
                    hasSubtitles = said != null,
                    state = exporting,
                    onExport = { kind, size ->
                        val file = source.uri.takeIf { source.cached == null }
                        if (file == null && kind != ExportKind.SRT) {
                            exporting = Exporting.Failed("This video is kept as a stream. Save it as an MP4 from the Library first.")
                            return@ExportPanel
                        }
                        val lines = said?.let { com.chaya.app.subtitles.ClipExport.together(it.lines, translated) }.orEmpty()
                        val out = com.chaya.app.subtitles.ClipExport.fileFor(File(context.cacheDir, "clips"), displayTitle(task), cut.name, kind.extension)
                        exportJob = scope.launch {
                            exporting = Exporting.Running(0f)
                            exporting = try {
                                if (kind == ExportKind.SRT) {
                                    withContext(Dispatchers.IO) { ClipExporter.writeSrt(lines, cut.start, cut.end, out) }
                                } else {
                                    ClipExporter.export(
                                        context,
                                        ClipExporter.Job(file!!, cut.start, cut.end, if (kind == ExportKind.WITH_SUBTITLES) lines else emptyList(), size, out),
                                    ) { exporting = Exporting.Running(it) }
                                }
                                Exporting.Done(out, kind.mimeType)
                            } catch (e: ClipExportException) {
                                out.delete()
                                Exporting.Failed(e.message ?: "Chaya couldn't make the clip.")
                            } catch (e: java.io.IOException) {
                                out.delete()
                                Exporting.Failed("Chaya couldn't write the clip.")
                            } catch (stopped: kotlinx.coroutines.CancellationException) {
                                out.delete()
                                exporting = Exporting.Idle
                                throw stopped
                            }
                        }
                    },
                    onStop = { exportJob?.cancel() },
                    onShare = { file, mime ->
                        runCatching {
                            val share = com.chaya.app.library.LibraryIntents.shareIntent(listOf(com.chaya.app.library.LibraryIntents.uriForFile(app, file)), listOf(mime))
                            if (share != null) context.startActivity(share)
                        }.onFailure { Toast.makeText(context, "No app can take this", Toast.LENGTH_SHORT).show() }
                    },
                    onSave = { file, mime ->
                        scope.launch {
                            val saved = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                                com.chaya.app.library.LibraryIntents.saveToPhone(app, file, file.name, mime)
                            Toast.makeText(context, if (saved) "Saved ${file.name} to your phone" else "Couldn't save ${file.name}", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
        )
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

/** How long what a change to the subtitles did stays shown. */
private const val FIXED_SHOWS_MS = 6_000L

/** What the last change to the subtitles did, and whether it can be undone. */
private data class Fixed(val said: String, val canUndo: Boolean)

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

/**
 * The seek bar; a video with chapters has its track in pieces, one a chapter ([marks], from 0 to 1), and its highlights
 * ([spans]) are amber under it.
 */
@Composable
private fun SeekBar(
    atMs: Long,
    lengthMs: Long,
    marks: List<Float>,
    actions: ControlActions,
    modifier: Modifier = Modifier,
    spans: List<ClosedFloatingPointRange<Float>> = emptyList(),
) {
    // The highlights, as amber strokes just under the track
    Box(modifier = modifier) {
        SeekSlider(atMs, lengthMs, marks, actions, Modifier.fillMaxWidth())
        HighlightMarks(spans, Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekSlider(atMs: Long, lengthMs: Long, marks: List<Float>, actions: ControlActions, modifier: Modifier = Modifier) {
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
    spans: List<ClosedFloatingPointRange<Float>>,
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
            SeekBar(atMs, lengthMs, marks, actions, Modifier.fillMaxWidth(), spans)
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
    spans: List<ClosedFloatingPointRange<Float>>,
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
        SeekBar(atMs, lengthMs, marks, actions, Modifier.weight(1f).padding(horizontal = 8.dp), spans)
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

/** "YouTube · 1080p": where it came from, and its quality when known; "On this phone" for the person's own. */
private fun factsOf(task: DownloadTask): String =
    if (task.isOwn) "On this phone" else listOfNotNull(siteOf(task).takeIf { it != SourceSite.OTHER }?.label, task.qualityHeight?.takeIf { it >= 144 }?.let { "${it}p" }).joinToString(" · ")

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

package com.chaya.app.home

import com.chaya.app.library.SavedCard
import androidx.compose.material.icons.filled.Tune
import com.chaya.app.ui.components.HeaderButton
import com.chaya.app.ui.components.ScreenHeader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.chaya.app.ChayaApplication
import com.chaya.app.R
import com.chaya.app.download.DownloadTask
import com.chaya.app.downloads.DownloadsViewModel
import com.chaya.app.downloads.displayTitle
import com.chaya.app.history.PagesToOpen
import com.chaya.app.library.LibraryFiles
import com.chaya.app.library.LibraryItem
import com.chaya.app.library.playsInChaya
import com.chaya.app.library.rememberCover
import com.chaya.app.ui.player.Watched
import com.chaya.app.ui.player.clockOf
import kotlinx.coroutines.launch


/**
 * Home (Stage): the newest saved video filling the top with Play and Share, the newest saved things in a row
 * under it, and how the downloads still going are getting on. Saved things open as the Library opens them.
 * At its foot, Chaya's places and "+" to paste a link or search.
 */
@Composable
fun HomeScreen(
    onPlace: (Place) -> Unit,
    onPlay: (Long) -> Unit,
    /** Chaya's settings, from the header's circle: Home is where they are. */
    onOpenSettings: () -> Unit = {},
    viewModel: DownloadsViewModel = viewModel(),
) {
    val tasks by viewModel.downloads.collectAsState()
    val app = LocalContext.current.applicationContext as ChayaApplication
    val watched by app.playbackPositions.watched.collectAsState()
    val shelf = remember(tasks, watched) { HomeShelf.of(tasks, watched = watched) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    /** As the Library opens a saved thing: a video in Chaya's player, anything else in the app that opens it. */
    fun open(task: DownloadTask) {
        if (playsInChaya(task)) onPlay(task.id) else viewModel.open(task) { message(it) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val hero = shelf.hero
            if (hero != null) {
                Hero(
                    task = hero,
                    watched = shelf.heroWatched,
                    files = viewModel.files,
                    onPlay = { open(hero) },
                    onShare = { viewModel.share(listOf(hero)) { message(it) } },
                    onOpenSettings = onOpenSettings,
                )
            } else {
                Welcome(onOpenSettings)
            }
            if (shelf.recent.isNotEmpty()) {
                Recent(
                    items = shelf.recent,
                    files = viewModel.files,
                    onSeeAll = { onPlace(Place.LIBRARY) },
                    onItem = { item ->
                        // A post's files are seen together in the Library's viewer; anything else opens
                        if (item is LibraryItem.Single) open(item.task) else onPlace(Place.LIBRARY)
                    },
                )
            }
            // Room for Chaya's bar over the foot of the page
            Spacer(Modifier.height(120.dp))
        }

        // Above Chaya's bar and the phone's own navigation, so a message is never under them
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = ChayaBarClearance))

        ChayaBar(
            place = Place.HOME,
            middle = BarMiddle.Browse,
            action = BarAction.Go,
            progress = shelf.progress,
            onPlace = onPlace,
            // A blank tab in Browse, its address field ready: where a link is pasted or words searched for
            onAction = PagesToOpen::openBlankTab,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** The video left part-way, else the newest saved one: its picture across the top, fading into the page, its name, Play and Share. */
@Composable
private fun Hero(
    task: DownloadTask,
    watched: Watched?,
    files: LibraryFiles,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ground = MaterialTheme.colorScheme.background
    // A frame as wide as the screen: a tile's small one, stretched across 500dp, was a blur
    val picture = rememberCover(task, files, large = true)
    Box(modifier = Modifier.fillMaxWidth().height(500.dp)) {
        if (picture != null) {
            AsyncImage(
                model = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
        // The page's own ground over the top (for Chaya's name) and the foot (for the video's), clear between
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to ground.copy(alpha = 0.55f),
                    0.24f to ground.copy(alpha = 0f),
                    0.48f to ground.copy(alpha = 0.25f),
                    0.78f to ground.copy(alpha = 0.92f),
                    // The page's own ground well before the foot, so the picture has no edge where it ends
                    0.8f to ground,
                    1f to ground,
                ),
            ),
        )
        TopRow(onOpenSettings)
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = watched?.let { w ->
                    "CONTINUE · ${clockOf(w.resumeAtMs)}" + if (w.lengthMs > 0) " OF ${clockOf(w.lengthMs)}" else ""
                }
                    ?: "NEWEST IN YOUR LIBRARY",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = displayTitle(task),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp,
                    lineHeight = 38.sp,
                    letterSpacing = (-1).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** With nothing saved yet: what Chaya is for, and the way to start, which is the bar's + (one way, not two). */
@Composable
private fun Welcome(onOpenSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
        TopRow(onOpenSettings)
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Save what you watch",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Tap + and paste a link from YouTube, Instagram, TikTok, X or Pinterest, or go to any page " +
                    "with a video on it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The header every screen has, Home's with Chaya's mark before the name and Settings as its one action. How far the
 * downloads are is the bar's Library slot's, on every screen.
 */
@Composable
private fun TopRow(onOpenSettings: () -> Unit) {
    ScreenHeader(
        title = "Chaya",
        leading = {
            // The two suns at rest (res/drawable/chaya_mark.xml, written from the mark's constants); the name says it
            Image(painter = painterResource(R.drawable.chaya_mark), contentDescription = null, modifier = Modifier.size(32.dp))
        },
    ) {
        HeaderButton(Icons.Default.Tune, "Settings", onClick = onOpenSettings)
    }
}

/** "Recently saved": the Library's newest, in a row, with "See all" for the rest. */
@Composable
private fun Recent(items: List<LibraryItem>, files: LibraryFiles, onSeeAll: () -> Unit, onItem: (LibraryItem) -> Unit) {
    Column(modifier = Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Recently saved",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = onSeeAll) {
                Text("See all", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The Library's own card, so a saved thing looks the same on Home as where it is kept
            items(items, key = { it.key }) { item ->
                SavedCard(item, files, modifier = Modifier.width(168.dp).clickable { onItem(item) })
            }
        }
    }
}

package com.chaya.app.home

import androidx.compose.foundation.layout.imePadding
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.chaya.app.ChayaApplication
import com.chaya.app.R
import com.chaya.app.SharedLinks
import com.chaya.app.download.DownloadTask
import com.chaya.app.downloads.DownloadsViewModel
import com.chaya.app.downloads.displayTitle
import com.chaya.app.history.PagesToOpen
import com.chaya.app.library.FileKind
import com.chaya.app.library.LibraryFiles
import com.chaya.app.library.LibraryItem
import com.chaya.app.library.NamedIcon
import com.chaya.app.library.iconFor
import com.chaya.app.library.kindOf
import com.chaya.app.library.playsInChaya
import com.chaya.app.library.rememberCover
import com.chaya.app.library.tileLabel
import com.chaya.app.library.tintFor
import com.chaya.app.ui.player.Watched
import com.chaya.app.ui.player.clockOf
import com.chaya.app.ui.theme.LocalChayaColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


/**
 * Home (Stage): the newest saved video filling the top with Play and Share, the newest saved things in a row
 * under it, and how the downloads still going are getting on. Saved things open as the Library opens them.
 * At its foot, Chaya's places and "+" to paste a link or search.
 */
@Composable
fun HomeScreen(
    onPlace: (Place) -> Unit,
    onPlay: (Long) -> Unit,
    viewModel: DownloadsViewModel = viewModel(),
) {
    val tasks by viewModel.downloads.collectAsState()
    val app = LocalContext.current.applicationContext as ChayaApplication
    val watched by app.playbackPositions.watched.collectAsState()
    val shelf = remember(tasks, watched) { HomeShelf.of(tasks, watched = watched) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }

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
                    progress = shelf.progress,
                    onProgress = { onPlace(Place.LIBRARY) },
                    onPlay = { open(hero) },
                    onShare = { viewModel.share(listOf(hero)) { message(it) } },
                )
            } else {
                Welcome(progress = shelf.progress, onProgress = { onPlace(Place.LIBRARY) })
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
            // Room for the places bar over the foot of the page
            Spacer(Modifier.height(120.dp))
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))

        PlacesBar(
            current = Place.HOME,
            onPlace = onPlace,
            onType = { typing = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (typing) {
        TypeSheet(
            onDismiss = { typing = false },
            onGo = { typed ->
                typing = false
                goTyped(typed, onPlace)
            },
        )
    }
}

/** The video left part-way, else the newest saved one: its picture across the top, fading into the page, its name, Play and Share. */
@Composable
private fun Hero(
    task: DownloadTask,
    watched: Watched?,
    files: LibraryFiles,
    progress: HomeShelf.Progress?,
    onProgress: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
) {
    val ground = MaterialTheme.colorScheme.background
    val picture = rememberCover(task, files)
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
        TopRow(progress = progress, onProgress = onProgress)
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
                    shape = RoundedCornerShape(25.dp),
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

/** With nothing saved yet: what Chaya is for, and the way to start, which is the "+" at the foot (one way, not two). */
@Composable
private fun Welcome(progress: HomeShelf.Progress?, onProgress: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
        TopRow(progress = progress, onProgress = onProgress)
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
                text = "Tap + to paste a link from YouTube, Instagram, TikTok, X or Pinterest, or to browse to any page " +
                    "with a video on it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Chaya's mark and name, and while anything is saving, how far it is: a ring of Stage's amber and its share. */
@Composable
private fun TopRow(progress: HomeShelf.Progress?, onProgress: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The two suns at rest (res/drawable/chaya_mark.xml, written from the mark's constants); the name says it
            Image(painter = painterResource(R.drawable.chaya_mark), contentDescription = null, modifier = Modifier.size(32.dp))
            Text(
                text = "Chaya",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (progress != null) {
            val fraction = progress.fraction
            val words = if (fraction != null) "${(fraction * 100).roundToInt()}%" else "${progress.running} saving"
            Surface(
                onClick = onProgress,
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.height(44.dp).semantics {
                    contentDescription = if (fraction != null) "$words saved, in the Library" else "$words, in the Library"
                },
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 14.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (fraction != null) {
                        CircularProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.size(22.dp),
                            color = LocalChayaColors.current.signal,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = LocalChayaColors.current.signal,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            strokeWidth = 2.5.dp,
                        )
                    }
                    Text(words, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
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
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
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
            items(items, key = { it.key }) { item -> RecentCard(item, files, onClick = { onItem(item) }) }
        }
    }
}

@Composable
private fun RecentCard(item: LibraryItem, files: LibraryFiles, onClick: () -> Unit) {
    val cover = item.cover
    val kind = kindOf(cover)
    val picture = rememberCover(cover, files)
    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = tileLabel(item) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .shadow(10.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (picture != null) {
                AsyncImage(
                    model = picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                NamedIcon(iconFor(kind), item.title, tint = tintFor(kind))
            }
            if (kind == FileKind.VIDEO && picture != null) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        .padding(3.dp),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Chaya's places, floating at the foot of the screen (Stage), the one shown lit; and "+", in ink, to paste a link or
 * search. A solid sheet: blurring what is behind it needs Android 12, and the phone's oldest is 8.
 */
@Composable
fun PlacesBar(current: Place, onPlace: (Place) -> Unit, onType: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(31.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 12.dp,
            modifier = Modifier.weight(1f).height(62.dp),
        ) {
            Row(modifier = Modifier.padding(5.dp)) {
                for (place in Place.entries) {
                    val on = place == current
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(26.dp))
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { if (!on) onPlace(place) }
                            .semantics { selected = on },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = place.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Surface(
            onClick = onType,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 12.dp,
            modifier = Modifier.size(62.dp).semantics { contentDescription = "Paste a link or search" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Hands what "+" was given to the browser, as [Typed] says, and brings Browse forward. */
internal fun goTyped(typed: Typed, onPlace: (Place) -> Unit) {
    when (typed) {
        is Typed.LookUp -> SharedLinks.offer(typed.text)
        is Typed.Open -> PagesToOpen.open(typed.text)
    }
    onPlace(Place.BROWSE)
}

/** "+": a link to save, an address, or words to search for; pasted with one tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypeSheet(onDismiss: () -> Unit, onGo: (Typed) -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    fun paste() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) input = text
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            // Above the keyboard while a link is typed
            modifier = Modifier.fillMaxWidth().imePadding().padding(start = 24.dp, end = 24.dp, bottom = 28.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Paste a link, or search", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("A link, an address, or words") },
                trailingIcon = {
                    IconButton(onClick = ::paste) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }
                },
                shape = RoundedCornerShape(16.dp),
            )
            Button(
                onClick = { Typed.of(input)?.let(onGo) },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Go", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

package com.chaya.app.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
// OpenInNew is directional — the plain Filled variant is deprecated in favor
// of the AutoMirrored one so LTR/RTL layouts mirror the glyph correctly.
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.chaya.app.download.DownloadNotification.formatFileSize
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import com.chaya.app.ui.theme.ChayaMotion
import com.chaya.app.ui.theme.LocalChayaColors
import com.chaya.app.ui.components.AppearanceDialog
import com.chaya.app.ui.theme.StaggeredAppear
import com.chaya.app.ui.theme.ThemeMode
import com.chaya.app.ui.theme.pressScale
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.produceState
import com.chaya.app.history.SearchField
import com.chaya.app.library.FileKind
import com.chaya.app.library.LibraryFiles
import com.chaya.app.library.LibraryFilter
import com.chaya.app.library.LibraryGrid
import com.chaya.app.library.LibraryIntents
import com.chaya.app.library.LibraryItem
import com.chaya.app.library.PictureViewer
import com.chaya.app.library.SourceSite
import com.chaya.app.library.ViewerPage
import com.chaya.app.library.ZipItem
import com.chaya.app.library.ZipView
import com.chaya.app.library.kindOf
import com.chaya.app.library.libraryItems
import com.chaya.app.library.unfinishedCount
import java.io.File

@Composable
fun DownloadsScreen(
    onNavigateBack: () -> Unit,
    onPlayStream: (Long) -> Unit,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToBookmarks: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    viewModel: DownloadsViewModel = viewModel()
) {
    val tasks by viewModel.downloads.collectAsState()
    val hidden by viewModel.pendingDeletes.collectAsState()
    val grid by viewModel.grid.collectAsState()
    var appearanceOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val shown = remember(tasks, hidden) { tasks.filter { it.id !in hidden } }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    /** Hides the downloads now and deletes them for real unless Undo is tapped. */
    fun requestDelete(chosen: List<DownloadTask>) {
        if (chosen.isEmpty()) return
        chosen.forEach { viewModel.hideForDelete(it.id) }
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = if (chosen.size == 1) "Deleted ${displayTitle(chosen.single()).take(40)}"
                else "Deleted ${chosen.size} downloads",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            chosen.forEach {
                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(it.id) else viewModel.commitDelete(it.id)
            }
        }
    }

    if (appearanceOpen) {
        AppearanceDialog(
            current = themeMode,
            onSelect = onThemeModeChange,
            onDismiss = { appearanceOpen = false },
        )
    }

    DownloadsContent(
        tasks = shown,
        grid = grid,
        onGridChange = viewModel::setGrid,
        files = viewModel.files,
        snackbarHostState = snackbarHostState,
        actions = DownloadActions(
            pause = viewModel::pause,
            resume = viewModel::resume,
            cancel = viewModel::cancel,
            delete = ::requestDelete,
            open = { task -> viewModel.open(task, ::message) },
            play = onPlayStream,
            saveAsFile = viewModel::saveAsFile,
            share = { chosen -> viewModel.share(chosen, ::message) },
            openFile = { file, mime -> viewModel.openFile(file, mime, ::message) },
            shareFile = { file, mime -> viewModel.shareFile(file, mime, ::message) },
            saveFile = { file, name, mime -> viewModel.saveFile(file, name, mime, ::message) },
        ),
        menu = DownloadsMenu(
            onNavigateBack = onNavigateBack,
            onBookmarks = onNavigateToBookmarks,
            onHistory = onNavigateToHistory,
            onAppearance = { appearanceOpen = true },
            onDiagnostics = onNavigateToDiagnostics,
        ),
    )
}

/** What the Downloads screen does with downloads, for [DownloadsContent]. */
internal class DownloadActions(
    val pause: (Long) -> Unit = {},
    val resume: (Long) -> Unit = {},
    val cancel: (Long) -> Unit = {},
    /** Deletes these, with Undo. */
    val delete: (List<DownloadTask>) -> Unit = {},
    /** Opens a finished file in another app. */
    val open: (DownloadTask) -> Unit = {},
    /** Plays a stream that lives in the cache, in the app. */
    val play: (Long) -> Unit = {},
    val saveAsFile: (Long) -> Unit = {},
    val share: (List<DownloadTask>) -> Unit = {},
    /** A file taken out of a ZIP: opened, shared or saved to the phone's folders. */
    val openFile: (File, String) -> Unit = { _, _ -> },
    val shareFile: (File, String) -> Unit = { _, _ -> },
    val saveFile: (File, String, String) -> Unit = { _, _, _ -> },
)

/** Where the top bar leads. */
internal class DownloadsMenu(
    val onNavigateBack: () -> Unit = {},
    val onBookmarks: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onAppearance: () -> Unit = {},
    val onDiagnostics: () -> Unit = {},
)

/** Pictures being looked at in the viewer, and which one first. */
private class Viewing(val pages: List<ViewerPage>, val start: Int, val tasks: List<DownloadTask>)

/**
 * The Downloads screen: the list, best for what is downloading, or the grid of what is done. Kind, site and
 * title narrow either; in the grid a long press chooses several to share or delete; pictures open in the viewer
 * and an account's ZIP opens to show what it holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsContent(
    tasks: List<DownloadTask>,
    grid: Boolean,
    onGridChange: (Boolean) -> Unit,
    files: LibraryFiles?,
    actions: DownloadActions,
    menu: DownloadsMenu = DownloadsMenu(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val filter = DownloadFilter.entries[filterIndex]
    var kind by rememberSaveable { mutableStateOf<FileKind?>(null) }
    var site by rememberSaveable { mutableStateOf<SourceSite?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    var zipTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var zipViewing by remember { mutableStateOf<Viewing?>(null) }
    val scope = rememberCoroutineScope()

    val narrowed = LibraryFilter(kind, site, query)
    val listed = remember(tasks, narrowed) { tasks.filter(narrowed::matches) }
    val sections = remember(listed, filter) { groupDownloads(listed, filter) }
    val items = remember(tasks, narrowed) { libraryItems(tasks, narrowed) }
    // Something chosen and then deleted, or filtered away, is no longer chosen.
    val chosenItems = items.filter { it.key in chosen }
    if (grid && chosenItems.size != chosen.size) chosen = chosenItems.map { it.key }.toSet()
    val choosing = grid && chosen.isNotEmpty()

    BackHandler(enabled = choosing) { chosen = emptySet() }

    fun pagesOf(pictures: List<DownloadTask>) = pictures.map { task ->
        val video = kindOf(task) == FileKind.VIDEO
        ViewerPage(
            key = "task:${task.id}",
            title = displayTitle(task),
            // A video in a post shows a frame of itself, else the post's poster, under its play button.
            model = if (video) task.thumbnailUrl else task.filePath?.let(::File) ?: task.thumbnailUrl,
            isVideo = video,
            load = if (video && files != null) suspend { files.frameFor(task) ?: task.thumbnailUrl } else null,
        )
    }

    fun openItem(item: LibraryItem) {
        val task = item.cover
        when {
            item is LibraryItem.Post -> viewing = Viewing(pagesOf(item.tasks), 0, item.tasks)
            kindOf(task) == FileKind.PICTURE -> {
                // A picture on its own swipes to the other pictures on their own, in the grid's order.
                val pictures = items.filterIsInstance<LibraryItem.Single>().map { it.task }
                    .filter { kindOf(it) == FileKind.PICTURE }
                viewing = Viewing(pagesOf(pictures), pictures.indexOf(task).coerceAtLeast(0), pictures)
            }
            kindOf(task) == FileKind.ZIP && task.filePath != null -> zipTaskId = task.id
            task.filePath != null || task.exportedUri != null -> actions.open(task)
            else -> actions.play(task.id)
        }
    }

    fun openTask(task: DownloadTask) {
        if (task.filePath != null || task.exportedUri != null) actions.open(task) else actions.play(task.id)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (choosing) {
                    val picked = chosenItems.flatMap { it.tasks }
                    TopAppBar(
                        title = { Text("${chosen.size} chosen", style = MaterialTheme.typography.titleLarge) },
                        navigationIcon = {
                            IconButton(onClick = { chosen = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "Stop choosing")
                            }
                        },
                        actions = {
                            IconButton(onClick = { actions.share(picked.filter(LibraryIntents::canShare)) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share chosen")
                            }
                            IconButton(onClick = {
                                actions.delete(picked)
                                chosen = emptySet()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete chosen")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                } else {
                    TopAppBar(
                        title = { Text("Downloads", style = MaterialTheme.typography.titleLarge) },
                        navigationIcon = {
                            IconButton(onClick = menu.onNavigateBack) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                searching = !searching
                                if (!searching) query = ""
                            }) {
                                Icon(Icons.Default.Search, contentDescription = if (searching) "Close search" else "Search downloads")
                            }
                            IconButton(onClick = { onGridChange(!grid) }) {
                                Icon(
                                    imageVector = if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                    contentDescription = if (grid) "Show as list" else "Show as grid"
                                )
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    listOf(
                                        "Bookmarks" to menu.onBookmarks,
                                        "History" to menu.onHistory,
                                        "Appearance" to menu.onAppearance,
                                        "Diagnostics" to menu.onDiagnostics,
                                    ).forEach { (label, action) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                menuOpen = false
                                                action()
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (searching) SearchField(query, onChange = { query = it }, placeholder = "Search by title")
                if (!grid) FilterRow(selected = filter, onSelect = { filterIndex = it.ordinal })
                NarrowRow(kind = kind, site = site, onKind = { kind = it }, onSite = { site = it })

                when {
                    grid && items.isEmpty() -> EmptyDownloads(
                        filter = DownloadFilter.DONE,
                        hasAnyDownloads = tasks.isNotEmpty(),
                        narrowed = !narrowed.isEmpty,
                        modifier = Modifier.weight(1f)
                    )
                    grid -> LibraryGrid(
                        items = items,
                        selected = chosen,
                        unfinished = unfinishedCount(tasks),
                        files = files,
                        onShowList = {
                            filterIndex = DownloadFilter.ACTIVE.ordinal
                            onGridChange(false)
                        },
                        onOpen = ::openItem,
                        onToggle = { item -> chosen = if (item.key in chosen) chosen - item.key else chosen + item.key },
                        modifier = Modifier.weight(1f)
                    )
                    sections.isEmpty() -> EmptyDownloads(
                        filter = filter,
                        hasAnyDownloads = tasks.isNotEmpty(),
                        narrowed = !narrowed.isEmpty,
                        modifier = Modifier.weight(1f)
                    )
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                    ) {
                        sections.forEach { section ->
                            item(key = "section:${section.title}") { SectionLabel(section.title) }
                            items(section.items, key = { "task:${it.id}" }) { task ->
                                SwipeableDownloadCard(
                                    task = task,
                                    modifier = Modifier.animateItem(),
                                    onPause = { actions.pause(task.id) },
                                    onResume = { actions.resume(task.id) },
                                    onCancel = { actions.cancel(task.id) },
                                    onDelete = { actions.delete(listOf(task)) },
                                    onOpen = { actions.open(task) },
                                    onPlay = { actions.play(task.id) },
                                    onSaveAsFile = { actions.saveAsFile(task.id) },
                                    onShare = { actions.share(listOf(task)) },
                                )
                            }
                        }
                    }
                }
            }
        }

        val zipTask = zipTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
        if (zipTaskId != null && zipTask == null) zipTaskId = null
        if (zipTask != null) {
            val zipItems by produceState<List<ZipItem>?>(initialValue = null, zipTask.id) {
                value = files?.zipItems(zipTask) ?: emptyList()
            }
            suspend fun fileOf(item: ZipItem) = files?.zipFile(zipTask, item)
            fun withFile(item: ZipItem, use: (File) -> Unit) {
                scope.launch {
                    val file = fileOf(item)
                    if (file != null) use(file) else snackbarHostState.showSnackbar("Couldn't take ${item.name} out of the ZIP")
                }
            }
            ZipView(
                title = displayTitle(zipTask),
                items = zipItems,
                fileFor = ::fileOf,
                onClose = { zipTaskId = null },
                onView = { item ->
                    val pictures = zipItems.orEmpty().filter { it.kind == FileKind.PICTURE }
                    if (item.kind == FileKind.PICTURE) {
                        zipViewing = Viewing(
                            pictures.map { picture ->
                                ViewerPage(key = picture.path, title = picture.name, model = null, load = { fileOf(picture) })
                            },
                            pictures.indexOf(item),
                            emptyList(),
                        )
                    } else {
                        withFile(item) { actions.openFile(it, LibraryIntents.mimeOf(item.name)) }
                    }
                },
                onShare = { item -> withFile(item) { actions.shareFile(it, LibraryIntents.mimeOf(item.name)) } },
                onSave = { item -> withFile(item) { actions.saveFile(it, item.name, LibraryIntents.mimeOf(item.name)) } },
            )
            zipViewing?.let { shown ->
                val pictures = zipItems.orEmpty().filter { it.kind == FileKind.PICTURE }
                PictureViewer(
                    pages = shown.pages,
                    start = shown.start,
                    onClose = { zipViewing = null },
                    onShare = { index -> pictures.getOrNull(index)?.let { item -> withFile(item) { actions.shareFile(it, LibraryIntents.mimeOf(item.name)) } } },
                    onPlay = {},
                    onSave = { index ->
                        pictures.getOrNull(index)?.let { item ->
                            withFile(item) { actions.saveFile(it, item.name, LibraryIntents.mimeOf(item.name)) }
                        }
                    },
                )
            }
        }

        viewing?.let { shown ->
            PictureViewer(
                pages = shown.pages,
                start = shown.start,
                onClose = { viewing = null },
                onShare = { index -> shown.tasks.getOrNull(index)?.let { actions.share(listOf(it)) } },
                onPlay = { index -> shown.tasks.getOrNull(index)?.let(::openTask) },
            )
        }
    }
}

@Composable
private fun FilterRow(selected: DownloadFilter, onSelect: (DownloadFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DownloadFilter.entries.forEach { option ->
            Chip(label = option.label, selected = option == selected, onClick = { onSelect(option) })
        }
    }
}

/** Kind and site, each tapped again to let go; one row, scrolled sideways. */
@Composable
private fun NarrowRow(
    kind: FileKind?,
    site: SourceSite?,
    onKind: (FileKind?) -> Unit,
    onSite: (SourceSite?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileKind.entries.forEach { option ->
            Chip(label = option.label, selected = option == kind, onClick = { onKind(option.takeIf { it != kind }) })
        }
        Box(
            modifier = Modifier
                .size(width = 1.dp, height = 20.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        SourceSite.entries.forEach { option ->
            Chip(label = option.label, selected = option == site, onClick = { onSite(option.takeIf { it != site }) })
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(16.dp),
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun EmptyDownloads(
    filter: DownloadFilter,
    hasAnyDownloads: Boolean,
    modifier: Modifier = Modifier,
    narrowed: Boolean = false,
) {
    val (title, body) = when {
        !hasAnyDownloads -> "Nothing saved yet" to "Open a page with a video and tap the download pill."
        narrowed -> "Nothing matches" to "Try another kind, site or title."
        filter == DownloadFilter.ACTIVE -> "All caught up" to "Nothing is running, waiting or needs attention."
        filter == DownloadFilter.DONE -> "Nothing finished yet" to "Finished downloads show up here."
        else -> "Nothing saved yet" to "Open a page with a video and tap the download pill."
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StaggeredAppear(index = 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        }
    }
}

/** Swipe left to delete finished, paused and failed downloads; the list offers Undo afterwards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableDownloadCard(
    task: DownloadTask,
    modifier: Modifier = Modifier,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onSaveAsFile: () -> Unit,
    onShare: () -> Unit = {}
) {
    val currentDelete by rememberUpdatedState(onDelete)
    val inFlight = task.state == DownloadState.DOWNLOADING || task.state == DownloadState.QUEUED
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                currentDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !inFlight,
        backgroundContent = { DeleteBackdrop() }
    ) {
        DownloadCard(task, onPause, onResume, onCancel, onDelete, onOpen, onPlay, onSaveAsFile, onShare)
    }
}

@Composable
private fun DeleteBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(end = 24.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun DownloadCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onSaveAsFile: () -> Unit,
    onShare: () -> Unit
) {
    // Streams live in Media3's cache (no single file), so they play in-app instead of opening.
    val openAction = if (task.filePath != null || task.exportedUri != null) onOpen else onPlay

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (task.state == DownloadState.COMPLETED) Modifier.clickable(onClick = openAction)
                    else Modifier
                )
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DownloadThumb(task)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle(task),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                StateArea(task)
            }

            Spacer(Modifier.width(4.dp))
            ActionsRow(task, onPause, onResume, onCancel, onDelete, onOpen, onPlay, onSaveAsFile, onShare)
        }
    }
}

/** Poster or page image when known; a tonal media-type icon underneath shows through until it loads. */
@Composable
private fun DownloadThumb(task: DownloadTask) {
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = iconForMime(task.mimeType),
            contentDescription = null,
            tint = if (task.mimeType?.startsWith("audio/") == true)
                MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        task.thumbnailUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/** Animated state area: progress while downloading, a status line otherwise. */
@Composable
private fun StateArea(task: DownloadTask) {
    AnimatedContent(
        targetState = task.state,
        transitionSpec = {
            (fadeIn(ChayaMotion.tweenShort()) +
                    slideInVertically(ChayaMotion.tweenShort()) { it / 2 }) togetherWith
                    (fadeOut(ChayaMotion.tweenShort()) +
                    slideOutVertically(ChayaMotion.tweenShort()) { -it / 2 })
        },
        label = "stateArea"
    ) { state ->
        when (state) {
            DownloadState.DOWNLOADING -> ProgressBlock(task)
            else -> StateLine(task, state)
        }
    }
}

@Composable
private fun ProgressBlock(task: DownloadTask) {
    // An archive counts files, not bytes: it cannot know its size until every file has arrived.
    val archive = task.archive
    val saving = task.savingAsFile
    val totalKnown = when {
        saving != null -> true
        archive != null -> !archive.listing && archive.found > 0
        else -> (task.totalBytes ?: 0L) > 0L
    }
    val target = when {
        saving != null -> saving
        archive != null && archive.found > 0 -> archive.saved.toFloat() / archive.found
        else -> task.progressFraction
    }
    val fraction by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = ChayaMotion.tweenStandard(),
        label = "progressFraction"
    )
    val barModifier = Modifier
        .fillMaxWidth()
        .height(5.dp)
        .clip(RoundedCornerShape(3.dp))

    Column {
        // Streams never report a total size, so they get a moving bar instead of a stuck 0%.
        if (totalKnown) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = barModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )
        } else {
            LinearProgressIndicator(
                modifier = barModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = when {
                    saving != null -> "Saving as MP4 · ${(fraction * 100).toInt()}%"
                    archive != null && archive.listing -> "Finding posts"
                    archive != null -> "${archive.saved} of ${archive.found} files"
                    totalKnown -> "${(fraction * 100).toInt()}%"
                    else -> "Downloading"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = when {
                    saving != null -> if (task.downloadedBytes > 0) formatFileSize(task.downloadedBytes) else ""
                    archive != null && archive.listing -> if (archive.found > 0) "${archive.found} so far" else ""
                    archive != null -> formatFileSize(task.downloadedBytes)
                    totalKnown -> "${formatFileSize(task.downloadedBytes)} of ${formatFileSize(task.totalBytes ?: 0)}"
                    task.downloadedBytes > 0 -> formatFileSize(task.downloadedBytes)
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StateLine(task: DownloadTask, state: DownloadState) {
    val extended = LocalChayaColors.current

    val dotColor = when (state) {
        DownloadState.COMPLETED -> extended.success
        DownloadState.FAILED -> MaterialTheme.colorScheme.error
        DownloadState.PAUSED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }

    val label = when (state) {
        DownloadState.QUEUED -> "Waiting to start"
        DownloadState.PAUSED -> "Paused · ${formatFileSize(task.downloadedBytes)}"
        DownloadState.COMPLETED -> completedLine(task)
        DownloadState.FAILED -> task.error?.userMessage?.takeIf { it.isNotBlank() }?.let { "Failed · $it" }
            ?: "Failed"
        DownloadState.CANCELLED -> "Stopped · ${formatFileSize(task.downloadedBytes)}"
        DownloadState.DOWNLOADING -> ""
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (state == DownloadState.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

/**
 * One tonal action for what the download needs next, plus a menu for the
 * secondary ones (cancel a running download, delete the rest).
 */
@Composable
internal fun ActionsRow(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onSaveAsFile: () -> Unit = {},
    onShare: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Cancel is the only way out of a queued download, so it is the primary action there.
    val menuItems: List<Pair<String, () -> Unit>> = when {
        task.state == DownloadState.DOWNLOADING -> listOf("Cancel" to onCancel)
        task.state == DownloadState.QUEUED -> emptyList()
        // A stream that lives only in the cache plays here; saving it makes a file other apps can open.
        task.canSaveAsFile -> listOf("Save as MP4" to onSaveAsFile, "Delete" to onDelete)
        LibraryIntents.canShare(task) -> listOf("Share" to onShare, "Delete" to onDelete)
        else -> listOf("Delete" to onDelete)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        when (task.state) {
            DownloadState.DOWNLOADING -> PrimaryAction(Icons.Default.Pause, "Pause", onPause)
            DownloadState.QUEUED -> PrimaryAction(Icons.Default.Close, "Cancel", onCancel)
            DownloadState.PAUSED, DownloadState.CANCELLED ->
                PrimaryAction(Icons.Default.PlayArrow, "Resume", onResume, highlighted = true)
            DownloadState.FAILED -> {
                // Retry only when the classified error says it could help
                // (pointless for 404s, denied links, full disks).
                if (task.error?.retryable != false) {
                    PrimaryAction(Icons.Default.Refresh, "Retry", onResume, highlighted = true)
                }
            }
            DownloadState.COMPLETED -> {
                if (task.filePath != null || task.exportedUri != null) {
                    PrimaryAction(Icons.AutoMirrored.Filled.OpenInNew, "Open", onOpen, highlighted = true)
                } else {
                    PrimaryAction(Icons.Default.PlayArrow, "Play", onPlay, highlighted = true)
                }
            }
        }

        if (menuItems.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.pressScale(0.88f)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More actions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menuItems.forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                action()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .pressScale(0.9f),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

private fun iconForMime(mimeType: String?): ImageVector = when {
    mimeType?.startsWith("video/") == true -> Icons.Default.Movie
    mimeType?.startsWith("audio/") == true -> Icons.Default.MusicNote
    else -> Icons.Default.Movie
}

/**
 * The line under a finished download. A stream that is still only in the cache says so (it plays in Chaya), with
 * the reason when saving it as a file did not work; a file says how it was saved when there is something to add.
 */
internal fun completedLine(task: DownloadTask): String {
    if (task.canSaveAsFile) {
        return listOfNotNull(task.saveNote ?: "Plays in Chaya", finishedDetails(task).ifEmpty { null }).joinToString(" · ")
    }
    val details = listOfNotNull(finishedDetails(task).ifEmpty { null }, task.saveNote).joinToString(" · ")
    return if (details.isEmpty()) "Saved" else "Saved · $details"
}

package com.chaya.app.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chaya.app.download.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finished downloads as square tiles: pictures show themselves, videos a frame, a post's files one tile with
 * their count. Tap opens; a long press starts choosing several, and while choosing a tap adds or removes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGrid(
    items: List<LibraryItem>,
    selected: Set<String>,
    /** Downloads still running, waiting or paused: the list is where they are followed. */
    unfinished: Int,
    files: LibraryFiles?,
    onShowList: () -> Unit,
    onOpen: (LibraryItem) -> Unit,
    onToggle: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choosing = selected.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (unfinished > 0) {
            item(key = "unfinished", span = { GridItemSpan(maxLineSpan) }) {
                UnfinishedPill(unfinished, onShowList)
            }
        }
        items(items, key = { it.key }) { item ->
            Tile(
                item = item,
                selected = item.key in selected,
                files = files,
                modifier = Modifier.combinedClickable(
                    onClick = { if (choosing) onToggle(item) else onOpen(item) },
                    onLongClick = { onToggle(item) },
                ),
            )
        }
    }
}

@Composable
private fun UnfinishedPill(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$count still downloading · Show list",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** What a tile is called to a screen reader: its title, and how many files a post holds. */
internal fun tileLabel(item: LibraryItem): String = when (item) {
    is LibraryItem.Post -> "${item.title}, ${item.tasks.size} files"
    is LibraryItem.Single -> item.title
}

@Composable
private fun Tile(item: LibraryItem, selected: Boolean, files: LibraryFiles?, modifier: Modifier) {
    val cover = item.cover
    val kind = kindOf(cover)
    val picture = rememberCover(cover, files)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                else Modifier
            )
            .then(modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = tileLabel(item)
                this.selected = selected
            },
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

        if (kind == FileKind.VIDEO) {
            Badge(Modifier.align(Alignment.BottomStart)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        if (item is LibraryItem.Post) {
            Badge(Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Collections, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text("${item.tasks.size}", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .size(22.dp),
            )
        }
    }
}

/** A tile without a picture: its kind's icon over its name. */
@Composable
internal fun NamedIcon(icon: ImageVector, name: String, tint: Color) {
    Column(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Badge(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

internal fun iconFor(kind: FileKind?): ImageVector = when (kind) {
    FileKind.VIDEO -> Icons.Default.Movie
    FileKind.PICTURE -> Icons.Default.Collections
    FileKind.AUDIO -> Icons.Default.MusicNote
    FileKind.ZIP -> Icons.Default.FolderZip
    null -> Icons.Default.Description
}

@Composable
internal fun tintFor(kind: FileKind?): Color =
    if (kind == FileKind.AUDIO) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary

/**
 * What a tile shows: a picture's own file, a frame of a video (else the site's poster), nothing for the rest.
 * Worked out off the main thread.
 */
@Composable
internal fun rememberCover(task: DownloadTask, files: LibraryFiles?): Any? {
    val cover by produceState<Any?>(initialValue = null, task.id, task.filePath, task.thumbnailUrl) {
        value = when (kindOf(task)) {
            FileKind.PICTURE -> withContext(Dispatchers.IO) { task.filePath?.let(::File)?.takeIf { it.isFile } }
                ?: task.thumbnailUrl
            FileKind.VIDEO -> files?.frameFor(task) ?: task.thumbnailUrl
            else -> null
        }
    }
    return cover
}

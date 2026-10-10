package com.chaya.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File

/**
 * What an account's ZIP holds, as a grid. A picture opens in the viewer; anything else opens in another app.
 * A long press offers Open, Share and Save to phone for that one file. Files are taken out one at a time, as
 * they are shown or used.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZipView(
    title: String,
    /** Null while the listing is read. */
    items: List<ZipItem>?,
    /** The file taken out for [ZipItem], or null when it cannot be. */
    fileFor: suspend (ZipItem) -> File?,
    onClose: () -> Unit,
    onView: (ZipItem) -> Unit,
    onShare: (ZipItem) -> Unit,
    onSave: (ZipItem) -> Unit,
) {
    BackHandler(onBack = onClose)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            items == null -> "Reading the ZIP"
                            items.size == 1 -> "1 file"
                            else -> "${items.size} files"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                items == null -> Unit
                items.isEmpty() -> Text(
                    text = "Nothing in this ZIP can be shown.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.path }) { _, item ->
                        ZipTile(item, fileFor, onView, onShare, onSave)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZipTile(
    item: ZipItem,
    fileFor: suspend (ZipItem) -> File?,
    onView: (ZipItem) -> Unit,
    onShare: (ZipItem) -> Unit,
    onSave: (ZipItem) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Only pictures are taken out to be shown; a video's frame would mean unpacking the whole video.
    val picture by produceState<File?>(initialValue = null, item.path) {
        if (item.kind == FileKind.PICTURE) value = fileFor(item)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = { onView(item) }, onLongClick = { menuOpen = true })
            .semantics(mergeDescendants = true) { contentDescription = item.name },
    ) {
        val shown = picture
        if (shown != null) {
            AsyncImage(model = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            NamedIcon(iconFor(item.kind), item.name, tint = tintFor(item.kind))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menuOpen = false; onView(item) })
            DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; onShare(item) })
            DropdownMenuItem(text = { Text("Save to phone") }, onClick = { menuOpen = false; onSave(item) })
        }
    }
}

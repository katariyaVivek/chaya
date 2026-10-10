package com.chaya.app.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaya.app.browser.TabSummary
import com.chaya.app.detection.MediaUrlClassifier
import com.chaya.app.ui.theme.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Every open tab as a card in two columns, as Chrome shows them: the site's icon, the title and a close button,
 * over a picture of the page as last seen. The tab shown is filled with the accent container. Tap a card to
 * show its tab; swipe it sideways or tap its × to close it, with Undo; search filters by title and address.
 *
 * Closing waits for the Undo to run out (or the grid to close) before [onClose] is called.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabGrid(
    tabs: List<TabSummary>,
    activeId: Long,
    canOpenMore: Boolean,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
    /** Changes whenever a tab's picture or icon may have changed, so they are read again. */
    picturesVersion: Int = 0,
) {
    var query by remember { mutableStateOf("") }
    var closing by remember { mutableStateOf(emptySet<Long>()) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)

    /** Closes for real the tabs whose Undo is still showing: leaving the grid ends the Undo. */
    fun commitClosing() {
        val ids = closing
        closing = emptySet()
        snackbarHostState.currentSnackbarData?.dismiss()
        ids.forEach { currentOnClose(it) }
    }

    DisposableEffect(Unit) {
        onDispose { closing.forEach { currentOnClose(it) } }
    }

    fun close(tab: TabSummary) {
        closing = closing + tab.id
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Closed ${titleOf(tab)}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (tab.id !in closing) return@launch
            closing = closing - tab.id
            if (result != SnackbarResult.ActionPerformed) currentOnClose(tab.id)
        }
    }

    BackHandler {
        commitClosing()
        onDismiss()
    }

    val shown = tabs.filter { it.id !in closing && it.matches(query) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            commitClosing()
                            onNewTab()
                        },
                        enabled = canOpenMore,
                        shape = RoundedCornerShape(14.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.size(44.dp).pressScale(0.9f),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New tab")
                    }
                    Spacer(Modifier.weight(1f))
                    // The tab count, as the bottom bar shows it; tab groups would sit beside it later.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    ) {
                        TabCountMark(count = tabs.size - closing.size)
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More tab actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Close all tabs") },
                                onClick = {
                                    menuOpen = false
                                    confirmCloseAll = true
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search your tabs") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { padding ->
        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = if (query.isBlank()) "No tabs open" else "No tab matches “${query.trim()}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            ) {
                items(shown, key = { it.id }) { tab ->
                    SwipeableTabCard(
                        tab = tab,
                        shown = tab.id == activeId,
                        picturesVersion = picturesVersion,
                        onOpen = {
                            commitClosing()
                            onSelect(tab.id)
                        },
                        onClose = { close(tab) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text("Close all tabs?") },
            text = { Text("Their pages, history and pictures are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCloseAll = false
                    closing = emptySet()
                    snackbarHostState.currentSnackbarData?.dismiss()
                    onCloseAll()
                }) { Text("Close all") }
            },
            dismissButton = { TextButton(onClick = { confirmCloseAll = false }) { Text("Cancel") } },
        )
    }
}

/** The number of tabs in a rounded square, the mark the bottom bar's Tabs button shows. */
@Composable
private fun TabCountMark(count: Int) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(1.75.dp, tint, RoundedCornerShape(6.dp))
            .semantics { contentDescription = if (count == 1) "1 tab" else "$count tabs" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = tint,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTabCard(
    tab: TabSummary,
    shown: Boolean,
    picturesVersion: Int,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnClose by rememberUpdatedState(onClose)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) currentOnClose()
            value != SwipeToDismissBoxValue.Settled
        },
    )
    SwipeToDismissBox(state = state, backgroundContent = {}, modifier = modifier) {
        TabCard(tab, shown, picturesVersion, onOpen, onClose)
    }
}

@Composable
private fun TabCard(tab: TabSummary, shown: Boolean, picturesVersion: Int, onOpen: () -> Unit, onClose: () -> Unit) {
    val container = if (shown) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (shown) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val title = titleOf(tab)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .semantics { selected = shown }
            .clickable(onClick = onOpen)
            .padding(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = rememberPicture(tab.icon, picturesVersion)
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(16.dp).clip(CircleShape))
            } else {
                Icon(
                    imageVector = if (tab.isStartScreen) Icons.Default.Home else Icons.Default.Public,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close $title", tint = content, modifier = Modifier.size(18.dp))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val picture = rememberPicture(tab.thumbnail, picturesVersion)
            if (picture != null) {
                Image(
                    picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = if (tab.isStartScreen) "Start page" else MediaUrlClassifier.hostOf(tab.url)?.removePrefix("www.") ?: tab.url,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

private fun titleOf(tab: TabSummary) = if (tab.isStartScreen) "Start page" else tab.title

/** A picture file, read off the main thread and again whenever it changes. */
@Composable
private fun rememberPicture(file: File?, version: Int): ImageBitmap? {
    val picture by produceState<ImageBitmap?>(null, file, version) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    return picture
}

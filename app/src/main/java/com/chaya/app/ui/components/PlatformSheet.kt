package com.chaya.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.chaya.app.detection.MediaNamer
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.PlatformChoice
import com.chaya.app.platform.PostItem
import com.chaya.app.ui.theme.pressScale

/**
 * The video behind a link on YouTube, Instagram, TikTok or X: a note while it is being found, then what was
 * found with the qualities it can be saved at, or why it cannot be saved. A post with pictures shows every
 * item in it instead; [onSavePost] gets the positions (from 0) of the items to save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSheet(
    state: LinkState,
    onDismiss: () -> Unit,
    onChoose: (PlatformChoice) -> Unit,
    onRetry: () -> Unit,
    onRetryWithSignIn: () -> Unit,
    onSavePost: (List<Int>) -> Unit = {},
) {
    if (state is LinkState.Idle) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        when (state) {
            LinkState.Idle -> Unit
            is LinkState.Looking -> Looking(state)
            is LinkState.Found -> Found(state, onChoose)
            is LinkState.FoundPost -> Post(state, onSavePost)
            is LinkState.Failed -> Failed(state, onRetry, onRetryWithSignIn)
        }
    }
}

@Composable
private fun Looking(state: LinkState.Looking) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Finding the video…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "From ${state.match.platform.displayName}. This takes a few seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Found(state: LinkState.Found, onChoose: (PlatformChoice) -> Unit) {
    val best = state.best
    val others = state.choices.drop(1)

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") {
            Hero(state = state, best = best, onChoose = onChoose)
        }
        if (others.isNotEmpty()) {
            item(key = "label") {
                Text(
                    text = "Other qualities",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(others, key = { it.file.id + ":" + it.label }) { choice ->
                ChoiceRow(choice = choice, onChoose = onChoose)
            }
        }
    }
}

@Composable
private fun Hero(state: LinkState.Found, best: PlatformChoice, onChoose: (PlatformChoice) -> Unit) {
    val media = state.media
    var posterFailed by remember(media.thumbnailUrl) { mutableStateOf(false) }
    val showPoster = media.thumbnailUrl != null && !posterFailed

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column {
            if (showPoster) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    AsyncImage(
                        model = media.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { posterFailed = true },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitleOf(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onChoose(best) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).pressScale(0.98f),
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(downloadLabel(best), style = MaterialTheme.typography.labelLarge)
                }
                if (best.detail.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = best.detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Post(state: LinkState.FoundPost, onSave: (List<Int>) -> Unit) {
    val postItems = state.items
    val rows = postItems.withIndex().chunked(POST_COLUMNS)

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "post") {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp)) {
                Text(
                    text = state.media.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(state.media.author, countOf(postItems), state.match.platform.displayName)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onSave(postItems.indices.toList()) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).pressScale(0.98f),
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(saveAllLabel(postItems), style = MaterialTheme.typography.labelLarge)
                }
                if (postItems.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Or tap one to save just that.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (postItems.size > 1) {
            items(rows, key = { row -> "row" + row.first().index }) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                ) {
                    row.forEach { (index, item) ->
                        PostTile(
                            item = item,
                            description = "Save ${kindOf(item)} ${index + 1} of ${postItems.size}",
                            onClick = { onSave(listOf(index)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep the last row's tiles the same size as the others.
                    repeat(POST_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PostTile(item: PostItem, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var failed by remember(item.url) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .pressScale(0.97f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (!item.isVideo && !failed) {
            AsyncImage(
                model = item.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Icon(
                imageVector = if (item.isVideo) Icons.Default.PlayArrow else Icons.Default.FileDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private const val POST_COLUMNS = 3

private fun kindOf(item: PostItem) = if (item.isVideo) "video" else "picture"

/** "4 pictures", "3 videos", "5 items" for a mix. */
private fun countOf(items: List<PostItem>): String {
    val kinds = items.map { kindOf(it) }.distinct()
    val noun = if (kinds.size == 1) kinds.single() else "item"
    return if (items.size == 1) "1 $noun" else "${items.size} ${noun}s"
}

/** "Save picture", "Save video", or "Save all 5". */
private fun saveAllLabel(items: List<PostItem>): String =
    if (items.size == 1) "Save ${kindOf(items.single())}" else "Save all ${items.size}"

@Composable
private fun ChoiceRow(choice: PlatformChoice, onChoose: (PlatformChoice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChoose(choice) }
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .semantics { contentDescription = "Download ${choice.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (choice.isAudioOnly) Icons.Default.MusicNote else Icons.Default.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = choice.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (choice.detail.isNotEmpty()) {
                Text(
                    text = choice.detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.FileDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun Failed(state: LinkState.Failed, onRetry: () -> Unit, onRetryWithSignIn: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = "Couldn't get this video",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.problem.message ?: "Something went wrong.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRetry,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(48.dp).pressScale(0.98f),
            ) {
                Text("Try again", style = MaterialTheme.typography.labelLarge)
            }
            if (state.signInMayHelp) {
                FilledTonalButton(
                    onClick = onRetryWithSignIn,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(48.dp).pressScale(0.98f),
                ) {
                    Text("Use my sign-in", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (state.signInMayHelp) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "This uses the account you're signed in to here. Some sites limit accounts that " +
                    "download a lot, so it is only done when you ask.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Download 1080p", "Download audio", or just "Download" for a bare file with no quality to name. */
private fun downloadLabel(choice: PlatformChoice): String = when {
    choice.isAudioOnly -> "Download audio"
    choice.quality == null -> "Download"
    else -> "Download ${choice.label}"
}

/** "Rick Astley · 3:33 · YouTube". */
private fun subtitleOf(state: LinkState.Found): String =
    listOfNotNull(
        state.media.author,
        state.media.durationSeconds?.let { MediaNamer.formatDuration(it) },
        state.match.platform.displayName,
    ).joinToString(" · ")

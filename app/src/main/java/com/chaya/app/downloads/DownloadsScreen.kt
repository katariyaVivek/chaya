package com.chaya.app.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaya.app.download.DownloadNotification.formatFileSize
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import com.chaya.app.ui.theme.ChayaMotion
import com.chaya.app.ui.theme.LocalChayaColors
import com.chaya.app.ui.theme.StaggeredAppear
import com.chaya.app.ui.theme.pressScale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onNavigateBack: () -> Unit,
    onPlayStream: (Long) -> Unit,
    viewModel: DownloadsViewModel = viewModel()
) {
    val tasks by viewModel.downloads.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (tasks.isEmpty()) {
            EmptyDownloads(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(tasks, key = { it.id }) { task ->
                    DownloadItem(
                        task = task,
                        modifier = Modifier.animateItem(),
                        onPause = { viewModel.pause(task.id) },
                        onResume = { viewModel.resume(task.id) },
                        onCancel = { viewModel.cancel(task.id) },
                        onDelete = { viewModel.delete(task.id) },
                        onOpen = {
                            viewModel.open(task) { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                        onPlay = { onPlayStream(task.id) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloads(modifier: Modifier = Modifier) {
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
                    text = "Nothing saved yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Browse a page and tap the floating button\nto download what you find.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        }
    }
}

@Composable
private fun DownloadItem(
    task: DownloadTask,
    modifier: Modifier = Modifier,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tonal circle with media-type icon
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconForMime(task.mimeType),
                contentDescription = null,
                tint = if (task.mimeType?.startsWith("audio/") == true)
                    MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.fileName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            StateArea(task)
        }

        Spacer(Modifier.width(4.dp))
        ActionsRow(task, onPause, onResume, onCancel, onDelete, onOpen, onPlay)
    }
}

/** Animated state area: progress bar while downloading, chip otherwise. */
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
            else -> StateChip(task, state)
        }
    }
}

@Composable
private fun ProgressBlock(task: DownloadTask) {
    val fraction by animateFloatAsState(
        targetValue = task.progressFraction.coerceIn(0f, 1f),
        animationSpec = ChayaMotion.tweenStandard(),
        label = "progressFraction"
    )

    Column {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        Spacer(Modifier.height(5.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${formatFileSize(task.downloadedBytes)} / ${formatFileSize(task.totalBytes ?: 0)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StateChip(task: DownloadTask, state: DownloadState) {
    val extended = LocalChayaColors.current

    data class ChipStyle(val dot: Color, val label: String, val text: Color?, val bg: Color?)

    val (dotColor, textColor, bgColor) = when (state) {
        DownloadState.COMPLETED -> Triple(
            extended.success,
            extended.successContainer,
            null
        )
        DownloadState.FAILED -> Triple(
            MaterialTheme.colorScheme.error,
            null,
            null
        )
        DownloadState.PAUSED -> Triple(MaterialTheme.colorScheme.tertiary, null, null)
        else -> Triple(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), null, null)
    }

    val label = when (state) {
        DownloadState.QUEUED -> "Queued"
        DownloadState.PAUSED -> "Paused · ${formatFileSize(task.downloadedBytes)}"
        DownloadState.COMPLETED ->
            "Saved · ${formatFileSize(task.totalBytes ?: task.downloadedBytes)}"
        DownloadState.FAILED -> task.errorMessage?.takeIf { it.isNotBlank() }?.let { "Failed · $it" }
            ?: "Failed"
        DownloadState.CANCELLED -> "Stopped · ${formatFileSize(task.downloadedBytes)}"
        else -> ""
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
            color = when (state) {
                DownloadState.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
private fun ActionsRow(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (task.state) {
            DownloadState.DOWNLOADING -> {
                ActionIcon(Icons.Default.PauseCircle, "Pause", onPause,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionIcon(Icons.Default.Close, "Cancel", onCancel,
                    tint = MaterialTheme.colorScheme.error)
            }
            DownloadState.QUEUED -> {
                ActionIcon(Icons.Default.Close, "Cancel", onCancel,
                    tint = MaterialTheme.colorScheme.error)
            }
            DownloadState.PAUSED, DownloadState.CANCELLED -> {
                ActionIcon(Icons.Default.PlayArrow, "Resume", onResume,
                    tint = MaterialTheme.colorScheme.primary)
                ActionIcon(Icons.Default.Delete, "Delete", onDelete)
            }
            DownloadState.FAILED -> {
                ActionIcon(Icons.Default.PlayArrow, "Retry", onResume,
                    tint = MaterialTheme.colorScheme.primary)
                ActionIcon(Icons.Default.Delete, "Delete", onDelete)
            }
            DownloadState.COMPLETED -> {
                // Streams live in Media3's cache (no single file) — play in-app.
                if (task.filePath != null || task.exportedUri != null) {
                    ActionIcon(Icons.Default.OpenInNew, "Open", onOpen,
                        tint = MaterialTheme.colorScheme.primary)
                } else {
                    ActionIcon(Icons.Default.PlayArrow, "Play", onPlay,
                        tint = MaterialTheme.colorScheme.primary)
                }
                ActionIcon(Icons.Default.Delete, "Delete", onDelete)
            }
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.pressScale(0.88f)
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint)
    }
}

private fun iconForMime(mimeType: String?): ImageVector = when {
    mimeType?.startsWith("video/") == true -> Icons.Default.Movie
    mimeType?.startsWith("audio/") == true -> Icons.Default.MusicNote
    else -> Icons.Default.Movie
}

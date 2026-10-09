package com.chaya.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chaya.app.detection.MediaSheetModel
import com.chaya.app.detection.RankedMedia
import com.chaya.app.model.MediaKind
import com.chaya.app.ui.theme.ChayaMotion
import com.chaya.app.ui.theme.pressScale

/**
 * Presents a page's media the way a person thinks about it: the main video
 * first with its real title, other media below, likely ads folded away, and
 * stream pieces hidden behind a one-line footnote.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectedMediaSheet(
    model: MediaSheetModel,
    onDismiss: () -> Unit,
    onDownload: (RankedMedia) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var adsExpanded by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                SheetHeader(count = model.visibleCount)
            }

            if (model.isEmpty) {
                item(key = "empty") {
                    Note("Nothing found yet. Play or scroll the page.")
                }
            }

            model.primary?.let { primary ->
                item(key = "primary") {
                    PrimaryMediaCard(item = primary, onDownload = onDownload)
                }
            }

            if (model.primary == null && model.likelyAds.isNotEmpty()) {
                item(key = "only-ads") {
                    Note("Only ads so far. Play the video you want, then check again.")
                }
            }

            if (model.others.isNotEmpty()) {
                item(key = "others-label") {
                    SectionLabel(if (model.primary != null) "More on this page" else "On this page")
                }
                items(model.others, key = { "other:" + it.media.normalizedUrl }) { item ->
                    MediaRow(item = item, onDownload = onDownload)
                }
            }

            if (model.likelyAds.isNotEmpty()) {
                item(key = "ads-toggle") {
                    AdsToggle(
                        count = model.likelyAds.size,
                        expanded = adsExpanded,
                        onToggle = { adsExpanded = !adsExpanded },
                    )
                }
                if (adsExpanded) {
                    items(model.likelyAds, key = { "ad:" + it.media.normalizedUrl }) { item ->
                        MediaRow(item = item, onDownload = onDownload, dimmed = true)
                    }
                }
            }

            if (model.hiddenSegmentCount > 0) {
                item(key = "segments") {
                    Note(
                        text = "${model.hiddenSegmentCount} stream " +
                            (if (model.hiddenSegmentCount == 1) "piece" else "pieces") +
                            " hidden. They're parts of a stream, not separate videos.",
                        small = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "On this page",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The page's main item: real title, facts, and the one primary action on the sheet.
 * A poster gets the full 16:9 stage; without one (or when it fails to load) the
 * card stays compact rather than framing an empty box.
 */
@Composable
private fun PrimaryMediaCard(item: RankedMedia, onDownload: (RankedMedia) -> Unit) {
    var thumbnailFailed by remember(item.thumbnailUrl) { mutableStateOf(false) }
    val showStage = item.thumbnailUrl != null && !thumbnailFailed

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
    ) {
        Column {
            if (showStage) {
                MediaThumbnail(
                    item = item,
                    iconSize = 36.dp,
                    onError = { thumbnailFailed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            }
            Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!showStage) {
                        MediaThumbnail(
                            item = item.copy(thumbnailUrl = null),
                            iconSize = 24.dp,
                            corner = 14.dp,
                            modifier = Modifier.size(52.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onDownload(item) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .pressScale(0.98f),
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Download", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun MediaRow(
    item: RankedMedia,
    onDownload: (RankedMedia) -> Unit,
    dimmed: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .clickable { onDownload(item) }
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .alpha(if (dimmed) 0.72f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(
            item = item,
            iconSize = 20.dp,
            modifier = Modifier.size(width = 64.dp, height = 40.dp),
            corner = 10.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.FileDownload,
            contentDescription = "Download",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Poster or page image when known; a tonal kind icon underneath shows through until (or unless) it loads. */
@Composable
private fun MediaThumbnail(
    item: RankedMedia,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 0.dp,
    onError: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = iconFor(item.kind),
            contentDescription = null,
            tint = if (item.kind == MediaKind.AUDIO) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(iconSize),
        )
        item.thumbnailUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { onError() },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun AdsToggle(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = ChayaMotion.tweenShort(),
        label = "adsChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .semantics { contentDescription = if (expanded) "Hide likely ads" else "Show likely ads" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Likely ads · $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String, small: Boolean = false) {
    Text(
        text = text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = if (small) 12.dp else 20.dp),
    )
}

private fun iconFor(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.AUDIO -> Icons.Default.MusicNote
    MediaKind.STREAM -> Icons.Default.Stream
    else -> Icons.Default.Movie
}

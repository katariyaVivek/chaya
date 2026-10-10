package com.chaya.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** One page of the viewer: a picture, or a video shown by its frame with a play button. */
data class ViewerPage(
    val key: String,
    val title: String,
    /** What to draw: a file, an address, or null for nothing yet. */
    val model: Any?,
    val isVideo: Boolean = false,
    /** Fetches what to draw when the page is first shown, for a file still to be taken out of a ZIP. */
    val load: (suspend () -> Any?)? = null,
)

/** The page's picture: [ViewerPage.model], or what [ViewerPage.load] brings once the page is shown. */
@Composable
private fun pictureOf(page: ViewerPage): Any? {
    val loaded by produceState(initialValue = page.model, page.key) {
        page.load?.let { value = it() }
    }
    return loaded
}

/**
 * Pictures full screen on black, swiped between; pinch or double-tap to zoom, and while zoomed a drag moves the
 * picture instead of turning the page. A video page plays in the app the downloads already use.
 */
@Composable
fun PictureViewer(
    pages: List<ViewerPage>,
    start: Int,
    onClose: () -> Unit,
    onShare: (Int) -> Unit,
    onPlay: (Int) -> Unit,
    /** Save the page to the phone's folders; null when it is already there (a download is). */
    onSave: ((Int) -> Unit)? = null,
) {
    if (pages.isEmpty()) return
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, pages.lastIndex)) { pages.size }
    var zoomed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            key = { pages[it].key },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val page = pages[index]
            if (page.isVideo) {
                VideoPage(page, onPlay = { onPlay(index) })
            } else {
                ZoomablePicture(
                    page = page,
                    // Only the page in view reports, so a neighbour reset to 1x never unlocks a zoomed one.
                    onZoomed = { if (index == pager.currentPage) zoomed = it },
                )
            }
        }

        val current = pages[pager.currentPage.coerceIn(0, pages.lastIndex)]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(VIEWER_TITLE_TAG),
                )
                if (pages.size > 1) {
                    Text(
                        text = "${pager.currentPage + 1} of ${pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            if (onSave != null) {
                IconButton(onClick = { onSave(pager.currentPage) }) {
                    Icon(Icons.Default.SaveAlt, contentDescription = "Save to phone", tint = Color.White)
                }
            }
            IconButton(onClick = { onShare(pager.currentPage) }) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
            }
        }
    }
}

@Composable
private fun VideoPage(page: ViewerPage, onPlay: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        pictureOf(page)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        FilledTonalIconButton(
            onClick = onPlay,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
            ),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${page.title}", modifier = Modifier.size(40.dp))
        }
    }
}

/** The viewer's title, for tests to read which picture is shown. */
const val VIEWER_TITLE_TAG = "viewer-title"

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
private fun ZoomablePicture(page: ViewerPage, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    /** Keeps the picture covering the screen: it can move only as far as its zoomed edges. */
    fun clamp(value: Offset, at: Float): Offset {
        val maxX = size.width * (at - 1) / 2
        val maxY = size.height * (at - 1) / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    fun zoomTo(value: Float, pan: Offset = Offset.Zero) {
        scale = value.coerceIn(1f, MAX_ZOOM)
        offset = if (scale <= 1f) Offset.Zero else clamp(offset + pan, scale)
        onZoomed(scale > 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .semantics { contentDescription = page.title }
            .pointerInput(page.key) {
                detectTapGestures(onDoubleTap = { zoomTo(if (scale > 1f) 1f else DOUBLE_TAP_ZOOM) })
            }
            .pointerInput(page.key) {
                // Two fingers zoom; one finger moves the picture only while it is zoomed, and turns the page otherwise.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            zoomTo(scale * event.calculateZoom(), event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        pictureOf(page)?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

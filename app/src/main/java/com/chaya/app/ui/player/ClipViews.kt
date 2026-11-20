package com.chaya.app.ui.player

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaya.app.subtitles.Clip
import com.chaya.app.subtitles.Clips
import com.chaya.app.subtitles.PeakMeter
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant
import com.chaya.app.ui.theme.StageAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** "0:12–0:31 · 19 s". */
fun clipFacts(start: Long, end: Long): String =
    "${clockOf(start)}–${clockOf(end)} · ${String.format(Locale.ROOT, if (end - start < 10_000) "%.1f s" else "%.0f s", (end - start) / 1000.0)}"

/** The clips under the video's name, beside its chapters: "3 clips", opening their list. */
@Composable
fun ClipsButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = NightContainer,
        border = BorderStroke(1.dp, NightOutlineVariant),
        modifier = modifier.semantics { contentDescription = "Clips" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ContentCut, contentDescription = null, tint = NightMuted, modifier = Modifier.size(16.dp))
            Text(if (count == 1) "1 clip" else "$count clips", color = NightInk, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/**
 * Lines chosen in the transcript: how many and their span, *Clip* to make them one, *Put right* for one line, and ×.
 * Tapping more lines in the transcript takes the choice to them.
 */
@Composable
fun ChosenBar(count: Int, start: Long, end: Long, onClip: () -> Unit, onFix: (() -> Unit)?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = NightContainer,
        border = BorderStroke(1.dp, NightOutlineVariant),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(if (count == 1) "1 line" else "$count lines", color = NightInk, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                Text(clipFacts(start, end), color = NightMuted, style = TextStyle(fontSize = 12.sp))
            }
            if (onFix != null) TextButton(onClick = onFix) { Text("Put right", color = NightMuted) }
            TextButton(onClick = onClip) { Text("Clip", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold)) }
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Stop choosing", tint = NightMuted) }
        }
    }
}

/** Every clip of the video, in order: its name and span; a tap opens it on its timeline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsSheet(clips: List<Clip>, onOpen: (Clip) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = NightGround) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Heading("CLIPS")
            clips.forEach { clip ->
                Surface(
                    onClick = { onOpen(clip) },
                    shape = RoundedCornerShape(14.dp),
                    color = NightContainer,
                    border = BorderStroke(1.dp, NightOutlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(clip.name, color = NightInk, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                        Text(clipFacts(clip.start, clip.end), color = NightMuted, style = TextStyle(fontSize = 13.sp))
                    }
                }
            }
            Text(
                "Make one by choosing lines in the transcript (a long press, then tap the last line), or from a highlight.",
                color = NightMuted,
                style = TextStyle(fontSize = 13.sp),
            )
        }
    }
}

/**
 * A clip on its timeline: the part of the video around it, its frames along the top and its sound's loudness under
 * them, the clip lit between two handles that are dragged to trim it (snapping to the lines and chapters), and where
 * the video is. Its name, *Play*, *Save* and *Delete* under it; [extra] adds what a later step offers (export).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipEditor(
    clip: Clip,
    durationMs: Long,
    atMs: Long,
    edges: Pair<List<Long>, List<Long>>,
    videoId: Long,
    file: Uri?,
    onPlay: (Clip) -> Unit,
    onSeek: (Long) -> Unit,
    onSave: (Clip) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable (Clip) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var edited by remember(clip.id) { mutableStateOf(clip) }
    val window = remember(clip.id, durationMs) { Clips.window(clip, durationMs) }
    val context = LocalContext.current
    val peaks by produceState<ByteArray?>(null, videoId, file) {
        value = file?.let { withContext(Dispatchers.IO) { ClipMedia.soundPeaks(context, videoId, it) } }
    }
    val frameHeight = with(LocalDensity.current) { 44.dp.roundToPx() }
    val frames by produceState(emptyList<ImageBitmap?>(), window, file) {
        val uri = file ?: return@produceState
        val times = (0 until FRAMES).map { window.first + (window.last - window.first) * (2 * it + 1) / (2 * FRAMES) }
        value = withContext(Dispatchers.IO) { times.map { ClipMedia.frameAt(context, uri, it, frameHeight)?.asImageBitmap() } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = NightGround) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("CLIP · ${clipFacts(edited.start, edited.end).uppercase(Locale.getDefault())}")
            TextField(
                value = edited.name,
                onValueChange = { edited = edited.copy(name = it) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = NightInk),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NightContainer,
                    unfocusedContainerColor = NightContainer,
                    focusedIndicatorColor = NightContainer,
                    unfocusedIndicatorColor = NightContainer,
                    cursorColor = NightInk,
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "The clip's name" },
            )
            ClipTimeline(
                window = window,
                clip = edited,
                atMs = atMs,
                peaks = peaks,
                frames = frames,
                onEdge = { start, ms -> edited = Clips.moveEdge(edited, start, ms, if (start) edges.first else edges.second, durationMs) },
                onSeek = onSeek,
            )
            Text("Drag an edge to trim it; it settles on the nearest line.", color = NightMuted, style = TextStyle(fontSize = 13.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(onClick = { onSave(edited) }, shape = RoundedCornerShape(14.dp), color = NightInk) {
                    Text("Save", color = NightGround, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp))
                }
                TextButton(onClick = { onPlay(edited) }) { Text("Play", color = NightInk) }
                TextButton(onClick = onDelete) { Text("Delete", color = NightMuted) }
            }
            extra(edited)
        }
    }
}

private const val FRAMES = 6

/** The timeline itself (see [ClipEditor]). */
@Composable
fun ClipTimeline(
    window: LongRange,
    clip: Clip,
    atMs: Long,
    peaks: ByteArray?,
    frames: List<ImageBitmap?>,
    onEdge: (start: Boolean, ms: Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = (window.last - window.first).coerceAtLeast(1)
    val current by rememberUpdatedState(clip)
    val handleReach = with(LocalDensity.current) { 28.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Clip timeline" }
            .pointerInput(window) {
                detectTapGestures { offset -> onSeek(window.first + (offset.x / size.width * span).toLong()) }
            }
            .pointerInput(window) {
                var dragging: Boolean? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        val x = { ms: Long -> (ms - window.first).toFloat() / span * size.width }
                        val toStart = abs(offset.x - x(current.start))
                        val toEnd = abs(offset.x - x(current.end))
                        dragging = when {
                            minOf(toStart, toEnd) > handleReach -> null
                            toStart <= toEnd -> true
                            else -> false
                        }
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    val start = dragging ?: return@detectDragGestures
                    change.consume()
                    onEdge(start, window.first + (change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * span).toLong())
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            drawRoundRect(NightContainer, cornerRadius = CornerRadius(12.dp.toPx()))
            // Frames along the top
            val frameHeight = 44.dp.toPx()
            val slot = size.width / FRAMES
            frames.forEachIndexed { i, frame ->
                frame ?: return@forEachIndexed
                val w = minOf(slot, frame.width * frameHeight / frame.height)
                drawImage(
                    frame,
                    dstOffset = IntOffset((i * slot + (slot - w) / 2).toInt(), 0),
                    dstSize = IntSize(w.toInt(), frameHeight.toInt()),
                    alpha = 0.85f,
                )
            }
            // The sound's loudness under them, a bar for each few peaks
            val soundTop = frameHeight + 6.dp.toPx()
            val soundHeight = size.height - soundTop - 6.dp.toPx()
            peaks?.let { levels ->
                val bars = (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1)
                for (b in 0 until bars) {
                    val from = window.first + span * b / bars
                    val to = window.first + span * (b + 1) / bars
                    val a = (from * PeakMeter.PER_SECOND / 1000).toInt().coerceIn(0, levels.size)
                    val z = (to * PeakMeter.PER_SECOND / 1000).toInt().coerceIn(a, levels.size)
                    val level = (a until maxOf(z, a + 1).coerceAtMost(levels.size)).maxOfOrNull { levels[it].toInt() and 0xff } ?: 0
                    val h = maxOf(1.dp.toPx(), soundHeight * level / 255f)
                    drawRect(NightMuted.copy(alpha = 0.7f), topLeft = Offset(b * size.width / bars, soundTop + (soundHeight - h) / 2), size = Size(maxOf(1f, size.width / bars - 1.dp.toPx()), h))
                }
            }
            fun x(ms: Long) = ((ms - window.first).toFloat() / span * size.width).coerceIn(0f, size.width)
            val left = x(clip.start)
            val right = x(clip.end)
            // Outside the clip dimmed; the clip outlined in amber, its handles in ink
            drawRect(Color.Black.copy(alpha = 0.5f), size = Size(left, size.height))
            drawRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(right, 0f), size = Size(size.width - right, size.height))
            val stroke = 2.dp.toPx()
            drawRect(StageAmber, topLeft = Offset(left, 0f), size = Size(right - left, stroke))
            drawRect(StageAmber, topLeft = Offset(left, size.height - stroke), size = Size(right - left, stroke))
            val handle = 8.dp.toPx()
            drawRoundRect(NightInk, topLeft = Offset(left - handle / 2, 0f), size = Size(handle, size.height), cornerRadius = CornerRadius(handle / 2))
            drawRoundRect(NightInk, topLeft = Offset(right - handle / 2, 0f), size = Size(handle, size.height), cornerRadius = CornerRadius(handle / 2))
            // Where the video is
            if (atMs in window) drawRect(Color.White, topLeft = Offset(x(atMs) - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
        }
    }
}

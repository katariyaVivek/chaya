package com.chaya.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaya.app.subtitles.Chapter
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant

/** The space left between two chapters on the seek bar. */
private val CHAPTER_GAP = 3.dp

/**
 * Where on a seek bar of a video [lengthMs] long each chapter after the first begins, from 0 to 1; none until the length
 * is known.
 */
fun chapterMarks(chapters: List<Chapter>, lengthMs: Long): List<Float> {
    if (lengthMs <= 0) return emptyList()
    return chapters.map { it.start.toFloat() / lengthMs }.filter { it > 0f && it < 1f }
}

/**
 * The seek bar's track for a video with chapters, as Chaya for Windows draws it: one piece a chapter, a small gap at
 * each [marks] where the next begins, played up to [fraction] in ink.
 */
@Composable
fun ChapterTrack(fraction: Float, marks: List<Float>, enabled: Boolean, modifier: Modifier = Modifier) {
    val played = if (enabled) NightInk else NightMuted
    val left = Color.White.copy(alpha = if (enabled) 0.22f else 0.12f)
    Canvas(modifier = modifier.fillMaxWidth().height(4.dp)) {
        val gap = CHAPTER_GAP.toPx()
        val height = size.height
        val corner = CornerRadius(height / 2)
        val playedTo = fraction.coerceIn(0f, 1f) * size.width
        (listOf(0f) + marks + listOf(1f)).zipWithNext { from, to ->
            val start = from * size.width + if (from > 0f) gap / 2 else 0f
            val end = to * size.width - if (to < 1f) gap / 2 else 0f
            if (end <= start) return@zipWithNext
            drawRoundRect(left, topLeft = Offset(start, 0f), size = Size(end - start, height), cornerRadius = corner)
            val playedEnd = minOf(end, playedTo)
            if (playedEnd > start) {
                drawRoundRect(played, topLeft = Offset(start, 0f), size = Size(playedEnd - start, height), cornerRadius = corner)
            }
        }
    }
}

/**
 * The chapter playing (or the one the seek bar is dragged to), under the video's name: "3 of 8 · The walk". Tapping it
 * lists every chapter.
 */
@Composable
fun ChapterButton(chapters: List<Chapter>, current: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val index = current?.takeIf { it in chapters.indices }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = NightContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, NightOutlineVariant),
        modifier = modifier.semantics { contentDescription = "Chapters" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = NightMuted, modifier = Modifier.size(18.dp))
            Text(
                text = if (index == null) "${chapters.size} chapters" else "${index + 1} of ${chapters.size}",
                color = NightMuted,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
            if (index != null) {
                Text(
                    text = chapters[index].title,
                    color = NightInk,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Every chapter with the time it begins, the one playing in ink; tapping one plays from it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSheet(chapters: List<Chapter>, current: Int?, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { current?.let { list.scrollToItem(it) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = NightGround) {
        Text(
            text = "CHAPTERS",
            color = NightMuted,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
        )
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            itemsIndexed(chapters, key = { index, _ -> index }) { index, chapter ->
                val now = index == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (now) NightContainer else Color.Transparent)
                        .clickable(onClickLabel = "Play from here") { onSeek(chapter.start) }
                        .semantics { selected = now }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = clockOf(chapter.start),
                        color = NightMuted,
                        style = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        text = chapter.title,
                        color = if (now) NightInk else NightMuted,
                        style = TextStyle(fontSize = 15.sp, fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal),
                    )
                }
            }
        }
    }
}

package com.chaya.app.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaya.app.subtitles.SubtitleLine
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import java.util.Locale

/** "English" for "en", "Portuguese (Brazil)" for "pt-BR"; the tag itself when the phone has no name for it. */
fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    val name = locale.getDisplayName(Locale.getDefault())
    return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) } ?: tag
}

/**
 * The video's subtitles as a transcript (Stage, as Chaya for Windows has it): every line with the time it is said, the
 * one being said in ink and the rest muted, kept in view as the video plays. Tapping a line plays from it; a long press
 * on one, or the pencil by the heading (for the line being said), puts it right ([onFix]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Transcript(
    lines: List<SubtitleLine>,
    language: String,
    current: Int?,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onFix: ((Int) -> Unit)? = null,
) {
    val list = rememberLazyListState()
    // Follows the line being said, with the one before it in view (item 0 is the heading), unless the person is scrolling
    LaunchedEffect(current) {
        if (current != null && !list.isScrollInProgress) list.animateScrollToItem(current)
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        item(key = "heading") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    text = "TRANSCRIPT · ${languageName(language).uppercase(Locale.getDefault())}",
                    color = NightMuted,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
                    modifier = Modifier.padding(start = 4.dp).weight(1f),
                )
                if (onFix != null && lines.isNotEmpty()) {
                    IconButton(onClick = { onFix(current ?: 0) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Put the subtitles right", tint = NightMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            val now = index == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (now) NightContainer else Color.Transparent)
                    .combinedClickable(
                        onClickLabel = "Play from here",
                        onLongClickLabel = if (onFix != null) "Put this line right" else null,
                        onLongClick = onFix?.let { fix -> { fix(index) } },
                        onClick = { onSeek(line.start) },
                    )
                    .semantics { selected = now }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = clockOf(line.start),
                    color = NightMuted,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 20.sp),
                    modifier = Modifier.width(48.dp),
                )
                Text(
                    text = line.text,
                    color = if (now) NightInk else NightMuted,
                    style = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                )
            }
        }
    }
}

/**
 * The line being said, over the foot of the picture: white on a dark band, as subtitles are drawn. Hidden from
 * accessibility services, which read the transcript's line instead of the same words twice.
 */
@Composable
fun SubtitleOverlay(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = text,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .clearAndSetSemantics {},
        )
    }
}

package com.chaya.app.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.chaya.app.subtitles.LineSearch
import com.chaya.app.ui.player.clockOf

/** "Said 12 times in 3 videos", "Said once in 1 video": how often the words searched for are said. */
fun saidSummary(results: LineSearch.Results): String {
    val times = if (results.total == 1) "once" else "${results.total} times"
    val videos = if (results.videos == 1) "1 video" else "${results.videos} videos"
    return "Said $times in $videos"
}

/** A line's text with what matched in bold, the rest as it is. */
fun markedText(hit: LineSearch.Hit): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (mark in hit.marks) {
        val start = mark.first.coerceIn(at, hit.text.length)
        val end = (mark.last + 1).coerceIn(start, hit.text.length)
        append(hit.text.substring(at, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(hit.text.substring(start, end)) }
        at = end
    }
    append(hit.text.substring(at))
}

/**
 * Under the Library's search box, when the words are said in any saved video: how often, and in how many. Tapping it
 * lists the lines ([SaidSheet]).
 */
@Composable
fun SaidRow(results: LineSearch.Results, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ClosedCaption, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(saidSummary(results), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Every line found, under the name of the video it is said in, each with its time and the words searched for in bold.
 * Tapping a line plays the video from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaidSheet(results: LineSearch.Results, onPlay: (LineSearch.Hit) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val videos = results.hits.groupBy { it.id }.values.toList()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = saidSummary(results),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
        )
        if (results.total > results.hits.size) {
            Text(
                text = "The first ${results.hits.size} are listed; more words find fewer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        ) {
            videos.forEach { hits ->
                item(key = "video:${hits.first().id}") {
                    Text(
                        text = hits.first().title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(hits, key = { index, hit -> "line:${hit.id}:$index" }) { _, hit ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = "Play from here") { onPlay(hit) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = clockOf(hit.start),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(56.dp).padding(top = 2.dp),
                        )
                        Text(text = markedText(hit), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

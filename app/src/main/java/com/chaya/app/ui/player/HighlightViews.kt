package com.chaya.app.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaya.app.subtitles.Finding
import com.chaya.app.subtitles.FoundHighlights
import com.chaya.app.subtitles.Highlight
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant
import com.chaya.app.ui.theme.StageAmber

/** How many highlights can be asked for. */
val HIGHLIGHT_COUNTS = listOf(5, 10, 20)

/** Each highlight's place on the seek bar, from 0 to 1. */
fun highlightSpans(highlights: List<Highlight>, lengthMs: Long): List<ClosedFloatingPointRange<Float>> {
    if (lengthMs <= 0) return emptyList()
    return highlights.map { (it.start.toFloat() / lengthMs).coerceIn(0f, 1f)..(it.end.toFloat() / lengthMs).coerceIn(0f, 1f) }
        .filter { it.endInclusive > it.start }
}

/** The highlights under the seek bar's track: an amber stroke for each, the signal that something is there. */
@Composable
fun HighlightMarks(spans: List<ClosedFloatingPointRange<Float>>, modifier: Modifier = Modifier) {
    if (spans.isEmpty()) return
    Canvas(modifier = modifier.fillMaxWidth().height(3.dp)) {
        val corner = CornerRadius(size.height / 2)
        spans.forEach { span ->
            val start = span.start * size.width
            val width = maxOf((span.endInclusive - span.start) * size.width, size.height * 2)
            drawRoundRect(StageAmber, topLeft = Offset(start, 0f), size = Size(width, size.height), cornerRadius = corner)
        }
    }
}

/** "1:20–2:05". */
fun spanOf(highlight: Highlight) = "${clockOf(highlight.start)}–${clockOf(highlight.end)}"

/**
 * *Highlights*: the moments worth watching, each with its time, title and why; a tap plays from one. Under them, how
 * many to find and *Find highlights* (again), or how it is going with *Stop*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsSheet(
    found: FoundHighlights?,
    state: Finding,
    onPlay: (Highlight) -> Unit,
    onFind: (Int) -> Unit,
    onStop: () -> Unit,
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var count by rememberSaveable { mutableIntStateOf(HIGHLIGHT_COUNTS.first()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = NightGround) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (found != null && found.items.isNotEmpty()) {
                Heading("HIGHLIGHTS · ${found.model.uppercase()}")
                found.items.forEach { highlight ->
                    Surface(
                        onClick = { onPlay(highlight) },
                        shape = RoundedCornerShape(14.dp),
                        color = NightContainer,
                        border = BorderStroke(1.dp, NightOutlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(spanOf(highlight), color = NightMuted, style = TextStyle(fontSize = 12.sp))
                            Text(highlight.title, color = NightInk, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                            if (highlight.reason.isNotEmpty()) {
                                Text(highlight.reason, color = NightMuted, style = TextStyle(fontSize = 13.sp))
                            }
                        }
                    }
                }
            }
            when (state) {
                Finding.Running -> {
                    Text("Finding highlights…", color = NightInk, style = TextStyle(fontSize = 15.sp))
                    LinearProgressIndicator(color = StageAmber, trackColor = NightContainer, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = onStop) { Text("Stop", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold)) }
                }
                else -> {
                    if (state is Finding.Failed) Text(state.message, color = NightInk, style = TextStyle(fontSize = 14.sp))
                    Heading(if (found == null) "FIND THE MOMENTS WORTH WATCHING" else "FIND AGAIN")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HIGHLIGHT_COUNTS.forEach { n ->
                            val chosen = n == count
                            Surface(
                                onClick = { count = n },
                                shape = RoundedCornerShape(12.dp),
                                color = if (chosen) NightInk else NightContainer,
                                modifier = Modifier.semantics { selected = chosen },
                            ) {
                                Text(
                                    "$n",
                                    color = if (chosen) NightGround else NightInk,
                                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(onClick = { onFind(count) }, shape = RoundedCornerShape(14.dp), color = NightInk) {
                            Text(
                                if (found == null) "Find highlights" else "Find again",
                                color = NightGround,
                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                            )
                        }
                        TextButton(onClick = onSetUp) { Text("AI model", color = NightMuted) }
                    }
                    Text(
                        "Your own AI model reads the subtitles and picks the moments that stand on their own.",
                        color = NightMuted,
                        style = TextStyle(fontSize = 13.sp),
                    )
                }
            }
        }
    }
}

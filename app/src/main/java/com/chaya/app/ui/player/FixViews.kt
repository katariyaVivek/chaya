package com.chaya.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaya.app.subtitles.Retime
import com.chaya.app.subtitles.SubtitleLine
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant
import kotlin.math.abs

/** What moving the whole track a little does, as Windows offers it: earlier or later by these many milliseconds. */
val NUDGES = listOf(-500L, -100L, 100L, 500L)

/** "−0.5 s", "+0.1 s": a nudge as its button says it. */
fun nudgeLabel(by: Long): String = (if (by < 0) "−" else "+") + "%.1f s".format(java.util.Locale.ROOT, abs(by) / 1000.0)

/**
 * What "said now" does to the line at [lineStart] with the video at [atMs]: with a line already put right at [synced]
 * far enough away, the lines stretch between the two; otherwise every line moves.
 */
fun saidNowStretches(synced: Long?, lineStart: Long): Boolean =
    synced != null && abs(lineStart - synced) >= Retime.NEAREST_ANCHORS

/**
 * A line put right, as Windows has it in its transcript: its words, corrected (empty takes it out); "said now", which
 * moves the lines so this one starts where the video is ([atMs]), or stretches them since the line put right at
 * [synced]; and every line a little earlier or later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixSheet(
    line: SubtitleLine,
    atMs: Long,
    synced: Long?,
    onKeep: (String) -> Unit,
    onSaidNow: () -> Unit,
    onNudge: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable(line) { mutableStateOf(line.text) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = NightGround) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("LINE AT ${clockOf(line.start)}")
            TextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(fontSize = 16.sp, color = NightInk),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NightContainer,
                    unfocusedContainerColor = NightContainer,
                    focusedIndicatorColor = NightContainer,
                    unfocusedIndicatorColor = NightContainer,
                    cursorColor = NightInk,
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "The line's words" },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Leave it empty to take the line out.",
                    color = NightMuted,
                    style = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                )
                val changed = text.trim() != line.text.trim()
                TextButton(onClick = { onKeep(text) }, enabled = changed) {
                    Text(
                        if (text.isBlank()) "Take out" else "Keep",
                        color = if (changed) NightInk else NightMuted,
                        style = TextStyle(fontWeight = FontWeight.SemiBold),
                    )
                }
            }

            Heading("TIMING · EVERY LINE")
            val stretches = saidNowStretches(synced, line.start)
            Surface(
                onClick = onSaidNow,
                shape = RoundedCornerShape(14.dp),
                color = NightContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, NightOutlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        "This line is said at ${clockOf(atMs)}",
                        color = NightInk,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        if (stretches && synced != null) "Stretches the lines between ${clockOf(synced)} and here, for subtitles that drift"
                        else "Moves every line so this one starts where the video is",
                        color = NightMuted,
                        style = TextStyle(fontSize = 13.sp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NUDGES.forEach { by ->
                    Surface(
                        onClick = { onNudge(by) },
                        shape = RoundedCornerShape(12.dp),
                        color = NightContainer,
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = "Every line ${"%.1f".format(java.util.Locale.ROOT, abs(by) / 1000.0)} seconds ${if (by < 0) "earlier" else "later"}"
                        },
                    ) {
                        Text(
                            nudgeLabel(by),
                            color = NightInk,
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** What the last change did, with Undo while there is something to undo. */
@Composable
fun FixedBar(said: String, canUndo: Boolean, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = NightContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, NightOutlineVariant),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(said, color = NightInk, style = TextStyle(fontSize = 14.sp), modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            if (canUndo) {
                TextButton(onClick = onUndo) {
                    Text("Undo", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        color = NightMuted,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
    )
}

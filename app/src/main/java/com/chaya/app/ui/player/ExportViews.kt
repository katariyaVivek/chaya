package com.chaya.app.ui.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaya.app.subtitles.ClipExport
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.StageAmber
import java.io.File

/** What a clip is saved as. */
enum class ExportKind(val label: String, val extension: String, val mimeType: String) {
    WITH_SUBTITLES("Video with subtitles", "mp4", "video/mp4"),
    VIDEO("Video", "mp4", "video/mp4"),
    SRT("Subtitles (SRT)", "srt", "application/x-subrip"),
}

/** How saving a clip is going. One value. */
sealed class Exporting {
    data object Idle : Exporting()
    data class Running(val progress: Float) : Exporting()
    data class Done(val file: File, val mimeType: String) : Exporting()
    data class Failed(val message: String) : Exporting()
}

/** "10 MB", or "As it is". */
fun sizeLabel(mb: Int?) = if (mb == null) "As it is" else "$mb MB"

/**
 * Under a clip's timeline: what to save it as (a video with its subtitles drawn on, a clean video, or its subtitles as
 * SRT), a size to fit for sending, *Save clip*; then how far, with *Stop*; then *Share* and *Save to phone*.
 */
@Composable
fun ExportPanel(
    hasSubtitles: Boolean,
    state: Exporting,
    onExport: (ExportKind, Int?) -> Unit,
    onStop: () -> Unit,
    onShare: (File, String) -> Unit,
    onSave: (File, String) -> Unit,
) {
    val kinds = ExportKind.entries.filter { hasSubtitles || it == ExportKind.VIDEO }
    var kind by rememberSaveable { mutableStateOf(kinds.first()) }
    var size by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("SAVE AS")
        Chips(kinds, kind, { it.label }) { kind = it }
        if (kind != ExportKind.SRT) Chips(ClipExport.SIZES, size, ::sizeLabel) { size = it }
        when (state) {
            is Exporting.Running -> {
                Text("Making the clip · ${(state.progress * 100).toInt()}%", color = NightInk, style = TextStyle(fontSize = 15.sp))
                LinearProgressIndicator(progress = { state.progress }, color = StageAmber, trackColor = NightContainer, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onStop) { Text("Stop", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold)) }
            }
            else -> {
                if (state is Exporting.Failed) Text(state.message, color = NightInk, style = TextStyle(fontSize = 14.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(onClick = { onExport(kind, size.takeIf { kind != ExportKind.SRT }) }, shape = CircleShape, color = NightInk) {
                        Text("Save clip", color = NightGround, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp))
                    }
                    if (state is Exporting.Done) {
                        TextButton(onClick = { onShare(state.file, state.mimeType) }) { Text("Share", color = NightInk) }
                        TextButton(onClick = { onSave(state.file, state.mimeType) }) { Text("Save to phone", color = NightInk) }
                    }
                }
                if (state is Exporting.Done) Text(state.file.name, color = NightMuted, style = TextStyle(fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun <T> Chips(options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val on = option == chosen
            Surface(
                onClick = { onChoose(option) },
                shape = RoundedCornerShape(12.dp),
                color = if (on) NightInk else NightContainer,
                modifier = Modifier.semantics { selected = on },
            ) {
                Text(
                    label(option),
                    color = if (on) NightGround else NightInk,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

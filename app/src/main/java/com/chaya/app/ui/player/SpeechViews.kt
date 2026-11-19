package com.chaya.app.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaya.app.speech.Making
import com.chaya.app.speech.SpeechModel
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant
import com.chaya.app.ui.theme.StageAmber

/**
 * Where the transcript would be, for a video saved without subtitles: *Make subtitles*, listened to on the phone. The
 * model is chosen with its size said, since the first time it is fetched; then how far, with *Stop*.
 */
@Composable
fun MakeSubtitlesCard(
    state: Making,
    fetched: (SpeechModel) -> Boolean,
    onMake: (SpeechModel) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var model by rememberSaveable { mutableStateOf(SpeechModel.TINY) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = NightContainer,
        border = BorderStroke(1.dp, NightOutlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Heading("NO SUBTITLES")
            when (state) {
                is Making.Fetching -> {
                    Text(
                        "Fetching the ${state.model.label} model · ${state.done / 1_000_000} of ${state.total / 1_000_000} MB",
                        color = NightInk,
                        style = TextStyle(fontSize = 15.sp),
                    )
                    LinearProgressIndicator(
                        progress = { if (state.total > 0) state.done / state.total.toFloat() else 0f },
                        color = StageAmber,
                        trackColor = NightGround,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onStop) { Text("Stop", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold)) }
                }
                is Making.Listening -> {
                    Text("Listening · ${state.percent}%", color = NightInk, style = TextStyle(fontSize = 15.sp))
                    LinearProgressIndicator(progress = { state.percent / 100f }, color = StageAmber, trackColor = NightGround, modifier = Modifier.fillMaxWidth())
                    Text("On this phone, nothing sent. A long video takes a while; you can leave the player.", color = NightMuted, style = TextStyle(fontSize = 13.sp))
                    TextButton(onClick = onStop) { Text("Stop", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold)) }
                }
                else -> {
                    Text(
                        "Chaya can listen to this video on your phone and write its subtitles. Nothing is sent anywhere.",
                        color = NightInk,
                        style = TextStyle(fontSize = 15.sp),
                    )
                    if (state is Making.Failed) Text(state.message, color = NightInk, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeechModel.entries.forEach { option ->
                            val chosen = option == model
                            Surface(
                                onClick = { model = option },
                                shape = RoundedCornerShape(12.dp),
                                color = if (chosen) NightInk else NightGround,
                                modifier = Modifier.semantics { selected = chosen },
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(option.label, color = if (chosen) NightGround else NightInk, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                                    Text(
                                        "${option.note} · " + if (fetched(option)) "on the phone" else option.megabytes,
                                        color = if (chosen) NightGround else NightMuted,
                                        style = TextStyle(fontSize = 12.sp),
                                    )
                                }
                            }
                        }
                    }
                    Surface(onClick = { onMake(model) }, shape = RoundedCornerShape(14.dp), color = NightInk) {
                        Text(
                            if (fetched(model)) "Make subtitles" else "Fetch ${model.megabytes} and make subtitles",
                            color = NightGround,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        }
    }
}

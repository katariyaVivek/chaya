package com.chaya.app.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.chaya.app.speech.CloudSpeech
import com.chaya.app.speech.Engine
import com.chaya.app.speech.Making
import com.chaya.app.speech.SpeechModel
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant
import com.chaya.app.ui.theme.StageAmber

/** An engine as the card names it, and back, so the choice survives the phone turning. */
internal fun engineKey(engine: Engine): String = when (engine) {
    is Engine.OnPhone -> "phone:${engine.model.name}"
    is Engine.Cloud -> "cloud:${engine.speech.name}"
}

internal fun engineOf(key: String): Engine? {
    val (kind, name) = key.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
    return when (kind) {
        "phone" -> SpeechModel.entries.firstOrNull { it.name == name }?.let { Engine.OnPhone(it) }
        "cloud" -> CloudSpeech.entries.firstOrNull { it.name == name }?.let { Engine.Cloud(it) }
        else -> null
    }
}

/**
 * Where the transcript would be, for a video saved without subtitles: *Make subtitles*, listened to on the phone, or,
 * with a key saved for one under AI model, by Groq or OpenAI, which hear far better and take seconds but are sent the
 * sound. Each choice says its size, or where the sound goes and what it costs; then how far, with *Stop*.
 */
@Composable
fun MakeSubtitlesCard(
    state: Making,
    fetched: (SpeechModel) -> Boolean,
    onMake: (Engine) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** The services a key is saved for, which can be chosen. */
    clouds: List<CloudSpeech> = emptyList(),
    /** The models this phone can run (Parakeet needs sherpa-onnx's library), the first chosen until another is. */
    models: List<SpeechModel> = SpeechModel.entries,
) {
    val first = Engine.OnPhone(models.firstOrNull() ?: SpeechModel.TINY)
    var chosenKey by rememberSaveable { mutableStateOf(engineKey(first)) }
    val chosen = engineOf(chosenKey)?.takeIf { (it as? Engine.Cloud)?.speech?.let { s -> s in clouds } ?: ((it as Engine.OnPhone).model in models) } ?: first
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
                    Text(
                        if (state.service == null) "Listening · ${state.percent}%" else "${state.service} is listening · ${state.percent}%",
                        color = NightInk,
                        style = TextStyle(fontSize = 15.sp),
                    )
                    LinearProgressIndicator(progress = { state.percent / 100f }, color = StageAmber, trackColor = NightGround, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (state.service == null) "On this phone, nothing sent. A long video takes a while; you can leave the player."
                        else "The sound is sent to ${state.service} five minutes at a time. You can leave the player.",
                        color = NightMuted,
                        style = TextStyle(fontSize = 13.sp),
                    )
                    TextButton(onClick = onStop) { Text("Stop", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold)) }
                }
                else -> {
                    Text(
                        "Chaya can listen to this video and write its subtitles: on your phone, or with your own Groq or OpenAI key.",
                        color = NightInk,
                        style = TextStyle(fontSize = 15.sp),
                    )
                    if (state is Making.Failed) Text(state.message, color = NightInk, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    val options = models.map { Engine.OnPhone(it) } + clouds.map { Engine.Cloud(it) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { option ->
                            val on = option == chosen
                            Surface(
                                onClick = { chosenKey = engineKey(option) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (on) NightInk else NightGround,
                                modifier = Modifier.semantics { selected = on },
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    val (title, note) = when (option) {
                                        is Engine.OnPhone -> option.model.label to
                                            "${option.model.note} · " + if (fetched(option.model)) "on the phone" else option.model.megabytes
                                        is Engine.Cloud -> option.speech.label to "Most accurate · seconds"
                                    }
                                    Text(title, color = if (on) NightGround else NightInk, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                                    Text(note, color = if (on) NightGround else NightMuted, style = TextStyle(fontSize = 12.sp))
                                }
                            }
                        }
                    }
                    when (val pick = chosen) {
                        is Engine.Cloud -> Text(
                            "The video's sound is sent to ${pick.speech.label} with your key (${pick.speech.model}, ${pick.speech.price}).",
                            color = NightMuted,
                            style = TextStyle(fontSize = 13.sp),
                        )
                        is Engine.OnPhone -> if (clouds.isEmpty()) Text(
                            "Save a Groq or OpenAI key under Library › ⋮ › AI model to have them listen instead: far more accurate, in seconds, for a few cents.",
                            color = NightMuted,
                            style = TextStyle(fontSize = 13.sp),
                        )
                    }
                    Surface(onClick = { onMake(chosen) }, shape = RoundedCornerShape(14.dp), color = NightInk) {
                        Text(
                            when (chosen) {
                                is Engine.Cloud -> "Send to ${chosen.speech.label} and make subtitles"
                                is Engine.OnPhone -> if (fetched(chosen.model)) "Make subtitles" else "Fetch ${chosen.model.megabytes} and make subtitles"
                            },
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

/**
 * Library › ⋮ › *Speech models*: the models fetched for *Make subtitles*, each with the room it takes and *Delete*, to
 * free it (it is fetched again the next time it is chosen). Subtitles made with one stay.
 */
@Composable
fun SpeechModelsDialog(fetched: List<SpeechModel>, onDelete: (SpeechModel) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech models") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fetched.isEmpty()) {
                    Text("No speech model is on this phone. One is fetched the first time you make subtitles with it.")
                } else {
                    Text("Fetched for Make subtitles. Deleting one frees its room; subtitles made with it stay.")
                    fetched.forEach { model ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(model.label, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${model.note} · ${model.megabytes}",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDelete(model) }) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

package com.chaya.app.ui.player

import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.chaya.app.subtitles.Translating
import com.chaya.app.subtitles.VideoTranslations
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant
import com.chaya.app.ui.theme.StageAmber
import java.util.Locale

/** The languages offered to translate into: the phone's own first, then the most spoken, each once. */
fun translateTargets(phone: Locale = Locale.getDefault(), from: String? = null): List<String> =
    (listOf(phone.language) + COMMON_LANGUAGES).distinct().filter { it.isNotEmpty() && it != from?.substringBefore('-') }

private val COMMON_LANGUAGES = listOf(
    "en", "es", "hi", "ar", "fr", "pt", "bn", "ru", "ja", "de", "zh", "ko", "it", "tr", "vi", "id", "ur", "fa", "pl",
    "nl", "uk", "th", "sv", "el", "he", "ro", "cs", "hu", "ta", "te", "mr", "gu", "pa", "ml", "kn", "sw",
)

/**
 * *Translate*: the language to translate into and *Translate*, how it is going (with *Stop*), and every version kept
 * (a language and a model each), one shown under the original or none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateSheet(
    from: String,
    translations: VideoTranslations,
    state: Translating,
    onTranslate: (String) -> Unit,
    onStop: () -> Unit,
    onShow: (Int?) -> Unit,
    /** Deletes version [Int] (a language and model) for good. */
    onDelete: (Int) -> Unit = {},
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val targets = translateTargets(from = from)
    var to by rememberSaveable { mutableStateOf(targets.first()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = NightGround) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("TRANSLATE FROM ${languageName(from).uppercase(Locale.getDefault())} INTO")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { tag ->
                    val chosen = tag == to
                    Surface(
                        onClick = { to = tag },
                        shape = RoundedCornerShape(12.dp),
                        color = if (chosen) NightInk else NightContainer,
                        modifier = Modifier.semantics { selected = chosen },
                    ) {
                        Text(
                            languageName(tag),
                            color = if (chosen) NightGround else NightInk,
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            when (state) {
                is Translating.Running -> {
                    Text(
                        "Translating into ${languageName(state.to)} · ${state.done} of ${state.total} lines",
                        color = NightInk,
                        style = TextStyle(fontSize = 15.sp),
                    )
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.done / state.total.toFloat() },
                        color = StageAmber,
                        trackColor = NightContainer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.model, color = NightMuted, style = TextStyle(fontSize = 13.sp), modifier = Modifier.weight(1f))
                        TextButton(onClick = onStop) { Text("Stop", color = NightInk, style = TextStyle(fontWeight = FontWeight.SemiBold)) }
                    }
                }
                else -> {
                    if (state is Translating.Failed) Text(state.message, color = NightInk, style = TextStyle(fontSize = 14.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(onClick = { onTranslate(to) }, shape = RoundedCornerShape(14.dp), color = NightInk) {
                            Text(
                                "Translate",
                                color = NightGround,
                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                            )
                        }
                        TextButton(onClick = onSetUp) { Text("AI model", color = NightMuted) }
                    }
                    Text(
                        "Your own AI model translates it, with the title and the whole transcript for names and terms. " +
                            "What's translated is kept, so a stopped translation carries on.",
                        color = NightMuted,
                        style = TextStyle(fontSize = 13.sp),
                    )
                }
            }

            if (translations.versions.isNotEmpty()) {
                Heading("SHOWN UNDER THE ORIGINAL")
                VersionRow("Original only", "", chosen = translations.shown == null, onClick = { onShow(null) })
                translations.versions.forEachIndexed { index, version ->
                    VersionRow(
                        title = languageName(version.to),
                        facts = version.model + if (version.whole) "" else " · ${version.done} of ${version.lines.size} lines",
                        chosen = translations.shown == index,
                        onClick = { onShow(index) },
                        onDelete = { onDelete(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRow(title: String, facts: String, chosen: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (chosen) NightContainer else NightGround,
        border = BorderStroke(1.dp, if (chosen) NightInk else NightOutlineVariant),
        modifier = Modifier.fillMaxWidth().semantics { selected = chosen },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(title, color = NightInk, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                if (facts.isNotEmpty()) Text(facts, color = NightMuted, style = TextStyle(fontSize = 13.sp))
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Close, contentDescription = "Delete the $title translation", tint = NightMuted)
                }
            }
        }
    }
}

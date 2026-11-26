package com.chaya.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaya.app.ui.theme.NightContainer
import com.chaya.app.ui.theme.NightGround
import com.chaya.app.ui.theme.NightInk
import com.chaya.app.ui.theme.NightMuted
import com.chaya.app.ui.theme.NightOutlineVariant

/**
 * The player's settings, as YouTube's that suit a saved video: the playback speed, Loop video, the sleep timer and
 * Lock screen. Captions keep their own button beside the picture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSettingsSheet(
    speed: Float,
    onSpeed: (Float) -> Unit,
    loop: Boolean,
    onLoop: (Boolean) -> Unit,
    sleep: SleepTimer,
    nowMs: Long,
    onSleep: (SleepTimer) -> Unit,
    onLock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = NightGround) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingHeading(Icons.Default.Speed, "Playback speed", speedLabel(speed))
            ChoiceRow(
                choices = PLAYBACK_SPEEDS.map { it to speedLabel(it) },
                chosen = speed,
                onChoose = onSpeed,
            )
            SettingRow(Icons.Default.Repeat, "Loop video") {
                Switch(
                    checked = loop,
                    onCheckedChange = onLoop,
                    colors = SwitchDefaults.colors(checkedTrackColor = NightInk, checkedThumbColor = NightGround),
                    modifier = Modifier.semantics { contentDescription = "Loop video" },
                )
            }
            SettingHeading(Icons.Default.Bedtime, "Sleep timer", sleep.label(nowMs))
            ChoiceRow(
                choices = listOf<Pair<SleepTimer, String>>(SleepTimer.Off to "Off") +
                    SleepTimer.MINUTES.map { m -> SleepTimer.after(m, nowMs) to "$m min" } +
                    (SleepTimer.EndOfVideo to "End of video"),
                chosen = sleep,
                // A timer running is the chip of its own length
                isChosen = { choice, chosen ->
                    choice == chosen || (choice is SleepTimer.At && chosen is SleepTimer.At && choice.minutes == chosen.minutes)
                },
                onChoose = onSleep,
            )
            SettingRow(Icons.Default.Lock, "Lock screen", onClick = onLock) {
                Text("Touches do nothing until unlocked", color = NightMuted, style = TextStyle(fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun SettingHeading(icon: ImageVector, title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NightInk, modifier = Modifier.size(22.dp))
        Text(title, color = NightInk, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 16.dp).weight(1f))
        Text(value, color = NightMuted, style = TextStyle(fontSize = 14.sp))
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, onClick: (() -> Unit)? = null, end: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NightInk, modifier = Modifier.size(22.dp))
        Text(title, color = NightInk, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 16.dp).weight(1f))
        end()
    }
}

/** A sideways row of chips, the chosen one in ink, as the Translate sheet's languages. */
@Composable
private fun <T> ChoiceRow(
    choices: List<Pair<T, String>>,
    chosen: T,
    onChoose: (T) -> Unit,
    isChosen: (T, T) -> Boolean = { a, b -> a == b },
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { (value, label) ->
            val on = isChosen(value, chosen)
            Surface(
                onClick = { onChoose(value) },
                shape = RoundedCornerShape(12.dp),
                color = if (on) NightInk else NightContainer,
                border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, NightOutlineVariant),
                modifier = Modifier.semantics { selected = on },
            ) {
                Text(
                    label,
                    color = if (on) NightGround else NightInk,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/**
 * Over its side of the picture while double taps add up: a curved shade and "20 seconds" with the arrows, as
 * YouTube shows it.
 */
@Composable
internal fun SeekStreakBubble(streak: SeekStreak, modifier: Modifier = Modifier) {
    val back = streak.side == SeekSide.BACK
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(160.dp)
            .clip(if (back) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
            .background(Color.White.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (back) Icons.Default.FastRewind else Icons.Default.FastForward,
                contentDescription = null,
                tint = NightInk,
                modifier = Modifier.size(28.dp),
            )
            Text("${streak.seconds} seconds", color = NightInk, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** At the top of the picture while it is held: the video plays at 2×. */
@Composable
internal fun HoldPill(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${speedLabel(HOLD_SPEED)} ", color = NightInk, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        Icon(Icons.Default.FastForward, contentDescription = null, tint = NightInk, modifier = Modifier.size(18.dp))
    }
}

/**
 * Lock screen: over everything, touches do nothing but bring up the unlock button for a moment ([hintShown]); a tap on
 * it unlocks.
 */
@Composable
internal fun LockLayer(hintShown: Boolean, onTouch: () -> Unit, onUnlock: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTouch() } },
        contentAlignment = Alignment.Center,
    ) {
        if (hintShown) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(NightInk)
                        .clickable(onClickLabel = "Unlock") { onUnlock() }
                        .semantics { contentDescription = "Unlock screen" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = NightGround, modifier = Modifier.size(28.dp))
                }
                Text("Tap to unlock", color = NightInk, style = TextStyle(fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

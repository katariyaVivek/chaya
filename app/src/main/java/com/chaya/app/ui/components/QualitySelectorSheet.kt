package com.chaya.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaya.app.streaming.StreamTrack
import com.chaya.app.ui.theme.ChayaMotion
import com.chaya.app.ui.theme.pressScale

sealed interface QualityPickerState {
    data object Loading : QualityPickerState
    data class Ready(
        val tracks: List<StreamTrack>,
        val url: String,
        val mimeType: String?
    ) : QualityPickerState
    data class Error(
        val message: String,
        val url: String = "",
        val mimeType: String? = null
    ) : QualityPickerState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelectorSheet(
    state: QualityPickerState,
    onDismiss: () -> Unit,
    onDownload: (List<StreamTrack>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Select quality",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )

            when (state) {
                is QualityPickerState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(36.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "Analyzing stream…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is QualityPickerState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(18.dp))
                        FilledTonalButton(
                            onClick = { onDownload(emptyList()) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.pressScale()
                        ) {
                            Text("Download anyway")
                        }
                    }
                }

                is QualityPickerState.Ready -> {
                    var selectedTracks by remember(state.tracks) {
                        mutableStateOf(state.tracks.map { it.selected })
                    }

                    LazyColumn {
                        itemsIndexed(state.tracks) { index, track ->
                            TrackRow(
                                index = index,
                                track = track,
                                tracks = state.tracks,
                                selected = selectedTracks[index],
                                onToggle = { checked ->
                                    selectedTracks = selectedTracks.toMutableList().apply {
                                        set(index, checked)
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val chosen = state.tracks.filterIndexed { i, _ -> selectedTracks[i] }
                            onDownload(chosen)
                        },
                        enabled = selectedTracks.any { it },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(48.dp)
                            .pressScale(0.98f)
                    ) {
                        Text(
                            text = "Download ${selectedTracks.count { it }} track" +
                                    if ((selectedTracks.count { it }) == 1) "" else "s",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

private enum class TrackKind { VIDEO, AUDIO }

@Composable
private fun TrackRow(
    index: Int,
    track: StreamTrack,
    tracks: List<StreamTrack>,
    selected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    // C.TRACK_TYPE_VIDEO == 2, C.TRACK_TYPE_AUDIO == 1 (media3 constants)
    val kind = if (track.rendererType == 2) TrackKind.VIDEO else TrackKind.AUDIO
    val previousKind = if (index == 0) null
    else if (tracks[index - 1].rendererType == 2) TrackKind.VIDEO else TrackKind.AUDIO

    Column {
        if (kind != previousKind) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
            ) {
                Icon(
                    imageVector = if (kind == TrackKind.VIDEO) Icons.Default.Movie else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (kind == TrackKind.VIDEO) "Video" else "Audio",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        val rowBg by animateColorAsState(
            targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            else Color.Transparent,
            animationSpec = ChayaMotion.tweenShort(),
            label = "trackRowBg"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(rowBg)
                .pressScale(0.985f)
                .clickable { onToggle(!selected) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Custom checkbox — round, animated check
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = track.label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

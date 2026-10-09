package com.chaya.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableIntStateOf
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
import java.util.Locale
import kotlin.math.roundToLong

sealed interface QualityPickerState {
    data object Loading : QualityPickerState
    data class Ready(
        val tracks: List<StreamTrack>,
        val url: String,
        val mimeType: String?,
        /** Title chosen in the media sheet, carried through to the saved file. */
        val suggestedName: String? = null,
        /** Known playback length; turns each rendition's bitrate into a size estimate. */
        val durationSeconds: Double? = null,
        /** Display title and poster, carried through to the downloads list. */
        val title: String? = null,
        val thumbnailUrl: String? = null,
    ) : QualityPickerState
    data class Error(
        val message: String,
        val url: String = "",
        val mimeType: String? = null,
        val suggestedName: String? = null,
        val title: String? = null,
        val thumbnailUrl: String? = null,
    ) : QualityPickerState
}

/** media3 `C.TRACK_TYPE_VIDEO`; kept local so the picker stays free of player imports. */
private const val TRACK_TYPE_VIDEO = 2

/**
 * Lets a person pick one video quality (best preselected) plus any audio
 * tracks. Video renditions are alternatives, not additions: downloading
 * several of them only multiplies the data for no visible gain.
 */
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
                text = "Choose quality",
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
                    // Tracks arrive best-first, so the first video rendition is the default choice.
                    var selectedVideo by remember(state.tracks) {
                        mutableIntStateOf(state.tracks.indexOfFirst { it.rendererType == TRACK_TYPE_VIDEO })
                    }
                    var selectedAudio by remember(state.tracks) {
                        mutableStateOf(
                            state.tracks.indices
                                .filter { state.tracks[it].rendererType != TRACK_TYPE_VIDEO && state.tracks[it].selected }
                                .toSet()
                        )
                    }

                    // Shrinks to fit so the Download button stays on screen however many renditions there are.
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        itemsIndexed(state.tracks) { index, track ->
                            val isVideo = track.rendererType == TRACK_TYPE_VIDEO
                            TrackRow(
                                index = index,
                                track = track,
                                tracks = state.tracks,
                                selected = if (isVideo) index == selectedVideo else index in selectedAudio,
                                exclusive = isVideo,
                                sizeEstimate = if (isVideo) estimateSize(track.bitrate, state.durationSeconds) else null,
                                onClick = {
                                    if (isVideo) {
                                        selectedVideo = if (selectedVideo == index) -1 else index
                                    } else {
                                        selectedAudio = if (index in selectedAudio) selectedAudio - index
                                        else selectedAudio + index
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    val chosenVideo = state.tracks.getOrNull(selectedVideo)
                    Button(
                        onClick = {
                            onDownload(state.tracks.filterIndexed { i, _ -> i == selectedVideo || i in selectedAudio })
                        },
                        enabled = chosenVideo != null || selectedAudio.isNotEmpty(),
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
                            text = when {
                                chosenVideo != null -> "Download ${chosenVideo.label}"
                                selectedAudio.isNotEmpty() -> "Download audio only"
                                else -> "Choose a quality"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

/** "≈ 490 MB" from bits per second and seconds; null when either is unknown. */
internal fun estimateSize(bitsPerSecond: Int, durationSeconds: Double?): String? {
    if (bitsPerSecond <= 0 || durationSeconds == null || durationSeconds <= 0) return null
    val megabytes = bitsPerSecond * durationSeconds / 8.0 / 1_000_000.0
    return if (megabytes >= 1000) "≈ %.1f GB".format(Locale.ROOT, megabytes / 1000)
    else "≈ ${megabytes.roundToLong().coerceAtLeast(1)} MB"
}

private enum class TrackKind { VIDEO, AUDIO }

@Composable
private fun TrackRow(
    index: Int,
    track: StreamTrack,
    tracks: List<StreamTrack>,
    selected: Boolean,
    exclusive: Boolean,
    sizeEstimate: String?,
    onClick: () -> Unit
) {
    val kind = if (track.rendererType == TRACK_TYPE_VIDEO) TrackKind.VIDEO else TrackKind.AUDIO
    val previousKind = if (index == 0) null
    else if (tracks[index - 1].rendererType == TRACK_TYPE_VIDEO) TrackKind.VIDEO else TrackKind.AUDIO

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
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (exclusive) RadioMark(selected) else CheckMark(selected)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                val facts = listOfNotNull(track.detail.ifBlank { null }, sizeEstimate).joinToString(" · ")
                if (facts.isNotEmpty()) {
                    Text(
                        text = facts,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Single-choice indicator: a ring with a filled center when chosen. */
@Composable
private fun RadioMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(
                width = 2.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** Independent toggle: a round check, matching the rest of the app's selection vocabulary. */
@Composable
private fun CheckMark(selected: Boolean) {
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
}

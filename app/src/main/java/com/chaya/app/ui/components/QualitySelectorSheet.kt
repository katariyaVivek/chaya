package com.chaya.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaya.app.streaming.StreamTrack

sealed interface QualityPickerState {
    data object Loading : QualityPickerState
    data class Ready(
        val tracks: List<StreamTrack>,
        val url: String,
        val mimeType: String?
    ) : QualityPickerState
    data class Error(val message: String) : QualityPickerState
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
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = "Select Quality",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            when (state) {
                is QualityPickerState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Analyzing stream...")
                    }
                }

                is QualityPickerState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            onDownload(emptyList()) // fallback: download all tracks
                        }) {
                            Text("Download anyway")
                        }
                    }
                }

                is QualityPickerState.Ready -> {
                    var selectedTracks by remember(state.tracks) {
                        mutableStateOf(state.tracks.map { it.selected })
                    }

                    LazyColumn {
                        items(state.tracks.size) { index ->
                            val track = state.tracks[index]
                            val isVideo = track.rendererType == 2 // C.TRACK_TYPE_VIDEO
                            val header = if (index == 0 || track.rendererType != state.tracks.getOrNull(index - 1)?.rendererType) {
                                if (isVideo) "Video" else "Audio"
                            } else null

                            if (header != null) {
                                Text(
                                    text = header,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selectedTracks[index],
                                    onCheckedChange = { checked ->
                                        selectedTracks = selectedTracks.toMutableList().apply {
                                            set(index, checked)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = track.label,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val chosen = state.tracks.filterIndexed { i, _ -> selectedTracks[i] }
                            onDownload(chosen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    ) {
                        Text("Download Selected")
                    }
                }
            }
        }
    }
}

package com.chaya.app.streaming

import androidx.media3.common.StreamKey

data class StreamTrack(
    val rendererType: Int,
    /** What a person picks by: "1080p" for video, a language or "Audio" for audio. */
    val label: String,
    val streamKeys: List<StreamKey>,
    val selected: Boolean = true,
    /** Rendition height in pixels; 0 when unknown or for audio. */
    val height: Int = 0,
    /** Peak or average bitrate in bits per second; 0 when unknown. */
    val bitrate: Int = 0,
    /** Secondary facts, e.g. "1920×1080 · 6.2 Mbps". */
    val detail: String = "",
)

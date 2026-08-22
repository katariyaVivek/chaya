package com.chaya.app.streaming

import androidx.media3.common.StreamKey

data class StreamTrack(
    val rendererType: Int,
    val label: String,
    val streamKeys: List<StreamKey>,
    val selected: Boolean = true
)

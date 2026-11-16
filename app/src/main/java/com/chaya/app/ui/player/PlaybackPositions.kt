package com.chaya.app.ui.player

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where each saved video was left ([Watched]), by download id, kept across launches: a few numbers each, so plain
 * SharedPreferences, as "id" -> "at,length,stoppedAt". Kept on the phone only.
 */
class PlaybackPositions(private val prefs: SharedPreferences) {
    private val _watched = MutableStateFlow(read())
    val watched: StateFlow<Map<Long, Watched>> = _watched.asStateFlow()

    fun put(id: Long, watched: Watched) {
        prefs.edit().putString(id.toString(), "${watched.atMs},${watched.lengthMs},${watched.stoppedAt}").apply()
        _watched.update { it + (id to watched) }
    }

    /** A download deleted: where it was left goes with it. */
    fun forget(id: Long) {
        prefs.edit().remove(id.toString()).apply()
        _watched.update { it - id }
    }

    private fun read(): Map<Long, Watched> = prefs.all.mapNotNull { (key, value) ->
        val id = key.toLongOrNull() ?: return@mapNotNull null
        val parts = (value as? String)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return@mapNotNull null
        if (parts.size != 3) return@mapNotNull null
        id to Watched(parts[0], parts[1], parts[2])
    }.toMap()

    companion object {
        fun from(context: Context) =
            PlaybackPositions(context.getSharedPreferences("chaya_playback", Context.MODE_PRIVATE))
    }
}

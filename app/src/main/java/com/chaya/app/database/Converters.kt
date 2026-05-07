package com.chaya.app.database

import androidx.room.TypeConverter
import com.chaya.app.download.DownloadState

class Converters {
    @TypeConverter
    fun fromDownloadState(state: DownloadState): String = state.name

    @TypeConverter
    fun toDownloadState(value: String): DownloadState = DownloadState.valueOf(value)
}

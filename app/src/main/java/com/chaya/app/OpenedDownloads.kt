package com.chaya.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A finished download the person tapped in its "Download complete" notification, held until the app's screens are
 * up to open it. [MainActivity] puts it here from the notification's intent; the navigation takes it, whatever was
 * showing, as [SharedLinks] does for a shared link.
 */
object OpenedDownloads {
    /** The download's id in the intent a "Download complete" notification opens the app with. */
    const val EXTRA_DOWNLOAD_ID = "com.chaya.app.OPEN_DOWNLOAD"

    private val _pending = MutableStateFlow<Long?>(null)
    val pending: StateFlow<Long?> = _pending.asStateFlow()

    fun offer(id: Long) {
        if (id >= 0) _pending.value = id
    }

    /** Marks [id] as opened, unless another was tapped meanwhile. */
    fun consume(id: Long) {
        _pending.compareAndSet(id, null)
    }
}

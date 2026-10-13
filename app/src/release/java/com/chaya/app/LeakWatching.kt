package com.chaya.app

import android.app.Application

/** Release builds carry no LeakCanary; see the debug source set. */
internal object LeakWatching {
    fun install(app: Application) = Unit
}

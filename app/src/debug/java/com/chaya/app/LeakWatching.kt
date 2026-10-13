package com.chaya.app

import android.app.Application
import android.os.Build
import leakcanary.AppWatcher
import leakcanary.RootViewWatcher

/**
 * LeakCanary in debug builds, without its [RootViewWatcher]. That watcher looks up one of LeakCanary's own
 * resources for every dialog shown, through the dialog's context. The dialogs WebView shows for a page (a
 * `<select>` drop-down, a date picker) carry WebView's resources instead of the app's, so the lookup threw
 * `Resources$NotFoundException` and closed the app the moment a page opened a drop-down (7movies' season list).
 * Activities, fragments, view models and services are still watched.
 */
internal object LeakWatching {
    fun install(app: Application) {
        // Unit tests run the app under Robolectric, where LeakCanary has nothing to watch.
        if (AppWatcher.isInstalled || Build.FINGERPRINT == "robolectric") return
        AppWatcher.manualInstall(
            app,
            watchersToInstall = AppWatcher.appDefaultWatchers(app).filterNot { it is RootViewWatcher },
        )
    }
}

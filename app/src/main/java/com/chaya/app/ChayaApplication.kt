package com.chaya.app

import android.app.Application
import android.os.Build
import android.os.StrictMode
import com.chaya.app.adblock.AdBlockSettings
import com.chaya.app.adblock.AdBlocker
import com.chaya.app.adblock.FilterLists
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.diagnostics.CrashReporter
import com.chaya.app.diagnostics.EventLog
import com.chaya.app.download.CompletionNotices
import com.chaya.app.download.DownloadManager
import com.chaya.app.platform.EngineSets
import com.chaya.app.platform.EngineUpdater
import com.chaya.app.platform.PlatformEngine
import com.chaya.app.platform.ProfileLister
import com.chaya.app.platform.WebViewSignIn
import com.chaya.app.ui.theme.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ChayaApplication : Application() {
    lateinit var downloadManager: DownloadManager
        private set

    val database: ChayaDatabase by lazy { ChayaDatabase.getInstance(this) }

    /** On-device diagnostics ring buffer + rotating log (Phase 2.3). */
    val eventLog: EventLog by lazy { EventLog(this) }

    /** Which copy of yt-dlp, yt-dlp-ejs and gallery-dl the engine uses: the app's own, or a newer one from PyPI. */
    val engineSets: EngineSets by lazy {
        EngineSets(File(filesDir, "engine"), bundled = EngineSets.parsePins(BuildConfig.ENGINE_PACKAGES))
    }

    /** yt-dlp and gallery-dl running on the phone; one for the whole app, so lookups take turns. */
    val platformEngine: PlatformEngine by lazy { PlatformEngine(this, engineSets) }

    /** Fetches newer yt-dlp, yt-dlp-ejs and gallery-dl from PyPI, about once a day. */
    val engineUpdater: EngineUpdater by lazy {
        EngineUpdater(engineSets, compile = platformEngine::compileWheel, installedVersion = platformEngine::installedVersion)
    }

    /** Work that outlives any screen: restoring downloads, the engine's daily check. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Light, dark, or the same as the phone. */
    val themeSettings: ThemeSettings by lazy { ThemeSettings.from(this) }

    /** Which finished downloads still need their "complete" notification; one for the whole process. */
    val completionNotices = CompletionNotices(appStartedAt = System.currentTimeMillis())

    /** Blocks ads and trackers in the browser; reads its lists when the browser first shows. */
    val adBlocker: AdBlocker by lazy { AdBlocker(AdBlockSettings.from(this), FilterLists.from(this)) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Fail fast on main-thread disk/network during development; release
            // builds never pay for this. Log-only (no penaltyDeath) so a single
            // slip in a manual walkthrough doesn't kill the debug session.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
        CrashReporter.install(this, eventLog)
        val dao = database.downloadDao()
        downloadManager = DownloadManager(
            this, dao, eventLog = eventLog,
            archiveLister = ProfileLister(platformEngine, WebViewSignIn(this)),
        )
        // Restore persisted downloads from Room
        appScope.launch {
            downloadManager.restore()
        }
    }

    private val engineCheckStarted = AtomicBoolean(false)

    /**
     * Asks PyPI for a newer engine if a day has passed; once per run of the app, a little after the person opens
     * it (not from [onCreate], which also runs for a share, a notification action, or a test).
     */
    fun checkEngineSoon() {
        if (!engineCheckStarted.compareAndSet(false, true)) return
        appScope.launch {
            delay(ENGINE_CHECK_DELAY_MILLIS)
            runCatching { engineUpdater.updateIfDue() }
        }
    }

    private companion object {
        const val ENGINE_CHECK_DELAY_MILLIS = 20_000L
    }
}

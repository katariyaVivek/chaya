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

    /** The browser's history and bookmarks, on the phone only. */
    val browsing: com.chaya.app.history.BrowsingRecord by lazy { com.chaya.app.history.BrowsingRecord.from(this, database) }

    /** Video frames and files taken out of ZIPs for the downloads grid, kept in the cache. */
    val libraryFiles: com.chaya.app.library.LibraryFiles by lazy { com.chaya.app.library.CachedLibraryFiles(this) }

    /** The open tabs on disk, so they come back after Android closes the app. */
    val tabStore: com.chaya.app.browser.TabStore by lazy { com.chaya.app.browser.TabStore(File(filesDir, "tabs")) }

    /** Settings › AI model: the service chosen and its keys, sealed with a key in the Android Keystore. */
    val aiKeys: com.chaya.app.ai.AiKeys by lazy { com.chaya.app.ai.AiKeys.from(this) }
    /** Asks the person's AI model, only when they ask for something that needs it. */
    val aiClient: com.chaya.app.ai.AiClient by lazy { com.chaya.app.ai.AiClient() }

    /** Videos' subtitles translated by the person's AI model, a version for each language and model. */
    val translationStore: com.chaya.app.subtitles.TranslationStore by lazy { com.chaya.app.subtitles.TranslationStore(File(filesDir, "subtitles")) }
    /** Translations under way, one per video; leaving the player does not stop one. */
    val translations: com.chaya.app.subtitles.TranslationJobs by lazy {
        com.chaya.app.subtitles.TranslationJobs(
            scope = appScope,
            store = translationStore,
            subtitles = subtitles::load,
            translator = com.chaya.app.subtitles.Translator(aiClient),
            settings = { aiKeys.settings },
            endpoint = { aiKeys.endpoint(it) },
        )
    }

    /** whisper.cpp's models, fetched once from Hugging Face and checked against their pinned sums. */
    val speechModels: com.chaya.app.speech.SpeechModels by lazy { com.chaya.app.speech.SpeechModels(File(filesDir, "speech")) }
    /** Subtitles made by a service with the person's own key, when they choose it. */
    val cloudListener: com.chaya.app.speech.CloudListener by lazy { com.chaya.app.speech.CloudListener() }
    /** *Make subtitles*: a video listened to on the phone, its lines kept as its subtitles. */
    val speech: com.chaya.app.speech.SpeechJobs by lazy {
        com.chaya.app.speech.SpeechJobs(
            scope = appScope,
            models = speechModels,
            decode = { id, onPiece ->
                val task = downloadManager.downloads.value.firstOrNull { it.id == id }
                val uri = task?.filePath?.let(::File)?.takeIf { it.isFile }?.let(android.net.Uri::fromFile)
                    ?: task?.exportedUri?.let(android.net.Uri::parse)
                var sound: com.chaya.app.speech.Sound16k? = null
                val decoded = uri != null && com.chaya.app.ui.player.ClipMedia.decodePcm(
                    this,
                    uri,
                    onFormat = { rate, channels -> sound = com.chaya.app.speech.Sound16k(rate, channels, onChunk = onPiece) },
                ) { sound?.add(it) }
                sound?.finish()
                decoded
            },
            listen = { file, model, samples, language, listener ->
                com.chaya.app.speech.Whisper.transcribe(file, model.dtw, samples, language, listener)
            },
            keep = { id, language, lines -> subtitles.write(id, "$id.$language.srt", com.chaya.app.subtitles.Subtitles.toSrt(lines)) },
            stopListening = com.chaya.app.speech.Whisper::stop,
            listenParakeet = { folder, samples, heard ->
                com.chaya.app.speech.Parakeet.transcribe(folder, samples, com.chaya.app.speech.Whisper.threads(), heard)
            },
            done = com.chaya.app.speech.Parakeet::release,
            listenCloud = { speech, samples, language ->
                // The service's own address, with the key saved for it under AI model
                val settings = com.chaya.app.ai.AiSettings(service = speech.service)
                when (val endpoint = aiKeys.endpoint(settings)) {
                    is com.chaya.app.ai.AiResult.Failed ->
                        com.chaya.app.speech.CloudHeard.Failed(endpoint.error.message(speech.label))
                    is com.chaya.app.ai.AiResult.Done -> when (val heard = cloudListener.transcribe(endpoint.value, speech, samples, language)) {
                        is com.chaya.app.ai.AiResult.Done -> com.chaya.app.speech.CloudHeard.Said(heard.value)
                        is com.chaya.app.ai.AiResult.Failed -> com.chaya.app.speech.CloudHeard.Failed(heard.error.message(speech.label))
                    }
                }
            },
        )
    }

    /** Each video's clips, kept beside its subtitles. */
    val clipStore: com.chaya.app.subtitles.ClipStore by lazy { com.chaya.app.subtitles.ClipStore(File(filesDir, "subtitles")) }

    /** The moments of each video worth watching, found by the person's AI model, kept beside its subtitles. */
    val highlightStore: com.chaya.app.subtitles.HighlightStore by lazy { com.chaya.app.subtitles.HighlightStore(File(filesDir, "subtitles")) }
    val highlights: com.chaya.app.subtitles.HighlightJobs by lazy {
        com.chaya.app.subtitles.HighlightJobs(
            scope = appScope,
            store = highlightStore,
            subtitles = subtitles::load,
            finder = com.chaya.app.subtitles.HighlightFinder(aiClient),
            settings = { aiKeys.settings },
            endpoint = { aiKeys.endpoint(it) },
        )
    }

    /** Light, dark, or the same as the phone. */
    val themeSettings: ThemeSettings by lazy { ThemeSettings.from(this) }

    /** A site's subtitles kept with the videos saved from it, in the app's own files. */
    val subtitles: com.chaya.app.subtitles.SubtitleStore by lazy { com.chaya.app.subtitles.SubtitleStore(File(filesDir, "subtitles")) }
    /** Every line said in the saved videos, kept in memory for the Library's search; read from the subtitles above. */
    /** Subtitles put right from the player, with Undo while the app runs. */
    val subtitleEdits: com.chaya.app.subtitles.SubtitleEdits by lazy { com.chaya.app.subtitles.SubtitleEdits(subtitles) }
    val lineSearch: com.chaya.app.subtitles.LineSearch by lazy { com.chaya.app.subtitles.LineSearch(File(filesDir, "subtitles")) }

    /** Where each saved video was left, for the player to resume and Home to carry on with. */
    val playbackPositions: com.chaya.app.ui.player.PlaybackPositions by lazy { com.chaya.app.ui.player.PlaybackPositions.from(this) }

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
        LeakWatching.install(this)
        CrashReporter.install(this, eventLog)
        val dao = database.downloadDao()
        downloadManager = DownloadManager(
            this, dao, eventLog = eventLog,
            archiveLister = ProfileLister(platformEngine, WebViewSignIn(this)),
            subtitles = subtitles,
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

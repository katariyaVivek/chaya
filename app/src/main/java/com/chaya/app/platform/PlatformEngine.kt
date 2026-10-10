package com.chaya.app.platform

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds out what a link to YouTube, Instagram, TikTok or X holds, by running yt-dlp (a Python program)
 * inside the app. It only looks things up: the files themselves are fetched by chaya's own downloader.
 *
 * [sets] chooses between the copy of yt-dlp in the app and newer ones [EngineUpdater] fetched; the choice is
 * made once, when Python starts, before anything imports yt-dlp. Without it the app's copy is used.
 */
class PlatformEngine(context: Context, private val sets: EngineSets? = null) : LinkFinder, ProfileListing {
    private val appContext = context.applicationContext

    override suspend fun find(url: String, cookieFile: File?): PlatformMedia = extract(url, cookieFile)

    // One lookup at a time keeps memory and CPU bounded on a phone; Python would serialize them anyway.
    private val oneAtATime = Mutex()

    /**
     * Looks up [url]. [cookieFile] (Netscape format) lets yt-dlp see what the signed-in browser sees.
     * @throws PlatformException with a message fit for the screen when the link can't be used.
     */
    suspend fun extract(url: String, cookieFile: File? = null): PlatformMedia =
        oneAtATime.withLock {
            withContext(Dispatchers.IO) {
                PlatformMedia.parse(runEngine(url, cookieFile))
            }
        }

    // An account's listing can take minutes; it has its own turn so single links are not held up behind it.
    private val oneListing = Mutex()

    /** Lists an account's posts for its ZIP archive (`chaya_engine.profiles`); returns the engine's JSON summary. */
    override suspend fun list(profile: ProfileMatch, cookieFile: File?, out: File, stop: File): String =
        oneListing.withLock {
            withContext(Dispatchers.IO) {
                try {
                    python().getModule("chaya_engine.profiles").callAttr(
                        "list_profile",
                        ProfileMatch.siteOf(profile.platform),
                        profile.username,
                        cacheDir().absolutePath,
                        cookieFile?.absolutePath,
                        out.absolutePath,
                        stop.absolutePath,
                    ).toString().also { sets?.runFinished(engineFailed = false) }
                } catch (e: PyException) {
                    sets?.runFinished(engineFailed = true)
                    ENGINE_FAILED
                } catch (e: LinkageError) {
                    ENGINE_FAILED
                }
            }
        }

    private fun cacheDir() = File(appContext.cacheDir, "yt-dlp").apply { mkdirs() }

    private fun runEngine(url: String, cookieFile: File?): String {
        val cacheDir = cacheDir()
        return try {
            python().getModule("chaya_engine.extract")
                .callAttr("extract", url, cacheDir.absolutePath, cookieFile?.absolutePath)
                .toString()
                .also { sets?.runFinished(engineFailed = false) }
        } catch (e: PyException) {
            // extract() turns every failure of a lookup into an answer; an error that escapes it means the
            // engine itself is broken.
            sets?.runFinished(engineFailed = true)
            throw PlatformException(PlatformException.Kind.ENGINE, e.message)
        } catch (e: LinkageError) {
            // The Python runtime's native part is missing for this phone's processor.
            throw PlatformException(PlatformException.Kind.ENGINE, e.message)
        }
    }

    /** Compiles a wheel fetched by [EngineUpdater] for this phone's Python (`chaya_engine.paths.compile_wheel`). */
    fun compileWheel(wheel: File, out: File) {
        python().getModule("chaya_engine.paths").callAttr("compile_wheel", wheel.absolutePath, out.absolutePath)
    }

    /** The version of a library inside the app, as Python reports it; null when the app has none. */
    fun installedVersion(name: String): String? =
        python().getModule("chaya_engine.paths").callAttr("installed_version", name)?.toString()

    private fun python(): Python {
        synchronized(startLock) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            if (!engineChosen && sets != null) {
                engineChosen = true
                val runtime = ChaquopyRuntime(Python.getInstance())
                // Whatever goes wrong in choosing (a full disk, say), the app's own copy still works.
                runCatching { sets.activate(runtime) }.onFailure { runCatching { runtime.drop() } }
            }
        }
        return Python.getInstance()
    }

    /** [EngineRuntime] through Chaquopy: `chaya_engine.paths` and `chaya_engine.selftest`. */
    class ChaquopyRuntime(private val python: Python) : EngineRuntime {
        override fun use(files: List<File>) {
            python.getModule("chaya_engine.paths").callAttr("use", files.map { it.absolutePath }.toTypedArray())
        }

        override fun drop() {
            python.getModule("chaya_engine.paths").callAttr("drop")
        }

        override fun status(): String = python.getModule("chaya_engine.selftest").callAttr("status").toString()
    }

    private companion object {
        val startLock = Any()

        /** Whether the copy of the engine has been chosen in this process; once, before the first import. */
        var engineChosen = false
        const val ENGINE_FAILED = """{"error": {"kind": "unknown"}}"""
    }
}

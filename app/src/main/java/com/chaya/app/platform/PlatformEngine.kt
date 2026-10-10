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
 */
class PlatformEngine(context: Context) : LinkFinder, ProfileListing {
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
                    ).toString()
                } catch (e: PyException) {
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
        } catch (e: PyException) {
            throw PlatformException(PlatformException.Kind.ENGINE, e.message)
        } catch (e: LinkageError) {
            // The Python runtime's native part is missing for this phone's processor.
            throw PlatformException(PlatformException.Kind.ENGINE, e.message)
        }
    }

    private fun python(): Python {
        synchronized(startLock) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        }
        return Python.getInstance()
    }

    private companion object {
        val startLock = Any()
        const val ENGINE_FAILED = """{"error": {"kind": "unknown"}}"""
    }
}

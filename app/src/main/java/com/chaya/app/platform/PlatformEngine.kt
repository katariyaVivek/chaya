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
class PlatformEngine(context: Context) {
    private val appContext = context.applicationContext

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

    private fun runEngine(url: String, cookieFile: File?): String {
        val cacheDir = File(appContext.cacheDir, "yt-dlp").apply { mkdirs() }
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
    }
}

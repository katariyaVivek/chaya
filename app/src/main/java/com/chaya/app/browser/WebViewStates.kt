package com.chaya.app.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView
import java.io.OutputStream

/** A WebView's back and forward history as bytes for [TabStore], and back. */
internal object WebViewStates {

    /** The WebView's state, or null when it has nothing to save (a tab on the start screen). Main thread. */
    fun save(webView: WebView): ByteArray? {
        val bundle = Bundle()
        webView.saveState(bundle) ?: return null
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.marshall()
        } catch (e: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Gives a new WebView the history [bytes] hold, and reloads its page. False when the bytes cannot be read
     * (written by another version of WebView, say); the caller then just opens the tab's address.
     */
    fun restore(webView: WebView, bytes: ByteArray): Boolean {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val bundle = parcel.readBundle(WebView::class.java.classLoader) ?: return false
            webView.restoreState(bundle) != null
        } catch (e: Exception) {
            false
        } finally {
            parcel.recycle()
        }
    }
}

/** Tab pictures for the tab grid. */
internal object TabPictures {
    /** About a third of the page's size: sharp enough for a card, small on disk. */
    private const val SCALE = 3

    /** The page as shown, drawn at a third of its size; null when the WebView is not laid out. Main thread. */
    fun capture(webView: WebView): Bitmap? {
        val width = webView.width / SCALE
        val height = webView.height / SCALE
        if (width <= 0 || height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.scale(1f / SCALE, 1f / SCALE)
                canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
                webView.draw(canvas)
            }
        }.getOrNull()
    }

    /** Writes [bitmap] as WebP at about 70% quality. */
    fun writeWebp(bitmap: Bitmap, out: OutputStream) {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bitmap.compress(format, 70, out)
    }

    fun writePng(bitmap: Bitmap, out: OutputStream) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
}

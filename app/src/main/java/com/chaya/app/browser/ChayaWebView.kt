package com.chaya.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.WebView

/**
 * The browser's WebView, with what a browser adds to a page: a menu for a long press on a link or a picture
 * ([PressedOn]), and pulling the page down at its top to load it again ([PullGesture]). Its own *Web search* on
 * chosen words opens a new tab in Chaya ([webSearchOf]). Everything goes through [callbacks], so it reaches the screen shown now.
 */
@SuppressLint("ViewConstructor")
internal class ChayaWebView(context: Context, private val callbacks: RetainedWebViewCallbacks) : WebView(context) {
    private val pull = PullGesture(threshold = PULL_DP * resources.displayMetrics.density)
    private var overScrolledAtTop = false

    init {
        setOnLongClickListener { pressed() }
    }

    /** A long press on a link or picture opens its menu; anything else is left to WebView, which selects text. */
    private fun pressed(): Boolean {
        val hit = hitTestResult
        val kind = when (hit.type) {
            HitTestResult.SRC_ANCHOR_TYPE -> HitKind.LINK
            HitTestResult.IMAGE_TYPE -> HitKind.IMAGE
            HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> HitKind.IMAGE_LINK
            else -> return false
        }
        val extra = hit.extra
        if (kind == HitKind.IMAGE) {
            PressedOn.of(kind, extra)?.let(callbacks::onPressed) ?: return false
            return true
        }
        // The link's own address and its words come from requestFocusNodeHref, handed back in a message.
        val reply = Handler(Looper.getMainLooper()) { message ->
            val data = message.data
            PressedOn.of(kind, extra, data.getString("url"), data.getString("title"), data.getString("src"))
                ?.let(callbacks::onPressed)
            true
        }.obtainMessage()
        requestFocusNodeHref(reply)
        return true
    }

    @SuppressLint("ClickableViewAccessibility") // WebView's own handling runs first; this only watches the drag.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pull.down(event.y)
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) pull.cancel() else pull.move(event.y, overScrolledAtTop)
                overScrolledAtTop = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> pull.cancel()
            MotionEvent.ACTION_UP -> if (pull.up()) callbacks.onPullToRefresh()
        }
        callbacks.onPulled(pull.pulled)
        return handled
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0) overScrolledAtTop = true
    }

    companion object {
        /** How far the page is pulled, in dp, before letting go loads it again. */
        const val PULL_DP = 96f
    }
}

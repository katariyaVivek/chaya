package com.chaya.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import org.json.JSONException
import org.json.JSONTokener

/**
 * The browser's WebView, with what a browser adds to a page: a menu for a long press on a link or a picture
 * ([PressedOn]), *Web search* on chosen words on every phone ([Selection]), and pulling the page down at its top to
 * load it again ([PullGesture]). Everything goes through [callbacks], so it reaches the screen shown now.
 *
 * Open only so a device test can watch the menu for chosen words.
 */
@SuppressLint("ViewConstructor")
internal open class ChayaWebView(context: Context, private val callbacks: RetainedWebViewCallbacks) : WebView(context) {
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

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(Selection(callback), type)

    /**
     * WebView's own menu for chosen words. WebView offers *Web search* only when an app on the phone takes web
     * searches; without one, Chaya offers it, so it is always there. Either way it is the same search, which
     * [com.chaya.app.MainActivity] opens in a new Chaya tab.
     */
    private inner class Selection(private val inner: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val shown = inner.onCreateActionMode(mode, menu)
            if (shown && menu.findItem(SEARCH_ID) == null && !context.hasWebSearchApp()) {
                menu.add(Menu.NONE, SEARCH_ID, SEARCH_ORDER, "Web search")
            }
            return shown
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = inner.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != SEARCH_ID) return inner.onActionItemClicked(mode, item)
            evaluateJavascript(SELECTED_TEXT_JS) { result ->
                // Started as WebView starts its own, so both reach the same new tab
                selectedText(result)?.let { words -> runCatching { context.startActivity(webSearchIntent(words)) } }
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    companion object {
        /** How far the page is pulled, in dp, before letting go loads it again. */
        const val PULL_DP = 96f
        private const val SEARCH_ID = 0x43_68_61 // "Cha"
        /** Where WebView puts its own Web search, after Copy, Share and Select all. */
        private const val SEARCH_ORDER = 7
        private const val SELECTED_TEXT_JS = "(function(){return String(window.getSelection());})()"

        /** The words `evaluateJavascript` hands back (a JSON string), trimmed and kept short; null for none. */
        fun selectedText(result: String?): String? {
            val text = try {
                JSONTokener(result ?: return null).nextValue() as? String
            } catch (_: JSONException) {
                null
            }
            return text?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_SEARCH)?.takeIf { it.isNotEmpty() }
        }

        private const val MAX_SEARCH = 300
    }
}

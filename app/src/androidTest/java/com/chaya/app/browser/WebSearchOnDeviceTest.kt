package com.chaya.app.browser

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.MainActivity
import com.chaya.app.history.PageToOpen
import com.chaya.app.history.PagesToOpen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView's own *Web search*, on a word chosen with a long press, opens a new tab in Chaya. WebView starts the
 * search through its context, Chaya's activity; before the fix Android handed it to Google's app.
 */
@RunWith(AndroidJUnit4::class)
class WebSearchOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun forgetThePage() {
        PagesToOpen.pending.value?.let(PagesToOpen::consume)
    }

    @Test
    fun webSearchOnAChosenWordOpensANewTabInChaya() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val loaded = CountDownLatch(1)
            lateinit var web: Watching
            lateinit var activity: MainActivity
            scenario.onActivity {
                activity = it
                web = Watching(it).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loaded.countDown()
                        }
                    }
                    loadData(PAGE, "text/html", null)
                }
                it.setContentView(web)
            }
            assertTrue("the page did not load", loaded.await(30, TimeUnit.SECONDS))
            assertTrue("the activity never had focus", waitFor { activity.hasWindowFocus() })
            // Let the page draw once, so the press lands on the word rather than on nothing yet.
            SystemClock.sleep(1_000)

            val at = IntArray(2)
            instrumentation.runOnMainSync { web.getLocationOnScreen(at) }
            val x = at[0] + web.width / 2f
            val y = at[1] + web.height / 2f
            // A finger held on the word, as a hand does it: WebView chooses words only for a finger's long press
            val down = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(finger(down, MotionEvent.ACTION_DOWN, x, y))
            repeat(6) {
                SystemClock.sleep(200)
                instrumentation.sendPointerSync(finger(down, MotionEvent.ACTION_MOVE, x, y))
            }
            instrumentation.sendPointerSync(finger(down, MotionEvent.ACTION_UP, x, y))

            // Two different failures, told apart: the press chose nothing, or WebView's menu had no web search in it
            assertTrue("no menu for chosen words ever opened: the long press chose nothing", waitFor { web.menuOpened() })
            if (!waitFor { web.webSearch() != null }) {
                var titles = emptyList<String>()
                instrumentation.runOnMainSync { titles = web.titles() }
                throw AssertionError("WebView's menu for the chosen word had no web search, only $titles")
            }
            instrumentation.runOnMainSync {
                val item = web.webSearch()
                assertNotNull(item)
                web.click(item!!)
            }

            assertTrue("the search never reached Chaya", waitFor { PagesToOpen.pending.value != null })
            assertEquals(PageToOpen("Chaya", newTab = true), PagesToOpen.pending.value)
        }
    }

    /** A WebView that keeps hold of its menu for chosen words, so the test can tap *Web search* in it. */
    private class Watching(context: Context) : WebView(context) {
        private var mode: ActionMode? = null
        private var menu: Menu? = null
        private var callback: ActionMode.Callback? = null

        fun menuOpened(): Boolean = menu != null

        private fun items(): List<MenuItem> = menu?.let { shown -> (0 until shown.size()).map(shown::getItem) }.orEmpty()

        fun titles(): List<String> = items().map { it.title?.toString().orEmpty() }

        /** WebView's own *Web search* ("Web search" in English), while its menu for chosen words is showing. */
        fun webSearch(): MenuItem? = items().firstOrNull { it.title?.toString()?.contains("search", ignoreCase = true) == true }

        fun click(item: MenuItem) {
            callback?.onActionItemClicked(mode ?: return, item)
        }

        override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
            val inner = callback
            return super.startActionMode(object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
                    inner.onCreateActionMode(mode, menu).also { shown ->
                        if (shown) {
                            this@Watching.mode = mode
                            this@Watching.menu = menu
                            this@Watching.callback = inner
                        }
                    }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = inner.onPrepareActionMode(mode, menu)
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = inner.onActionItemClicked(mode, item)
                override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)
                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect)
                    else super.onGetContentRect(mode, view, outRect)
                }
            }, type)
        }
    }

    /** One touch of a finger on the touchscreen, not a pointer of no known kind. */
    private fun finger(down: Long, action: Int, x: Float, y: Float): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            down, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            var met = false
            instrumentation.runOnMainSync { met = condition() }
            if (met) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private companion object {
        /** One word filling the middle of the page, so a long press there chooses it. */
        const val PAGE = "<html><body style=\"margin:0;height:100vh;display:flex;align-items:center;" +
            "justify-content:center;font:64px sans-serif\">Chaya</body></html>"
    }
}

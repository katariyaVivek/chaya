package com.chaya.app.browser

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A page's drop-down (`<select>`) opens without closing the app. WebView shows its list in a dialog that carries
 * WebView's own resources; LeakCanary's root view watcher, in debug builds, looked one of its resources up through
 * that dialog and threw, which ended the process (7movies' season list). Before the fix this test died with it.
 */
@RunWith(AndroidJUnit4::class)
class SelectPopupOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aPagesDropDownOpensAndTheAppCarriesOn() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val loaded = CountDownLatch(1)
            lateinit var web: WebView
            lateinit var activity: ComponentActivity
            scenario.onActivity {
                activity = it
                web = WebView(it).apply {
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
            // Let the page draw once, so the tap lands on the drop-down rather than on nothing yet.
            SystemClock.sleep(1_000)

            val at = IntArray(2)
            instrumentation.runOnMainSync { web.getLocationOnScreen(at) }
            val x = at[0] + web.width / 2f
            val y = at[1] + web.height / 2f
            val down = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
            instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))

            // The list takes focus from the page when it opens; the process is still here to see that.
            assertTrue("the drop-down's list never opened", waitFor { !activity.hasWindowFocus() })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertTrue("the list did not close", waitFor { activity.hasWindowFocus() })
        }
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
        /** One drop-down filling the page, so a tap in the middle opens it. */
        const val PAGE = "<html><body style=\"margin:0\">" +
            "<select style=\"position:fixed;left:0;top:0;width:100%;height:100%;font-size:40px\">" +
            "<option>Season 1</option><option>Season 2</option><option>Season 3</option><option>Season 4</option>" +
            "</select></body></html>"
    }
}

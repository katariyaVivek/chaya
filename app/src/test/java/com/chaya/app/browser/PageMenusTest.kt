package com.chaya.app.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A long press's menu on a link or picture, the words chosen to search for, and pulling the page to reload it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageMenusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a link offers what Chrome does, its words copied only when it has some`() {
        val link = PressedOn.of(HitKind.LINK, "https://app.roteo.ai/", href = "https://app.roteo.ai/", title = " Launch app ")
        assertEquals(PressedOn.Link("https://app.roteo.ai/", "Launch app"), link)
        assertEquals(
            listOf(
                PageAction.OPEN_IN_NEW_TAB, PageAction.COPY_LINK, PageAction.COPY_LINK_TEXT,
                PageAction.DOWNLOAD_LINK, PageAction.SHARE_LINK,
            ),
            link!!.actions,
        )
        val bare = PressedOn.of(HitKind.LINK, "https://a.example/x", title = "")!!
        assertFalse(PageAction.COPY_LINK_TEXT in bare.actions)
    }

    @Test
    fun `a picture inside a link offers both, and its own address is the picture's`() {
        val pressed = PressedOn.of(
            HitKind.IMAGE_LINK, extra = "https://cdn.example/p.jpg", href = "https://site.example/post", src = "https://cdn.example/p.jpg",
        )
        assertEquals(PressedOn.Image("https://cdn.example/p.jpg", link = "https://site.example/post"), pressed)
        assertEquals("https://site.example/post", pressed!!.shown)
        assertTrue(PageAction.OPEN_IN_NEW_TAB in pressed.actions && PageAction.DOWNLOAD_IMAGE in pressed.actions)
        val alone = PressedOn.of(HitKind.IMAGE, "https://cdn.example/p.jpg")!!
        assertFalse("a picture alone has no link to open", PageAction.OPEN_IN_NEW_TAB in alone.actions)
    }

    @Test
    fun `only web addresses get a menu`() {
        assertNull(PressedOn.of(HitKind.LINK, "javascript:void(0)"))
        assertNull(PressedOn.of(HitKind.IMAGE, "data:image/png;base64,AAAA"))
        assertNull(PressedOn.of(HitKind.OTHER, "https://a.example/"))
        assertEquals(
            "a data: picture inside a real link is still the link",
            PressedOn.Link("https://a.example/", ""),
            PressedOn.of(HitKind.IMAGE_LINK, "data:image/png;base64,AAAA", href = "https://a.example/"),
        )
    }

    @Test
    fun `the words chosen are read from WebView's answer, tidied and kept short`() {
        assertEquals("Connect once", ChayaWebView.selectedText("\"Connect\\n  once\""))
        assertNull(ChayaWebView.selectedText("\"   \""))
        assertNull(ChayaWebView.selectedText("null"))
        assertNull(ChayaWebView.selectedText(null))
        assertEquals(300, ChayaWebView.selectedText("\"" + "a".repeat(500) + "\"")!!.length)
    }

    @Test
    fun `pulling counts only from when the page is at its top, and letting go past the end reloads`() {
        val pull = PullGesture(threshold = 100f)
        pull.down(0f)
        pull.move(80f, overScrolledAtTop = false) // the page scrolled up; not a pull yet
        assertEquals(0f, pull.pulled)
        pull.move(100f, overScrolledAtTop = true) // reached its top: the pull starts from 80
        assertEquals(0.2f, pull.pulled, 0.001f)
        pull.move(200f, overScrolledAtTop = false)
        assertEquals(1f, pull.pulled)
        assertTrue(pull.up())
        assertEquals(0f, pull.pulled)
    }

    @Test
    fun `a short pull, a pull pushed back up, or a second finger does not reload`() {
        val pull = PullGesture(threshold = 100f)
        pull.down(0f)
        pull.move(10f, overScrolledAtTop = true)
        pull.move(50f, overScrolledAtTop = false)
        assertFalse(pull.up())

        pull.down(0f)
        pull.move(10f, overScrolledAtTop = true)
        pull.move(150f, overScrolledAtTop = false)
        pull.move(-20f, overScrolledAtTop = false)
        assertEquals(0f, pull.pulled)
        assertFalse(pull.up())

        pull.down(0f)
        pull.move(10f, overScrolledAtTop = true)
        pull.move(150f, overScrolledAtTop = false)
        pull.cancel()
        assertFalse(pull.up())
    }

    @Test
    fun `a drag up at the top is not a pull`() {
        val pull = PullGesture(threshold = 100f)
        pull.down(100f)
        pull.move(50f, overScrolledAtTop = true)
        assertEquals(0f, pull.pulled)
    }

    @Test
    fun `the menu shows the link and reports the row tapped`() {
        val tapped = mutableListOf<PageAction>()
        composeRule.setContent {
            PageMenu(PressedOn.Link("https://app.roteo.ai/", "Launch app"), onAction = { tapped += it }, onDismiss = {})
        }
        composeRule.onNodeWithText("https://app.roteo.ai/").assertExists()
        composeRule.onNodeWithText("Copy link text").performClick()
        assertEquals(listOf(PageAction.COPY_LINK_TEXT), tapped)
    }
}

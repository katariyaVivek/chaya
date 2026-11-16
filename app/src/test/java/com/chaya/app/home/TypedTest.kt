package com.chaya.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home's "+": a link Chaya can look up is looked up; an address or words open in the browser. */
class TypedTest {

    @Test
    fun `a link to a video, post or pin is looked up, as a shared link is`() {
        for (link in listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.instagram.com/p/abc123/",
            "https://x.com/someone/status/123",
            "https://pin.it/abcdef",
        )) {
            assertEquals(link, Typed.LookUp(link), Typed.of(link))
        }
    }

    @Test
    fun `a sentence with such a link in it is looked up whole, as the browser finds the link in it`() {
        val shared = "Look at this https://www.youtube.com/watch?v=dQw4w9WgXcQ."
        assertEquals(Typed.LookUp(shared), Typed.of(shared))
    }

    @Test
    fun `an address or words to search for open in the browser`() {
        assertEquals(Typed.Open("example.com"), Typed.of("  example.com "))
        assertEquals(Typed.Open("https://en.wikipedia.org/wiki/Cat"), Typed.of("https://en.wikipedia.org/wiki/Cat"))
        assertEquals(Typed.Open("how to make bread"), Typed.of("how to make bread"))
    }

    @Test
    fun `nothing typed does nothing`() {
        assertNull(Typed.of("   "))
    }
}

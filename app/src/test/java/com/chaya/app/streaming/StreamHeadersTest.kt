package com.chaya.app.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A stream is asked for the way the page's player asks for it, Origin included. */
class StreamHeadersTest {

    @Test
    fun `a player's stream carries its frame as Referer and the frame's origin as Origin`() {
        assertEquals(
            mapOf(
                "User-Agent" to "UA",
                "Cookie" to "a=1",
                "Referer" to "https://player.example/e/1?x=2",
                "Origin" to "https://player.example",
            ),
            StreamHeaders.of("UA", "a=1", "https://player.example/e/1?x=2"),
        )
    }

    @Test
    fun `nothing is sent that is not known`() {
        assertEquals(emptyMap<String, String>(), StreamHeaders.of(null, "", null))
    }

    @Test
    fun `an origin keeps a port that is not the default and only http(s) has one`() {
        assertEquals("http://127.0.0.1:8080", StreamHeaders.originOf("http://127.0.0.1:8080/page"))
        assertEquals("https://Player.example".lowercase(), StreamHeaders.originOf("https://Player.example:443/x"))
        assertNull(StreamHeaders.originOf("about:blank"))
        assertNull(StreamHeaders.originOf("not a url"))
    }
}

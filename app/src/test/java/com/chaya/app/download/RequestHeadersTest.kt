package com.chaya.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestHeadersTest {

    @Test
    fun `headers survive being stored as text`() {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) Chrome/148.0",
            "Referer" to "https://www.tiktok.com/@creator/video/1",
            "Cookie" to "tt_chain_token=abc==; other=1",
        )

        assertEquals(headers, HeaderCodec.decode(HeaderCodec.encode(headers)))
    }

    @Test
    fun `a value may contain colons, and spaces around it are not kept`() {
        val decoded = HeaderCodec.decode("Referer:   https://example.com:8443/a:b  ")

        assertEquals(mapOf("Referer" to "https://example.com:8443/a:b"), decoded)
    }

    @Test
    fun `nothing worth storing gives null, and null or blank text gives no headers`() {
        assertNull(HeaderCodec.encode(emptyMap()))
        assertEquals(emptyMap<String, String>(), HeaderCodec.decode(null))
        assertEquals(emptyMap<String, String>(), HeaderCodec.decode(""))
        assertEquals(emptyMap<String, String>(), HeaderCodec.decode("   \n  "))
    }

    @Test
    fun `a header that cannot survive the format is dropped instead of corrupting its neighbours`() {
        val encoded = HeaderCodec.encode(
            mapOf(
                "Good" to "yes",
                "Bad:Name" to "x",
                "Line\nBreak" to "x",
                "Value" to "first\nsecond",
                "" to "empty name",
            ),
        )

        assertEquals("Good: yes", encoded)
    }

    @Test
    fun `lines that are not headers are ignored when reading`() {
        val decoded = HeaderCodec.decode("no colon here\n: no name\nA: 1\n  \nB: 2")

        assertEquals(mapOf("A" to "1", "B" to "2"), decoded)
    }

    @Test
    fun `the user agent, cookie and referer are found whatever the capitalisation`() {
        val headers = RequestHeaders.from(
            mapOf("user-agent" to "UA", "COOKIE" to "a=b", "referer" to "https://site.example/", "Accept" to "*/*"),
        )

        assertEquals(RequestHeaders("UA", "a=b", "https://site.example/"), headers)
    }

    @Test
    fun `the page stands in for a missing referer, but never replaces one that is given`() {
        assertEquals("https://page.example/", RequestHeaders.from(emptyMap(), "https://page.example/").referer)
        assertEquals(
            "https://given.example/",
            RequestHeaders.from(mapOf("Referer" to "https://given.example/"), "https://page.example/").referer,
        )
    }

    @Test
    fun `blank values count as missing`() {
        val headers = RequestHeaders.from(mapOf("User-Agent" to "  ", "Cookie" to ""))

        assertEquals(RequestHeaders(null, null, null), headers)
    }
}

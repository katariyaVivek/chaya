package com.chaya.app.adblock

import com.chaya.app.adblock.engine.RequestType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Telling what a WebView request is for, which decides the rules that apply to it. */
class RequestTypesTest {

    @Test
    fun `the fetch destination header says it best`() {
        assertEquals(RequestType.SCRIPT, RequestTypes.of("https://x.example/a", mapOf("sec-fetch-dest" to "script")))
        assertEquals(RequestType.SUBDOCUMENT, RequestTypes.of("https://x.example/a.js", mapOf("Sec-Fetch-Dest" to "iframe")))
        assertEquals(RequestType.XHR, RequestTypes.of("https://x.example/api", mapOf("Sec-Fetch-Dest" to "empty")))
    }

    @Test
    fun `then the accept header`() {
        assertEquals(RequestType.STYLESHEET, RequestTypes.of("https://x.example/s", mapOf("Accept" to "text/css,*/*;q=0.1")))
        assertEquals(RequestType.IMAGE, RequestTypes.of("https://x.example/i", mapOf("Accept" to "image/avif,image/webp,*/*")))
        assertEquals(RequestType.SUBDOCUMENT, RequestTypes.of("https://x.example/f", mapOf("Accept" to "text/html,application/xhtml+xml")))
    }

    @Test
    fun `then the file extension, ignoring the query`() {
        assertEquals(RequestType.SCRIPT, RequestTypes.of("https://x.example/gpt.js?v=2", mapOf("Accept" to "*/*")))
        assertEquals(RequestType.IMAGE, RequestTypes.of("https://x.example/p/a.PNG#x", emptyMap()))
        assertEquals(RequestType.MEDIA, RequestTypes.of("https://x.example/v/1.m3u8", emptyMap()))
        assertEquals(RequestType.FONT, RequestTypes.of("https://x.example/f.woff2", emptyMap()))
    }

    @Test
    fun `and unknown when nothing tells`() {
        assertEquals(RequestType.UNKNOWN, RequestTypes.of("https://x.example/collect?v=1", mapOf("Accept" to "*/*")))
        assertEquals(RequestType.UNKNOWN, RequestTypes.of("https://x.example.com", emptyMap()))
    }
}

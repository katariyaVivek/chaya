package com.chaya.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reads the JSON the Python side returns, the way it is written by `chaya_engine/extract.py`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformMediaParseTest {

    @Test
    fun `a video with its formats is read in full`() {
        val media = PlatformMedia.parse(FULL)

        assertEquals("dQw4w9WgXcQ", media.id)
        assertEquals("Never Gonna Give You Up", media.title)
        assertEquals("Rick Astley", media.author)
        assertEquals(213.0, media.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", media.thumbnailUrl)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", media.pageUrl)
        assertEquals("Youtube", media.extractor)
        assertFalse(media.isLive)
        assertEquals(listOf("18", "140"), media.formats.map { it.id })
    }

    @Test
    fun `a format keeps its details`() {
        val video = PlatformMedia.parse(FULL).formats.first()

        assertEquals("https://rr1.googlevideo.com/videoplayback?id=18", video.url)
        assertEquals("mp4", video.ext)
        assertEquals("https", video.protocol)
        assertEquals(640, video.width)
        assertEquals(360, video.height)
        assertEquals(25.0, video.fps)
        assertEquals("avc1.42001E", video.videoCodec)
        assertEquals("mp4a.40.2", video.audioCodec)
        assertEquals(392.5, video.bitrateKbps)
        assertEquals(96.0, video.audioBitrateKbps)
        assertEquals("360p", video.note)
        assertEquals(
            mapOf("User-Agent" to "Mozilla/5.0", "Accept-Language" to "en-us,en;q=0.5"),
            video.headers,
        )
    }

    @Test
    fun `an approximate size is used when the exact one is unknown, and the exact one wins`() {
        val formats = PlatformMedia.parse(FULL).formats

        assertEquals(10_485_760L, formats[0].sizeBytes) // filesize null, filesize_approx given
        assertEquals(3_452_816L, formats[1].sizeBytes) // filesize given
    }

    @Test
    fun `missing and null fields stay missing instead of becoming the text null`() {
        val media = PlatformMedia.parse(
            """
            {"media": {"id": null, "title": null, "uploader": null, "channel": null, "duration": null,
              "thumbnail": null, "webpage_url": null, "extractor_key": null, "is_live": null,
              "formats": [{"format_id": null, "url": "https://cdn.example/clip.mp4", "ext": null,
                "vcodec": null, "acodec": null, "language": null,
                "http_headers": {"Referer": null, "User-Agent": "UA"}}]}}
            """.trimIndent(),
        )
        val format = media.formats.single()

        assertNull(media.id)
        assertEquals("Untitled", media.title)
        assertNull(media.author)
        assertNull(media.durationSeconds)
        assertNull(media.thumbnailUrl)
        assertNull(media.pageUrl)
        assertNull(media.extractor)
        assertFalse(media.isLive)
        assertNull(format.ext)
        assertNull(format.videoCodec)
        assertNull(format.audioCodec)
        assertNull(format.language)
        assertEquals(mapOf("User-Agent" to "UA"), format.headers)
        assertTrue(format.id.isNotBlank() && format.id != "null")
    }

    @Test
    fun `the channel stands in when there is no uploader`() {
        val media = PlatformMedia.parse("""{"media": {"title": "T", "channel": "The Channel", "formats": []}}""")

        assertEquals("The Channel", media.author)
    }

    @Test
    fun `a zero or negative duration means unknown`() {
        assertNull(PlatformMedia.parse("""{"media": {"title": "T", "duration": 0, "formats": []}}""").durationSeconds)
        assertNull(PlatformMedia.parse("""{"media": {"title": "T", "duration": -1, "formats": []}}""").durationSeconds)
    }

    @Test
    fun `formats without an address are skipped`() {
        val media = PlatformMedia.parse(
            """{"media": {"title": "T", "formats": [{"format_id": "sb0"}, {"format_id": "ok", "url": "https://cdn.example/a"}]}}""",
        )

        assertEquals(listOf("ok"), media.formats.map { it.id })
    }

    @Test
    fun `a missing format list is an empty one`() {
        assertTrue(PlatformMedia.parse("""{"media": {"title": "T"}}""").formats.isEmpty())
    }

    @Test
    fun `a live stream is flagged`() {
        assertTrue(PlatformMedia.parse("""{"media": {"title": "T", "is_live": true, "formats": []}}""").isLive)
    }

    @Test
    fun `an engine error becomes an exception with its kind and detail`() {
        val failure = assertThrows(PlatformException::class.java) {
            PlatformMedia.parse("""{"error": {"kind": "bot_check", "detail": "Sign in to confirm you're not a bot"}}""")
        }

        assertEquals(PlatformException.Kind.BOT_CHECK, failure.kind)
        assertEquals("Sign in to confirm you're not a bot", failure.detail)
        assertEquals(PlatformException.Kind.BOT_CHECK.message, failure.message)
    }

    @Test
    fun `each engine error kind maps to its own kind, and unfamiliar ones to unknown`() {
        val expected = mapOf(
            "bot_check" to PlatformException.Kind.BOT_CHECK,
            "private" to PlatformException.Kind.PRIVATE,
            "needs_login" to PlatformException.Kind.NEEDS_LOGIN,
            "geo" to PlatformException.Kind.GEO,
            "unsupported" to PlatformException.Kind.UNSUPPORTED,
            "unavailable" to PlatformException.Kind.UNAVAILABLE,
            "network" to PlatformException.Kind.NETWORK,
            "unknown" to PlatformException.Kind.UNKNOWN,
            "something_new" to PlatformException.Kind.UNKNOWN,
        )

        expected.forEach { (name, kind) ->
            val failure = assertThrows(PlatformException::class.java) {
                PlatformMedia.parse("""{"error": {"kind": "$name", "detail": "d"}}""")
            }
            assertEquals("kind for $name", kind, failure.kind)
        }
    }

    @Test
    fun `an error without a kind is unknown`() {
        val failure = assertThrows(PlatformException::class.java) {
            PlatformMedia.parse("""{"error": {"detail": "d"}}""")
        }

        assertEquals(PlatformException.Kind.UNKNOWN, failure.kind)
    }

    @Test
    fun `output that is not what the engine promises is an engine problem`() {
        listOf("not json at all", "", "{}", """{"media": null}""", "[]").forEach { output ->
            val failure = assertThrows("for '$output'", PlatformException::class.java) { PlatformMedia.parse(output) }
            assertEquals("kind for '$output'", PlatformException.Kind.ENGINE, failure.kind)
        }
    }

    @Test
    fun `every kind has a message a person can read`() {
        PlatformException.Kind.entries.forEach { kind ->
            assertTrue("message for $kind", kind.message.length > 10)
            assertFalse("message for $kind mentions internals", kind.message.contains("yt-dlp", ignoreCase = true))
        }
    }

    private companion object {
        val FULL = """
            {"media": {
              "id": "dQw4w9WgXcQ",
              "title": "Never Gonna Give You Up",
              "uploader": "Rick Astley",
              "channel": "Rick Astley Official",
              "duration": 213,
              "webpage_url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
              "extractor_key": "Youtube",
              "is_live": false,
              "live_status": "not_live",
              "thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
              "formats": [
                {"format_id": "18", "url": "https://rr1.googlevideo.com/videoplayback?id=18", "ext": "mp4",
                 "protocol": "https", "width": 640, "height": 360, "fps": 25,
                 "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "tbr": 392.5, "abr": 96,
                 "filesize": null, "filesize_approx": 10485760, "format_note": "360p", "language": null,
                 "http_headers": {"User-Agent": "Mozilla/5.0", "Accept-Language": "en-us,en;q=0.5"}},
                {"format_id": "140", "url": "https://rr1.googlevideo.com/videoplayback?id=140", "ext": "m4a",
                 "protocol": "https", "width": null, "height": null, "fps": null,
                 "vcodec": "none", "acodec": "mp4a.40.2", "tbr": 129.5, "abr": 129.5,
                 "filesize": 3452816, "filesize_approx": null, "format_note": "medium", "language": "en",
                 "http_headers": {}}
              ]
            }}
        """.trimIndent()
    }
}

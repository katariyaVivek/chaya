package com.chaya.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadRequestsTest {

    private val video = PlatformFormatFixtures.format(
        id = "133", ext = "mp4", width = 320, height = 240, vcodec = "avc1.4D400C", acodec = "none",
        size = 433_081, headers = mapOf("User-Agent" to "UA/video", "Cookie" to "a=b"),
    )
    private val sound = PlatformFormatFixtures.format(
        id = "140", ext = "m4a", vcodec = "none", acodec = "mp4a.40.2",
        size = 309_288, headers = mapOf("User-Agent" to "UA/sound"),
    )

    @Test
    fun `a joined choice becomes a two-file request saved as MP4`() {
        val choice = choice("240p", quality = 240, file = video, sound = sound)

        val request = choice.toDownloadRequest(media(title = "Me at the zoo"))

        assertEquals(video.url, request.url)
        assertEquals(video.headers, request.headers)
        assertEquals(sound.url, request.audioUrl)
        assertEquals(sound.headers, request.audioHeaders)
        assertEquals("Me at the zoo (240p).mp4", request.fileName)
        assertEquals("video/mp4", request.mimeType)
        assertEquals("Me at the zoo", request.title)
        assertEquals(240, request.qualityHeight)
        assertEquals(433_081L + 309_288L, request.expectedBytes)
        assertEquals("https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg", request.thumbnailUrl)
    }

    @Test
    fun `a ready-made file keeps its own container and has no second file`() {
        val webm = PlatformFormatFixtures.format(
            id = "w", ext = "webm", width = 1920, height = 1080, vcodec = "vp9", acodec = "opus",
        )

        val request = choice("1080p", quality = 1080, file = webm).toDownloadRequest(media(title = "Clip"))

        assertEquals("Clip (1080p).webm", request.fileName)
        assertEquals("video/webm", request.mimeType)
        assertNull(request.audioUrl)
        assertEquals(emptyMap<String, String>(), request.audioHeaders)
    }

    @Test
    fun `a high frame rate shows in the name`() {
        val request = choice("1080p60", quality = 1080, file = video).toDownloadRequest(media(title = "Race"))

        assertEquals("Race (1080p60).mp4", request.fileName)
    }

    @Test
    fun `sound alone is named audio and has no picture quality`() {
        val request = PlatformChoice("Audio only", "", null, sound, null, isAudioOnly = true)
            .toDownloadRequest(media(title = "Song"))

        assertEquals("Song (audio).m4a", request.fileName)
        assertEquals("audio/mp4", request.mimeType)
        assertNull(request.qualityHeight)
        assertNull(request.audioUrl)
    }

    @Test
    fun `Opus sound in WebM stays WebM`() {
        val opus = PlatformFormatFixtures.format(id = "251", ext = "webm", vcodec = "none", acodec = "opus")

        val request = PlatformChoice("Audio only", "", null, opus, null, isAudioOnly = true)
            .toDownloadRequest(media(title = "Song"))

        assertEquals("Song (audio).webm", request.fileName)
        assertEquals("audio/webm", request.mimeType)
    }

    @Test
    fun `a file with no named container is saved as MP4 or M4A`() {
        val noExt = PlatformFormatFixtures.format(id = "x", ext = null, vcodec = "avc1", acodec = "mp4a")
        val noExtSound = PlatformFormatFixtures.format(id = "y", ext = null, vcodec = "none", acodec = "mp4a")

        assertEquals("T (720p).mp4", choice("720p", 720, noExt).toDownloadRequest(media("T")).fileName)
        assertEquals(
            "T (audio).m4a",
            PlatformChoice("Audio only", "", null, noExtSound, null, true).toDownloadRequest(media("T")).fileName,
        )
    }

    @Test
    fun `a missing title is called Video`() {
        val request = choice("720p", 720, video).toDownloadRequest(media(title = "   "))

        assertEquals("Video", request.title)
        assertEquals("Video (720p).mp4", request.fileName)
    }

    @Test
    fun `the page the person was on wins over the one the engine reports`() {
        val media = media(title = "T", pageUrl = "https://www.youtube.com/watch?v=engine")

        assertEquals(
            "https://www.youtube.com/watch?v=shared",
            choice("720p", 720, video).toDownloadRequest(media, pageUrl = "https://www.youtube.com/watch?v=shared").pageUrl,
        )
        assertEquals(
            "https://www.youtube.com/watch?v=engine",
            choice("720p", 720, video).toDownloadRequest(media).pageUrl,
        )
    }

    @Test
    fun `a streaming manifest is not a file download`() {
        val manifest = PlatformFormatFixtures.format(
            id = "hls", ext = "mp4", protocol = "m3u8_native", url = "https://cdn.example/index.m3u8",
            width = 1280, height = 720, vcodec = "avc1", acodec = "mp4a",
        )

        assertThrows(IllegalArgumentException::class.java) {
            choice("720p", 720, manifest).toDownloadRequest(media(title = "T"))
        }
    }

    private fun choice(label: String, quality: Int?, file: PlatformFormat, sound: PlatformFormat? = null) =
        PlatformChoice(label, "", quality, file, sound, isAudioOnly = false)

    private fun media(title: String, pageUrl: String? = null) = PlatformMedia(
        id = "jNQXAC9IVRw",
        title = title,
        author = null,
        durationSeconds = null,
        thumbnailUrl = "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg",
        pageUrl = pageUrl,
        extractor = "Youtube",
        isLive = false,
        formats = emptyList(),
    )
}

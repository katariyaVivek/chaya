package com.chaya.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `a video with no picture size is named after its title alone`() {
        val bare = PlatformFormatFixtures.format(id = "direct", ext = "mp4")

        val request = PlatformChoice("Video", "", null, bare, null, isAudioOnly = false)
            .toDownloadRequest(media(title = "Clip"))

        assertEquals("Clip.mp4", request.fileName)
        assertNull(request.qualityHeight)
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

    @Test
    fun `an item of a post is named by its place in the post and keeps its own type`() {
        val picture = PostItem("https://cdn.example/two.webp", isVideo = false, ext = "webp", width = 1080, height = 1080,
            headers = mapOf("Referer" to "https://www.instagram.com/"))

        val request = picture.toDownloadRequest(media(title = "Sunset at the lake"), number = 2, count = 5)

        assertEquals("Sunset at the lake (2 of 5).webp", request.fileName)
        assertEquals("Sunset at the lake (2 of 5)", request.title)
        assertEquals("image/webp", request.mimeType)
        assertEquals(picture.url, request.url)
        assertEquals(picture.headers, request.headers)
        assertEquals("a picture is its own poster", picture.url, request.thumbnailUrl)
        assertNull(request.audioUrl)
    }

    @Test
    fun `the items of one post share a group key, so the library shows them as one tile`() {
        val one = PostItem("https://cdn.example/1.jpg", isVideo = false, ext = "jpg", width = null, height = null)
        val two = PostItem("https://cdn.example/2.jpg", isVideo = false, ext = "jpg", width = null, height = null)
        val post = media(title = "Sunset", pageUrl = "https://www.instagram.com/p/abc/")

        val first = one.toDownloadRequest(post, number = 1, count = 2)
        val second = two.toDownloadRequest(post, number = 2, count = 2)
        val other = one.toDownloadRequest(media(title = "Another", pageUrl = "https://www.instagram.com/p/xyz/"), 1, 2)

        assertEquals(first.groupKey, second.groupKey)
        assertEquals(groupKeyOf("https://www.instagram.com/p/abc/", "Sunset"), first.groupKey)
        assertNotEquals(first.groupKey, other.groupKey)
        assertNull("a post of one item has nothing to group", one.toDownloadRequest(post, number = 1, count = 1).groupKey)
    }

    @Test
    fun `a post of one item is saved under the post's name alone`() {
        val video = PostItem("https://cdn.example/clip.mp4", isVideo = true, ext = "mp4", width = null, height = null)

        val request = video.toDownloadRequest(media(title = "Clip"), number = 1, count = 1)

        assertEquals("Clip.mp4", request.fileName)
        assertEquals("video/mp4", request.mimeType)
        assertEquals("a video uses the post's poster", "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg", request.thumbnailUrl)
    }
}

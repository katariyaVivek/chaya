package com.chaya.app.streaming

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.StreamKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The quality picker's choice must download that rendition only. Shaped like the stream the emulator
 * walkthrough uses (Big Buck Bunny on the hls.js demo): the first variant is 720p and the sound is muxed
 * into every variant. Picking 184p used to download 720p as well, because the audio entry's hand-built
 * stream key meant "variant 0".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestHelperStreamKeysTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(Dispatchers.Main)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/master.m3u8" -> playlist(
                    "#EXTM3U\n#EXT-X-VERSION:3\n" +
                        "#EXT-X-STREAM-INF:BANDWIDTH=2149280,RESOLUTION=1280x720,CODECS=\"avc1.64001f,mp4a.40.2\"\n" +
                        "hi/index.m3u8\n" +
                        "#EXT-X-STREAM-INF:BANDWIDTH=246440,RESOLUTION=320x184,CODECS=\"avc1.42000d,mp4a.40.2\"\n" +
                        "lo/index.m3u8\n",
                )
                "/hi/index.m3u8", "/lo/index.m3u8" -> playlist(
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXTINF:2.0,\nseg0.ts\n#EXT-X-ENDLIST\n",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private fun playlist(text: String) =
        MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(text)

    private fun parse(): List<StreamTrack> {
        var result: Result<List<StreamTrack>>? = null
        scope.launch {
            result = ManifestHelper.parse(context, server.url("/master.m3u8").toString(), "application/x-mpegURL", null)
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (result == null && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return checkNotNull(result) { "the manifest was never analysed" }.getOrThrow()
    }

    @Test
    fun choosing184pAndItsSoundDownloadsOnlyThe184pVariant() {
        val tracks = parse()
        val video = tracks.filter { it.rendererType == C.TRACK_TYPE_VIDEO }
        val audio = tracks.filter { it.rendererType == C.TRACK_TYPE_AUDIO }
        assertEquals(listOf("720p", "184p"), video.map { it.label })
        assertTrue("the muxed sound should be offered as an audio choice", audio.isNotEmpty())
        val low = video.single { it.label == "184p" }

        // Variant 1 of the multivariant playlist (group 0), and nothing else.
        assertEquals(listOf(StreamKey(0, 0, 1)), ManifestHelper.streamKeysFor(listOf(low) + audio))
    }

    @Test
    fun eachRenditionCarriesTheKeyOfItsOwnVariant() {
        val video = parse().filter { it.rendererType == C.TRACK_TYPE_VIDEO }

        assertEquals(listOf(StreamKey(0, 0, 0)), video.single { it.label == "720p" }.streamKeys)
        assertEquals(listOf(StreamKey(0, 0, 1)), video.single { it.label == "184p" }.streamKeys)
    }
}

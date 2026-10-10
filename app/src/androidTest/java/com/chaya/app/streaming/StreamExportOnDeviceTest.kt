package com.chaya.app.streaming

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.util.Log
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.TestMedia
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A real HLS stream (fragmented MP4, H.264 and AAC, made on the device) is downloaded into Media3's cache by
 * [StreamDownloader], then saved as an MP4 by [Media3StreamExporter], which Robolectric cannot run. The file must
 * hold the same picture and sound: the same number of samples, the same length.
 */
@RunWith(AndroidJUnit4::class)
class StreamExportOnDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var server: MockWebServer
    private var streams: StreamDownloader? = null
    private val taskId = 4242L

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "stream-export-test").apply { deleteRecursively(); mkdirs() }
        File(context.cacheDir, "media3-cache").deleteRecursively()
        context.deleteDatabase(StandaloneDatabaseProvider.DATABASE_NAME)
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        streams?.let { s -> InstrumentationRegistry.getInstrumentation().runOnMainSync { runCatching { s.release() } } }
        runCatching { server.shutdown() }
        dir.deleteRecursively()
    }

    /** The picture and sound TestMedia makes, as one fragmented MP4 split into an init segment and a media segment. */
    private fun makeStream(): Pair<ByteArray, ByteArray> {
        val picture = File(dir, "picture.mp4").also { TestMedia.pictureOnly(it) }
        val sound = File(dir, "sound.m4a").also { TestMedia.soundOnly(it) }
        val fragmented = File(dir, "stream.mp4")
        val sources = listOf(picture, sound).map { file -> MediaExtractor().apply { setDataSource(file.absolutePath); selectTrack(0) } }
        FileOutputStream(fragmented).use { out ->
            val muxer = FragmentedMp4Muxer.Builder(out).build()
            val tracks = sources.map { muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(it.getTrackFormat(0))) }
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            // In time order across both tracks, as a real stream interleaves them.
            while (true) {
                val next = sources.indices.filter { sources[it].sampleTime >= 0 }.minByOrNull { sources[it].sampleTime } ?: break
                val source = sources[next]
                buffer.clear()
                val size = source.readSampleData(buffer, 0)
                val info = MediaCodec.BufferInfo().apply {
                    set(0, size, source.sampleTime, if ((source.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                }
                buffer.position(0)
                buffer.limit(size)
                // The muxer keeps each buffer until it writes the fragment, so every sample gets its own.
                val sample = ByteBuffer.allocateDirect(size).put(buffer).apply { flip() }
                muxer.writeSampleData(tracks[next], sample, info)
                source.advance()
            }
            muxer.close()
        }
        sources.forEach { it.release() }
        val bytes = fragmented.readBytes()
        val firstFragment = topLevelBoxes(bytes).first { it.second == "moof" }.first
        return bytes.copyOfRange(0, firstFragment) to bytes.copyOfRange(firstFragment, bytes.size)
    }

    /** (offset, type) of each top-level MP4 box. */
    private fun topLevelBoxes(bytes: ByteArray): List<Pair<Int, String>> {
        val boxes = ArrayList<Pair<Int, String>>()
        var at = 0
        while (at + 8 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            boxes += at to type
            val length = if (size == 1L) ByteBuffer.wrap(bytes, at + 8, 8).long else size
            if (length < 8) break
            at += length.toInt()
        }
        return boxes
    }

    @Test
    fun aDownloadedHlsStreamIsSavedAsAnMp4WithTheSamePictureAndSound() {
        val (init, segment) = makeStream()
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:3
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:${TestMedia.AUDIO_SECONDS}.0,
            segment0.m4s
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/show/media.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(playlist)
                "/show/init.mp4" -> MockResponse().setBody(Buffer().write(init))
                "/show/segment0.m4s" -> MockResponse().setBody(Buffer().write(segment))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val done = CountDownLatch(1)
        var failure: Exception? = null
        val listener = object : StreamDownloader.Listener {
            override fun onStreamProgress(taskId: Long, downloadedBytes: Long, totalBytes: Long?) = Unit
            override fun onStreamCompleted(taskId: Long) = done.countDown()
            override fun onStreamFailed(taskId: Long, reason: Int, cause: Exception?) {
                failure = cause ?: Exception("reason $reason")
                done.countDown()
            }
            override fun onStreamPaused(taskId: Long) = Unit
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            streams = StreamDownloader(context, listener).also {
                it.startStreamDownload(taskId, server.url("/show/media.m3u8").toString(), "application/x-mpegURL", null, null)
            }
        }
        assertTrue("the stream did not download in time", done.await(60, TimeUnit.SECONDS))
        assertNull("the stream failed: $failure", failure)

        val exporter = Media3StreamExporter(context, streams!!)
        assertTrue((exporter.cachedBytes(taskId) ?: 0) > 0)
        val output = File(dir, "saved.mp4")
        val progress = mutableListOf<Float>()
        val started = System.nanoTime()
        val result = runBlocking { exporter.export(taskId, output) { progress += it } }
        Log.i("ChayaDevice", "stream saved as MP4 in ${(System.nanoTime() - started) / 1_000_000} ms: $result")

        val source = TestMedia.inspect(File(dir, "stream.mp4"))
        val saved = TestMedia.inspect(output)
        assertTrue(saved.mimeTypes.toString(), saved.trackOf("video/avc") >= 0)
        assertTrue(saved.mimeTypes.toString(), saved.trackOf("audio/") >= 0)
        assertEquals(TestMedia.VIDEO_FRAMES, saved.times("video/").size)
        assertEquals(source.times("audio/").size, saved.times("audio/").size)
        val lasting = saved.durationMs ?: 0
        assertTrue("lasts $lasting ms", lasting in 1_500..2_600)
        assertTrue(result.hasVideo)
        assertTrue("copied as it was, not re-encoded", !result.reencoded)
        assertEquals(1f, progress.last())

        // Once the file is kept, the cached copy goes.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { exporter.removeCached(taskId) }
        assertTrue(waitFor { exporter.cachedBytes(taskId) == null })
    }

    @Test
    fun aStreamThatIsNotAllDownloadedIsNotSaved() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            streams = StreamDownloader(context, object : StreamDownloader.Listener {
                override fun onStreamProgress(taskId: Long, downloadedBytes: Long, totalBytes: Long?) = Unit
                override fun onStreamCompleted(taskId: Long) = Unit
                override fun onStreamFailed(taskId: Long, reason: Int, cause: Exception?) = Unit
                override fun onStreamPaused(taskId: Long) = Unit
            })
        }
        val exporter = Media3StreamExporter(context, streams!!)

        assertNull(exporter.cachedBytes(taskId))
        assertThrows(StreamExportException::class.java) {
            runBlocking { exporter.export(taskId, File(dir, "never.mp4")) {} }
        }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return condition()
    }
}

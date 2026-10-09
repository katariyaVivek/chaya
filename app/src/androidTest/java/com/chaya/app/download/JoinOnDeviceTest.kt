package com.chaya.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.TestMedia
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The real join, with the device's own media framework: nothing here can run on a plain JVM. The files are
 * made on the spot with the device's encoders, so they are genuine H.264 and AAC.
 */
@RunWith(AndroidJUnit4::class)
class JoinOnDeviceTest {

    private lateinit var dir: File

    @Before
    fun makeWorkFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "join-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun removeWorkFolder() {
        dir.deleteRecursively()
    }

    @Test
    fun joiningAPictureAndASoundKeepsEverySampleOfBoth() {
        val picture = File(dir, "picture.mp4").also { TestMedia.pictureOnly(it) }
        val sound = File(dir, "sound.m4a").also { TestMedia.soundOnly(it) }
        val joined = File(dir, "joined.mp4")
        val pictureIn = TestMedia.inspect(picture)
        val soundIn = TestMedia.inspect(sound)
        // The generator itself must have worked, or the comparison below proves nothing.
        assertTrue("picture frames: ${pictureIn.times("video/").size}", pictureIn.times("video/").size in 25..TestMedia.VIDEO_FRAMES)
        assertTrue("sound frames: ${soundIn.times("audio/").size}", soundIn.times("audio/").size > 60)

        Mp4Merger().merge(picture, sound, joined)

        val result = TestMedia.inspect(joined)
        assertEquals("tracks: ${result.mimeTypes}", 2, result.mimeTypes.size)
        assertTrue(result.mimeTypes.contains("video/avc"))
        assertTrue(result.mimeTypes.any { it.startsWith("audio/mp4a") })
        assertEquals(pictureIn.times("video/").size, result.times("video/").size)
        assertEquals(soundIn.times("audio/").size, result.times("audio/").size)
    }

    @Test
    fun timestampsSurviveTheJoinAndTheDurationIsRight() {
        val picture = File(dir, "picture.mp4").also { TestMedia.pictureOnly(it) }
        val sound = File(dir, "sound.m4a").also { TestMedia.soundOnly(it) }
        val joined = File(dir, "joined.mp4")

        Mp4Merger().merge(picture, sound, joined)

        val before = TestMedia.inspect(picture).times("video/")
        val audioBefore = TestMedia.inspect(sound).times("audio/")
        val after = TestMedia.inspect(joined)
        listOf("video/" to before, "audio/" to audioBefore).forEach { (kind, original) ->
            val times = after.times(kind)
            assertEquals("$kind in order", times.sorted(), times)
            assertTrue("$kind first: ${original.first()} vs ${times.first()}", abs(original.first() - times.first()) <= 1_000)
            assertTrue("$kind last: ${original.last()} vs ${times.last()}", abs(original.last() - times.last()) <= 1_000)
        }
        val duration = after.durationMs ?: error("The joined file has no duration")
        assertTrue("duration $duration ms", duration in 1_700..2_400)
    }

    @Test
    fun aSidewaysPictureStaysTheRightWayUp() {
        val picture = File(dir, "picture.mp4").also { TestMedia.pictureOnly(it, rotation = 90) }
        val sound = File(dir, "sound.m4a").also { TestMedia.soundOnly(it) }
        val joined = File(dir, "joined.mp4")
        assertEquals(90, TestMedia.inspect(picture).rotation)

        Mp4Merger().merge(picture, sound, joined)

        assertEquals(90, TestMedia.inspect(joined).rotation)
    }

    @Test
    fun aFileWithoutAPictureCannotBeJoinedAndLeavesNothingBehind() {
        val sound = File(dir, "sound.m4a").also { TestMedia.soundOnly(it) }
        val joined = File(dir, "joined.mp4")

        assertThrows(CombineException::class.java) { Mp4Merger().merge(sound, sound, joined) }

        assertFalse(joined.exists())
    }

    @Test
    fun aFileThatIsNotMediaCannotBeJoinedAndLeavesNothingBehind() {
        val junk = File(dir, "junk.mp4").apply { writeText("this is not a video") }
        val sound = File(dir, "sound.m4a").also { TestMedia.soundOnly(it) }
        val joined = File(dir, "joined.mp4")

        assertThrows(CombineException::class.java) { Mp4Merger().merge(junk, sound, joined) }

        assertFalse(joined.exists())
    }
}

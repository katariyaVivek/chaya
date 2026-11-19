package com.chaya.app.subtitles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `core::clips`' cases, ported; the timeline's snap from Windows' clips.js; the sound's loudness; clips kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun new(name: String, start: Long, end: Long) = NewClip(name, start, end)
    private fun times(clips: List<Clip>) = clips.map { listOf(it.id.toLong(), it.start, it.end) to it.name }

    @Test
    fun `clips are kept in order, named, each with its own number`() {
        var (clips, ids) = Clips.add(emptyList(), listOf(new("The turn", 60_000, 75_000), new("  ", 10_000, 20_000)), 300_000)
        assertEquals(listOf(1, 2), ids)
        assertEquals(listOf(listOf(2L, 10_000L, 20_000L) to "Clip 2", listOf(1L, 60_000L, 75_000L) to "The turn"), times(clips))
        // A number is never given again, even after its clip goes.
        clips = Clips.remove(clips, 1)!!
        assertNull(Clips.remove(clips, 1))
        val (again, newIds) = Clips.add(clips, listOf(new("", 0, 5_000)), null)
        assertEquals(listOf(3), newIds)
        assertEquals(listOf(3L, 0L, 5_000L) to "Clip 3", times(again)[0])
    }

    @Test
    fun `a range is put right, backwards, too short or past the end`() {
        val (clips, _) = Clips.add(
            emptyList(),
            listOf(new("Backwards", 9_000, 4_000), new("Too short", 30_000, 30_100), new("Past the end", 95_000, 120_000)),
            100_000,
        )
        assertEquals(
            listOf(listOf(1L, 4_000L, 9_000L) to "Backwards", listOf(2L, 30_000L, 30_500L) to "Too short", listOf(3L, 95_000L, 100_000L) to "Past the end"),
            times(clips),
        )
        // At the very end, it is made long enough by starting earlier.
        val (more, ids) = Clips.add(clips, listOf(new("Last moment", 99_900, 100_000)), 100_000)
        assertEquals(listOf(4), ids)
        assertEquals(99_500L to 100_000L, more.last().let { it.start to it.end })
        // A video shorter than a clip can be has none.
        assertEquals(listOf<Int?>(null), Clips.add(more, listOf(new("", 0, 300)), 300).second)
    }

    @Test
    fun `overlapping clips are both kept, the same one only once`() {
        val (clips, _) = Clips.add(emptyList(), listOf(new("Whole answer", 10_000, 40_000), new("Its best part", 20_000, 30_000)), null)
        val (again, ids) = Clips.add(clips, listOf(new("Whole answer, again", 10_000, 40_000), new("", 40_000, 10_000)), null)
        assertEquals(listOf(1, 1), ids)
        assertEquals(clips, again)
    }

    @Test
    fun `a clip changed is put right and moved into its place`() {
        val (clips, _) = Clips.add(emptyList(), listOf(new("First", 10_000, 20_000), new("Second", 30_000, 40_000)), null)
        val moved = Clips.change(clips, Clip(1, "  First,   later ", 52_000, 45_000), 50_000)!!
        assertEquals(listOf(listOf(2L, 30_000L, 40_000L) to "Second", listOf(1L, 45_000L, 50_000L) to "First, later"), times(moved))
        // A name left empty keeps the one it had.
        assertEquals("Second", Clips.change(moved, Clip(2, "", 30_000, 41_000), null)!![0].name)
        assertNull(Clips.change(moved, Clip(9, "None", 0, 1_000), null))
    }

    @Test
    fun `a long name is cut, and there is a most`() {
        val (clips, _) = Clips.add(emptyList(), listOf(new("word ".repeat(40), 0, 1_000)), null)
        assertEquals(Clips.LONGEST_NAME, clips[0].name.length)
        val (many, ids) = Clips.add(clips, (1L..Clips.MOST + 4L).map { new("", it * 1_000, it * 1_000 + 800) }, null)
        assertEquals(Clips.MOST, many.size)
        assertEquals(5, ids.count { it == null })
    }

    @Test
    fun `an edge snaps to the nearest line within a second and a half, else to a tenth of a second`() {
        val lines = listOf(SubtitleLine(10_000, 12_000, "One."), SubtitleLine(13_000, 15_000, "Two."))
        val starts = Clips.edges(lines, listOf(Chapter(30_000, 40_000, "Later")), start = true)
        assertEquals(listOf(10_000L, 13_000L, 30_000L), starts)
        assertEquals(13_000L, Clips.snap(starts, 12_100))
        assertEquals(29_000L + 1_000, Clips.snap(starts, 28_800))
        assertEquals(21_200L, Clips.snap(starts, 21_234))
        val clip = Clip(1, "A", 10_000, 15_000)
        assertEquals("never shorter than the shortest", 14_500L, Clips.moveEdge(clip, true, 14_900, emptyList(), null).start)
        assertEquals("never past the end", 16_000L, Clips.moveEdge(clip, false, 40_000, emptyList(), 16_000).end)
        assertEquals(5_000L..20_000L, Clips.window(clip, 60_000))
    }

    @Test
    fun `lines chosen either way round, and a clip named from its first line`() {
        assertEquals(2..5, Clips.chosen(10, 5, 2))
        assertEquals(8..9, Clips.chosen(10, 8, 40))
        assertNull(Clips.chosen(0, 0, 0))
        assertEquals("So here is the thing about rivers", Clips.nameFrom("So here is the thing about rivers."))
        assertEquals("A sentence that goes on for much longer", Clips.nameFrom("A sentence that goes on for much longer than forty letters."))
    }

    @Test
    fun `the sound's loudness is the loudest moment of each fiftieth of a second, channels together`() {
        val meter = PeakMeter(sampleRate = 100, channels = 2, perSecond = 50)
        // Two frames a peak: (100, -200), (50, 0) | (32767, 0), (0, 0) | (-16384, 0)
        listOf<Short>(100, -200, 50, 0, 32_767, 0, 0, 0, -16_384, 0).forEach(meter::add)
        assertArrayEquals(byteArrayOf(2, 255.toByte(), 128.toByte()), meter.peaks())
        assertEquals(0, PeakMeter.byte(0))
        assertEquals(255, PeakMeter.byte(32_768))
    }

    @Test
    fun `clips are kept beside the subtitles and go with them`() {
        val dir = folder.newFolder()
        val store = ClipStore(dir)
        assertTrue(store.load(12).isEmpty())
        val clips = listOf(Clip(1, "A", 0, 1_000), Clip(3, "B", 5_000, 9_000))
        store.keep(12, clips)
        assertEquals(clips, store.load(12))
        SubtitleStore(dir).forget(12)
        assertTrue(store.load(12).isEmpty())
    }
}

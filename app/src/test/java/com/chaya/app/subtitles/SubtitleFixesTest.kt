package com.chaya.app.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subtitles put right as Chaya for Windows puts them right (`desktop/core/src/fix.rs`'s tests). */
class SubtitleFixesTest {

    private fun times(lines: List<SubtitleLine>) = lines.map { it.start to it.end }

    private val walk = listOf(
        SubtitleLine(1_000, 4_000, "One"),
        SubtitleLine(5_000, 8_000, "Two"),
        SubtitleLine(20_000, 24_000, "Three"),
        SubtitleLine(60_000, 64_000, "Four"),
    )

    @Test
    fun `a shift moves every line and leaves out what would end before the video`() {
        assertEquals(
            listOf(2_500L to 5_500L, 6_500L to 9_500L, 21_500L to 25_500L, 61_500L to 65_500L),
            times(Retime.shift(1_500).apply(walk)),
        )
        // "One" ended at 4 s: now before the start, it is gone; "Two" starts at 0.5 s
        assertEquals(listOf(500L to 3_500L, 15_500L to 19_500L, 55_500L to 59_500L), times(Retime.shift(-4_500).apply(walk)))
        assertEquals("cut at the start, not gone", 0L to 2_500L, times(Retime.shift(-1_500).apply(walk))[0])
    }

    @Test
    fun `two lines put right stretch the rest between and beyond them`() {
        // "Three" was put right at 20 s; "Four" is said at 61.5 s, not 60: everything after 20 s stretches by 1.5/40
        val retime = Retime.of(Retiming.Through(fixed = 20_000, from = 60_000, to = 61_500))
        assertEquals(1.0375, retime.scale, 1e-9)
        val lines = retime.apply(walk)
        assertEquals("the first line put right stays", 20_000L, lines[2].start)
        assertEquals("the second goes where it was said", 61_500L, lines[3].start)
        assertEquals(65_650L, lines[3].end)
        assertEquals("before the first, the stretch carries on backwards", 288L, lines[0].start)
    }

    @Test
    fun `a stretch too close or too wild is a shift`() {
        // 2 s apart: a stretch from so close would magnify any slip, so the second line is shifted to
        assertEquals(Retime.shift(1_000), Retime.of(Retiming.Through(fixed = 20_000, from = 22_000, to = 23_000)))
        // Twice as fast is not a frame rate: a shift
        assertEquals(Retime.shift(30_000), Retime.of(Retiming.Through(fixed = 0, from = 30_000, to = 60_000)))
        assertEquals(Retime.shift(-250), Retime.of(Retiming.Shift(-250)))
    }

    @Test
    fun `a line is corrected or taken out`() {
        val fixed = SubtitleFixes.withText(walk, 1, "  Two, said right \r\n\r\n second half  ")!!
        assertEquals("Two, said right\nsecond half", fixed[1].text)
        assertEquals("its time stays", times(walk), times(fixed))
        assertEquals(listOf("Two", "Three", "Four"), SubtitleFixes.withText(walk, 0, "   ")!!.map { it.text })
        assertNull(SubtitleFixes.withText(walk, 4, "No such line"))
        assertEquals(SubtitleFixes.LONGEST_TEXT, SubtitleFixes.withText(walk, 2, "x".repeat(900))!![2].text.length)
    }

    @Test
    fun `what a shift did is said in seconds`() {
        assertEquals("Lines moved 1.5 s later.", SubtitleFixes.moved(1_500))
        assertEquals("Lines moved 0.1 s earlier.", SubtitleFixes.moved(-100))
        assertEquals("Lines moved 2 s later.", SubtitleFixes.moved(2_000))
        assertTrue(SubtitleFixes.moved(0).startsWith("Lines left"))
    }
}

package com.chaya.app.downloads

import androidx.compose.ui.text.font.FontWeight
import com.chaya.app.subtitles.LineSearch
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the lines found are summed up and marked. */
class SaidInVideosTest {

    private fun hit(id: Long, text: String, marks: List<IntRange> = emptyList()) =
        LineSearch.Hit(id, "Video $id", "en", 0, 1000, text, marks)

    @Test
    fun `how often the words are said, and in how many videos`() {
        assertEquals("Said once in 1 video", saidSummary(LineSearch.Results(listOf(hit(1, "a")), total = 1)))
        assertEquals(
            "Said 12 times in 2 videos",
            saidSummary(LineSearch.Results(listOf(hit(1, "a"), hit(1, "b"), hit(2, "c")), total = 12)),
        )
    }

    @Test
    fun `what matched is bold and the rest is as it was`() {
        val marked = markedText(hit(1, "Le café de l'École", listOf(3 until 7, 13 until 18)))

        assertEquals("Le café de l'École", marked.text)
        assertEquals(
            listOf("café", "École"),
            marked.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { marked.text.substring(it.start, it.end) },
        )
        // Marks that run past the text are cut to it
        assertEquals("ab", markedText(hit(1, "ab", listOf(1 until 9))).text)
    }
}

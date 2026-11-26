package com.chaya.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** Files a download lost, given back only where the size leaves no doubt whose they are. */
class LooseFilesTest {

    @Test
    fun `a file of the one size a download lost goes back to it`() {
        assertEquals(
            mapOf(1L to "/d/clip.mp4", 2L to "/d/talk.mp4"),
            LooseFiles.match(
                missing = mapOf(1L to 100L, 2L to 250L),
                loose = mapOf("/d/clip.mp4" to 100L, "/d/talk.mp4" to 250L, "/d/notes.txt" to 7L),
            ),
        )
    }

    @Test
    fun `two downloads, or two files, of the same size are left as they are`() {
        assertEquals(emptyMap<Long, String>(), LooseFiles.match(mapOf(1L to 100L, 2L to 100L), mapOf("/d/a.mp4" to 100L)))
        assertEquals(emptyMap<Long, String>(), LooseFiles.match(mapOf(1L to 100L), mapOf("/d/a.mp4" to 100L, "/d/b.mp4" to 100L)))
    }

    @Test
    fun `nothing is matched by an empty size`() {
        assertEquals(emptyMap<Long, String>(), LooseFiles.match(mapOf(1L to 0L), mapOf("/d/empty" to 0L)))
    }
}

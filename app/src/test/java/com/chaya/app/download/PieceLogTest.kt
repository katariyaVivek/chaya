package com.chaya.app.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The record of which pieces of a file are whole: what a resume trusts, and what it throws away. */
class PieceLogTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("chaya-piecelog").toFile()
        file = File(dir, "video.mp4.part").apply { writeBytes(ByteArray(100)) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `whole pieces come back after the app is closed`() {
        val log = PieceLog.load(file, 40, fromBytes = 0)
        log.begin(100)
        log.markDone(0)
        log.markDone(2)

        val again = PieceLog.load(file, 40, fromBytes = 100)

        assertEquals(setOf(0, 2), again.done)
        assertEquals(100L, again.total)
        assertEquals(1, again.firstMissing())
        // Pieces are 40, 40 and the last 20 bytes.
        assertEquals(60L, again.doneBytes())
        assertEquals(60L, PieceLog.bytesOnDisk(file))
        assertFalse(again.isComplete())
    }

    @Test
    fun `without a record, the whole pieces of a gapless start count`() {
        val log = PieceLog.load(file, 40, fromBytes = 90)

        assertEquals(setOf(0, 1), log.done)
        assertEquals(2, log.firstMissing())
        log.begin(100)
        assertEquals(setOf(0, 1), log.done)
        assertEquals(80L, PieceLog.bytesOnDisk(file))
        assertEquals(0L, PieceLog.bytesOnDisk(File(dir, "not there")))
    }

    @Test
    fun `a different size means a different file and nothing counts`() {
        PieceLog.load(file, 40, 0).apply {
            begin(100)
            markDone(0)
        }

        val log = PieceLog.load(file, 40, 0)
        log.begin(120)

        assertEquals(emptySet<Int>(), log.done)
    }

    @Test
    fun `a record of another piece size, an unreadable one, or one with no file is not trusted`() {
        PieceLog.load(file, 40, 0).apply {
            begin(100)
            markDone(1)
        }
        assertEquals(emptySet<Int>(), PieceLog.load(file, 50, fromBytes = 0).done)

        PieceLog.fileFor(file).writeText("nonsense\n1,x\n")
        assertEquals(emptySet<Int>(), PieceLog.load(file, 40, fromBytes = 0).done)
        assertEquals(100L, PieceLog.bytesOnDisk(file))

        PieceLog.load(file, 40, 0).apply {
            begin(100)
            markDone(1)
        }
        file.delete()
        assertEquals(emptySet<Int>(), PieceLog.load(file, 40, fromBytes = 0).done)
        assertEquals(0L, PieceLog.bytesOnDisk(file))
    }

    @Test
    fun `every piece whole is complete, and the last is fetched again if asked where to start`() {
        val log = PieceLog.load(file, 40, 0)
        log.begin(100)
        (0..2).forEach(log::markDone)

        assertTrue(log.isComplete())
        assertEquals(2, log.firstMissing())
        log.delete()
        assertFalse(PieceLog.fileFor(file).exists())
    }

    @Test
    fun `pieces past the end of a smaller file are dropped`() {
        val log = PieceLog.load(file, 40, fromBytes = 100)
        assertEquals(setOf(0, 1), log.done)

        log.begin(40)

        assertEquals(setOf(0), log.done)
        assertEquals(2, log.count(50))
        assertEquals(40L until 50L, log.range(1, 50))
    }
}

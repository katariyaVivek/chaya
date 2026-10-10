package com.chaya.app.library

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An account's ZIP listed and opened one file at a time, never unpacked whole, never outside its folder. */
class ZipContentsTest {

    private lateinit var dir: File
    private lateinit var zip: File
    private val picture = ByteArray(3000) { (it % 7).toByte() }
    private val video = ByteArray(5000) { (it % 11).toByte() }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("chaya-zip").toFile()
        zip = File(dir, "someone.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun put(name: String, bytes: ByteArray?) {
                out.putNextEntry(ZipEntry(name))
                bytes?.let(out::write)
                out.closeEntry()
            }
            put("2026-10-01 first.jpg", picture)
            put("clips/", null)
            put("clips/2026-10-02 second.mp4", video)
            put("notes.txt", "hello".toByteArray())
            put("../escape.jpg", picture)
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `the listing has every file in packing order, with its kind and size, and no folders`() {
        val items = ZipContents.list(zip)

        assertEquals(listOf("2026-10-01 first.jpg", "2026-10-02 second.mp4", "notes.txt", "escape.jpg"), items.map { it.name })
        assertEquals(listOf(FileKind.PICTURE, FileKind.VIDEO, null, FileKind.PICTURE), items.map { it.kind })
        assertEquals(5000L, items[1].size)
        assertEquals("clips/2026-10-02 second.mp4", items[1].path)
    }

    @Test
    fun `one file is taken out as it was packed, and taken out again only when it is missing`() {
        val out = File(dir, "out")
        val item = ZipContents.list(zip)[1]

        val file = ZipContents.extract(zip, item, out)

        assertArrayEquals(video, file.readBytes())
        assertEquals(File(out, "2026-10-02 second.mp4"), file)
        val modified = file.lastModified()
        Thread.sleep(20)
        assertEquals(modified, ZipContents.extract(zip, item, out).lastModified())
        assertEquals(listOf("2026-10-02 second.mp4"), out.list()!!.toList())
    }

    @Test
    fun `a name that climbs out of the folder lands inside it`() {
        val out = File(dir, "out")
        val item = ZipContents.list(zip).single { it.path == "../escape.jpg" }

        val file = ZipContents.extract(zip, item, out)

        assertEquals(out.canonicalFile, file.canonicalFile.parentFile)
        assertArrayEquals(picture, file.readBytes())
        assertTrue(!File(dir, "escape.jpg").exists())
    }

    @Test
    fun `names are cut to their last part`() {
        assertEquals("a.jpg", ZipContents.safeName("x/y/a.jpg"))
        assertEquals("a.jpg", ZipContents.safeName("x\\a.jpg"))
        assertNull(ZipContents.safeName("x/.."))
        assertNull(ZipContents.safeName("x/"))
    }
}

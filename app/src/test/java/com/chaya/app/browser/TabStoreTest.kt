package com.chaya.app.browser

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** The open tabs on disk: what comes back after Android closes the app, and what closing tabs deletes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tabs").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val two = TabStore.Saved(
        tabs = listOf(
            TabStore.SavedTab(3, "https://news.example/story", "A story"),
            TabStore.SavedTab(7, "", ""),
        ),
        activeId = 7,
    )

    @Test
    fun `the tabs come back in order, with the one shown`() {
        TabStore(dir).save(two)

        assertEquals(two, TabStore(dir).load())
    }

    @Test
    fun `nothing saved, or an index that cannot be read, gives nothing to restore`() {
        assertNull(TabStore(dir).load())

        File(dir, "index.json").writeText("{ not json")
        assertNull(TabStore(dir).load())

        File(dir, "index.json").writeText("""{"active": 1, "tabs": []}""")
        assertNull(TabStore(dir).load())
    }

    @Test
    fun `a tab's history comes back as written, and a tab without one has none`() {
        val store = TabStore(dir)
        store.writeState(3, byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(1, 2, 3), store.readState(3))
        assertNull(store.readState(7))
    }

    @Test
    fun `closing a tab deletes its history, picture and icon`() {
        val store = TabStore(dir)
        store.save(two)
        store.writeState(3, byteArrayOf(1))
        store.writeThumbnail(3) { it.write(byteArrayOf(2)) }
        store.writeIcon(3) { it.write(byteArrayOf(3)) }

        store.remove(3)

        assertNull(store.readState(3))
        assertFalse(store.thumbnail(3).exists())
        assertFalse(store.icon(3).exists())
    }

    @Test
    fun `files of tabs no longer listed are deleted when the list is saved`() {
        val store = TabStore(dir)
        store.save(two)
        store.writeState(3, byteArrayOf(1))
        store.writeThumbnail(3) { it.write(byteArrayOf(2)) }
        store.writeState(7, byteArrayOf(4))

        store.save(TabStore.Saved(listOf(TabStore.SavedTab(7, "", "")), activeId = 7))

        assertNull(store.readState(3))
        assertFalse(store.thumbnail(3).exists())
        assertArrayEquals(byteArrayOf(4), store.readState(7))
    }

    @Test
    fun `close all tabs leaves nothing behind`() {
        val store = TabStore(dir)
        store.save(two)
        store.writeState(3, byteArrayOf(1))
        store.writeThumbnail(7) { it.write(byteArrayOf(2)) }

        store.clear()

        assertNull(store.load())
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a picture that fails to write leaves no half-written file`() {
        val store = TabStore(dir)
        store.writeThumbnail(3) { it.write(byteArrayOf(9)) }

        store.writeThumbnail(3) { out ->
            out.write(byteArrayOf(1))
            error("encoder failed")
        }

        assertArrayEquals(byteArrayOf(9), store.thumbnail(3).readBytes())
        assertEquals(listOf("3.webp"), dir.list()!!.toList())
    }
}

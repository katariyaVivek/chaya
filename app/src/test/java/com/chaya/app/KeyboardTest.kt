package com.chaya.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chaya draws edge to edge, so the window no longer shrinks for the keyboard: a screen or sheet with a text field
 * must make room for it itself (`imePadding`), or the field is typed into unseen under the keyboard, as Settings ›
 * AI model's model name was. The fields at the top of their screens (the address bar, the History and tab searches)
 * never meet the keyboard.
 */
class KeyboardTest {
    private val atTheTop = setOf("BrowserChrome.kt", "HistoryScreen.kt", "TabGrid.kt")

    @Test
    fun `every screen or sheet with a text field below the top makes room for the keyboard`() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("No sources found from ${File(".").absolutePath}", sources.isNotEmpty())
        val withFields = sources.filter { file -> "TextField(" in file.readText() && file.name !in atTheTop }
        assertTrue(withFields.isNotEmpty())
        val covered = withFields.filter { "imePadding()" !in it.readText() }.map { it.name }
        assertTrue("Text fields the keyboard covers: $covered", covered.isEmpty())
    }
}

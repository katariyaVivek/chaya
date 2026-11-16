package com.chaya.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's regular expressions are compiled by Android's regex engine (ICU), not the desktop Java these tests run
 * on, and ICU refuses some of Java's syntax. `Regex("(?U)\\s+")` compiled here and passed every test, then threw as
 * its object was first used on a phone, closing the app whenever a link's quality was saved. Reading the sources is
 * what catches it before a phone does.
 */
class AndroidRegexTest {

    /** Java's own regex syntax that ICU rejects: Unicode character classes, and the java* property classes. */
    private val refused = listOf("(?U)", "UNICODE_CHARACTER_CLASS", "\\p{java", "\\P{java")

    @Test
    fun `no regex uses syntax Android's engine refuses`() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("No sources found from ${File(".").absolutePath}", sources.isNotEmpty())
        val found = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                .filter { (_, line) -> refused.any { it in line } }
                .map { (i, _) -> "${file.path}:${i + 1}" }
        }
        assertTrue("Regex syntax Android's ICU engine refuses (a crash on a phone, not here): $found", found.isEmpty())
    }
}

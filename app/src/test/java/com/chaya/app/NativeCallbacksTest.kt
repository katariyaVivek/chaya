package com.chaya.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The native bridge finds Kotlin methods by name, and release builds are shrunk by R8, which renames whatever its
 * rules don't keep: `Whisper.Listener.heard` was renamed, and "Make subtitles" closed the app on the first phone it
 * ran on while every test passed (tests and debug builds are not shrunk). Reading the C sources and the rules is what
 * catches it before a phone does.
 */
class NativeCallbacksTest {
    @Test
    fun `every method the native code looks up by name is kept from R8`() {
        val sources = File("src/main/cpp").walkTopDown().filter { it.isFile && it.extension in setOf("c", "cpp") }.toList()
        assertTrue("No native sources found from ${File(".").absolutePath}", sources.isNotEmpty())
        val looked = sources.flatMap { file ->
            Regex("""Get(?:Static)?MethodID\([^"]*"([A-Za-z_][A-Za-z0-9_]*)"""").findAll(file.readText()).map { it.groupValues[1] }.toList()
        }
        assertTrue("the bridge's progress callback is found", "heard" in looked)
        val rules = File("proguard-rules.pro").readText()
        assertTrue("Whisper.Listener is kept", "-keep interface com.chaya.app.speech.Whisper\$Listener" in rules)
        val unkept = looked.filter { name -> !Regex("""\b$name\s*\(""").containsMatchIn(rules) }
        assertTrue("Methods the native code calls by name that R8 may rename: $unkept", unkept.isEmpty())
    }
}

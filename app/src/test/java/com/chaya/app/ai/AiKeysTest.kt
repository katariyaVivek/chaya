package com.chaya.app.ai

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Robolectric has no Android Keystore: the same AES-GCM, with a key held in memory (`KeystoreLockTest` on a device). */
private class MemoryLock(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : KeyLock {
    override fun seal(plain: ByteArray) = gcmSeal(key, plain)
    override fun open(sealed: ByteArray) = runCatching { gcmOpen(key, sealed) }.getOrNull()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiKeysTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(AiKeys.FILE, Context.MODE_PRIVATE)

    @Test
    fun `a key is kept sealed, never as it was typed, and given back`() {
        val keys = AiKeys(prefs, MemoryLock())
        assertNull(keys.setKey("groq", "  gsk_secret123  "))
        assertEquals("gsk_secret123", keys.key("groq"))
        assertTrue(keys.hasKey("groq"))
        assertFalse(keys.hasKey("openai"))

        val onDisk = File(context.applicationInfo.dataDir, "shared_prefs/${AiKeys.FILE}.xml")
        prefs.edit().commit()
        assertFalse("the key is never kept in plain text", onDisk.readText().contains("gsk_secret123"))

        keys.forgetKey("groq")
        assertNull(keys.key("groq"))
    }

    @Test
    fun `a key sealed by another lock is not opened, as after moving to a new phone`() {
        AiKeys(prefs, MemoryLock()).setKey("openai", "sk-one")
        assertNull(AiKeys(prefs, MemoryLock()).key("openai"))
    }

    @Test
    fun `what doesn't look like a key, or a service with none, is refused in words`() {
        val keys = AiKeys(prefs, MemoryLock())
        assertEquals("That doesn't look like a key. Paste it again.", keys.setKey("openai", "two words"))
        assertEquals("That doesn't look like a key. Paste it again.", keys.setKey("openai", " "))
        assertEquals("This service takes no key.", keys.setKey("ollama", "k"))
        assertNull(keys.key("openai"))
    }

    @Test
    fun `the settings are kept without the key, and the endpoint uses the chosen service's key`() {
        val keys = AiKeys(prefs, MemoryLock())
        val chosen = AiSettings(service = "groq", model = "llama-3.3-70b")
        keys.settings = chosen
        assertEquals(chosen, keys.settings)
        assertEquals(AiResult.Failed(AiError.NoKey), keys.endpoint())
        keys.setKey("groq", "gsk_1")
        assertEquals("gsk_1", (keys.endpoint() as AiResult.Done).value.key)
        assertFalse(AiSettings.fromJson(prefs.getString("settings", null)).toJson().contains("gsk"))
    }

    @Test
    fun `the AI model's preferences are left out of backups and moving to a new phone`() {
        listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml").forEach { path ->
            val rules = File(path).readText()
            assertTrue(path, rules.contains("""<exclude domain="sharedpref" path="${AiKeys.FILE}.xml" />"""))
        }
        val dataExtraction = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertEquals("cloud backup and device transfer alike", 2, Regex("chaya_ai.xml").findAll(dataExtraction).count())
    }

    @Test
    fun `choosing a service asks for its key, and Test says what the model answered`() {
        var settings by mutableValue(AiSettings())
        val saved = mutableListOf<Pair<String, String>>()
        var tested = 0
        composeRule.setContent {
            AiModelContent(
                settings = settings,
                keySaved = false,
                models = listOf("llama-3.3-70b", "qwen3-32b"),
                checking = Checking.Said("llama-3.3-70b answered in 0.8 s.", worked = true),
                onNavigateBack = {},
                actions = AiModelActions(
                    choose = { settings = it },
                    saveKey = { service, key -> saved += service to key; null },
                    test = { tested++ },
                ),
            )
        }
        composeRule.onNodeWithText("Groq").performScrollTo().performClick()
        assertEquals("groq", settings.service)
        composeRule.onNodeWithText("Groq key").performScrollTo().performTextInput("gsk_abc")
        composeRule.onNodeWithText("Save key").performScrollTo().performClick()
        assertEquals(listOf("groq" to "gsk_abc"), saved)
        composeRule.onNodeWithText("qwen3-32b").performScrollTo().performClick()
        assertEquals("qwen3-32b", settings.model)
        composeRule.onNodeWithText("Test").performScrollTo().performClick()
        assertEquals(1, tested)
        composeRule.onNodeWithText("llama-3.3-70b answered in 0.8 s.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a model on a PC asks for the PC's address and no key`() {
        composeRule.setContent {
            AiModelContent(
                settings = AiSettings(service = "ollama"),
                keySaved = false,
                models = emptyList(),
                checking = Checking.Idle,
                onNavigateBack = {},
                actions = AiModelActions(),
            )
        }
        composeRule.onNodeWithText("Address").performScrollTo().assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTextCount("Save key") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}

/** A `var` the composition reads and the test writes, without the test needing Compose's state imports at each use. */
private fun <T> mutableValue(initial: T) = androidx.compose.runtime.mutableStateOf(initial)

package com.chaya.app.diagnostics

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.platform.EngineSets
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app = mapOf("yt-dlp" to "2026.8.19", "yt-dlp-ejs" to "0.8.0", "gallery-dl" to "1.32.16")

    private fun status(
        versions: Map<String, String> = app,
        fromApp: Boolean = true,
        waiting: Map<String, String>? = null,
        checkedAt: Long? = null,
        updatesOn: Boolean = true,
        note: String? = null,
    ) = EngineSets.EngineStatus(versions, fromApp, started = true, waiting, checkedAt, updatesOn, note)

    @Test
    fun `the versions in use and where they came from are shown`() {
        composeRule.setContent { EngineSection(status(), onUpdatesChange = {}) }

        composeRule.onNodeWithText("yt-dlp 2026.8.19 · yt-dlp-ejs 0.8.0 · gallery-dl 1.32.16").assertExists()
        composeRule.onNodeWithText("In use: the copy that came with the app").assertExists()
        composeRule.onNodeWithText("Not checked for updates yet").assertExists()
    }

    @Test
    fun `an updated copy, one waiting for the next start, and the last note are shown`() {
        composeRule.setContent {
            EngineSection(
                status(
                    versions = app + ("yt-dlp" to "2026.9.1"),
                    fromApp = false,
                    waiting = app + ("yt-dlp" to "2026.9.20"),
                    checkedAt = 1_760_000_000_000L,
                    note = "Fetched yt-dlp 2026.9.20; used from the next start",
                ),
                onUpdatesChange = {},
            )
        }

        composeRule.onNodeWithText("In use: updated from PyPI").assertExists()
        composeRule.onNodeWithText("Newer copy ready (yt-dlp 2026.9.20", substring = true).assertExists()
        composeRule.onNodeWithText("Fetched yt-dlp 2026.9.20; used from the next start").assertExists()
        composeRule.onNodeWithText("Last checked", substring = true).assertExists()
    }

    @Test
    fun `the switch shows the setting and tapping the row reports the change`() {
        val changes = mutableListOf<Boolean>()
        composeRule.setContent { EngineSection(status(updatesOn = false), onUpdatesChange = { changes += it }) }

        composeRule.onNodeWithText("Off: the copy that came with the app is used from the next start").assertExists()
        composeRule.onNodeWithText("Keep it up to date").performClick()

        assertEquals(listOf(true), changes)
    }
}

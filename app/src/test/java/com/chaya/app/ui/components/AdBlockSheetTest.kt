package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdBlockSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `the sheet says what was blocked and turns blocking off everywhere or on this site`() {
        val everywhere = mutableListOf<Boolean>()
        val onSite = mutableListOf<Boolean>()
        composeRule.setContent {
            AdBlockSheet(
                site = "news.example", blocked = 7, enabled = true, blocksOnSite = true,
                ruleCount = 136_657, lastUpdated = 10 * day, now = 13 * day,
                onEnabledChange = { everywhere += it }, onBlocksOnSiteChange = { onSite += it }, onDismiss = {},
            )
        }

        composeRule.onNodeWithText("7 ads and trackers blocked on this page").assertExists()
        composeRule.onNodeWithText("Block ads and trackers").assertIsOn().performClick()
        composeRule.onNodeWithText("Block them on news.example").assertIsOn().performClick()

        assertEquals(listOf(false), everywhere)
        assertEquals(listOf(false), onSite)
        composeRule.onNodeWithText(
            "Uses EasyList and EasyPrivacy, the lists uBlock Origin uses (136,657 rules, updated 3 days ago). " +
                "Chaya fetches fresh copies about once a week.",
        ).assertExists()
    }

    @Test
    fun `with blocking off the site switch waits for the main one`() {
        composeRule.setContent {
            AdBlockSheet(
                site = "news.example", blocked = 0, enabled = false, blocksOnSite = true,
                ruleCount = null, lastUpdated = null,
                onEnabledChange = {}, onBlocksOnSiteChange = {}, onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Off: pages show their ads").assertExists()
        composeRule.onNodeWithText("Block ads and trackers").assertIsOff()
        composeRule.onNodeWithText("Block them on news.example").assertIsOff().assertIsNotEnabled()
    }

    @Test
    fun `a site the person allows says so`() {
        val onSite = mutableListOf<Boolean>()
        composeRule.setContent {
            AdBlockSheet(
                site = "news.example", blocked = 0, enabled = true, blocksOnSite = false,
                ruleCount = 10, lastUpdated = null,
                onEnabledChange = {}, onBlocksOnSiteChange = { onSite += it }, onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Ads are allowed on news.example").assertExists()
        composeRule.onNodeWithText("Block them on news.example").assertIsOff().performClick()
        assertEquals(listOf(true), onSite)
    }

    @Test
    fun `the note about the lists`() {
        assertEquals(
            "Uses EasyList and EasyPrivacy, the lists uBlock Origin uses. Reading them…",
            listsNote(ruleCount = null, lastUpdated = null, now = 0),
        )
        assertEquals(
            "Uses EasyList and EasyPrivacy, the lists uBlock Origin uses (1,200 rules, the copies that came " +
                "with Chaya). Chaya fetches fresh copies about once a week.",
            listsNote(ruleCount = 1_200, lastUpdated = null, now = 0),
        )
        assertEquals(true, listsNote(5, lastUpdated = day, now = day + 1000).contains("updated today"))
        assertEquals(true, listsNote(5, lastUpdated = day, now = 2 * day + 1000).contains("updated yesterday"))
    }
}

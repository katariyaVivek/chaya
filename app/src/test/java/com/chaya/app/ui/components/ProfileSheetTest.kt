package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.platform.ProfileMatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val profile = ProfileMatcher.match("https://www.instagram.com/someone/")!!

    private fun show(signedIn: Boolean, saves: MutableList<Boolean>, signIns: MutableList<Unit> = mutableListOf()) {
        composeRule.setContent {
            ProfileSheet(
                profile = profile,
                signedIn = signedIn,
                onSave = { saves += it },
                onSignIn = { signIns += Unit },
                onDismiss = {},
            )
        }
    }

    @Test
    fun `signed in, the sign-in is offered and only used when that button is tapped`() {
        val saves = mutableListOf<Boolean>()
        show(signedIn = true, saves)

        composeRule.onNodeWithText("@someone").assertIsDisplayed()
        composeRule.onNodeWithText("Try without signing in").performClick()
        composeRule.onNodeWithText("Save all posts with my sign-in").performClick()

        assertEquals(listOf(false, true), saves)
    }

    @Test
    fun `not signed in, the sheet offers a visitor's try and a way to sign in`() {
        val saves = mutableListOf<Boolean>()
        val signIns = mutableListOf<Unit>()
        show(signedIn = false, saves, signIns)

        composeRule.onNodeWithText("Save all posts with my sign-in").assertDoesNotExist()
        composeRule.onNodeWithText("Sign in to Instagram first").performClick()
        composeRule.onNodeWithText("Try without signing in").performClick()

        assertEquals(1, signIns.size)
        assertEquals(listOf(false), saves)
    }
}

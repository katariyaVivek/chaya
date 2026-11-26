package com.chaya.app.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chaya.app.home.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chaya's places on a real back stack: where Back goes from each, and which screens are kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlacesNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var nav: NavHostController

    private fun start() {
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Screen.Home.route) {
                composable(Screen.Home.route) {}
                composable(Screen.Browser.route) {}
                composable(Screen.Downloads.route) {}
            }
        }
        composeRule.waitForIdle()
    }

    private fun go(place: Place) {
        composeRule.runOnIdle { nav.goTo(place) }
        composeRule.waitForIdle()
    }

    private fun back() {
        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()
    }

    /** The places' screens on the back stack, bottom first: Home, then the browser, then the Library, the last shown. */
    private fun stack(): List<String> = composeRule.runOnIdle {
        val held = listOf(Screen.Home.route, Screen.Browser.route, Screen.Downloads.route)
            .filter { route -> runCatching { nav.getBackStackEntry(route) }.isSuccess }
        assertEquals("the screen shown is the top", held.last(), nav.currentDestination?.route)
        assertEquals("the one under it is next", held.getOrNull(held.size - 2), nav.previousBackStackEntry?.destination?.route)
        held
    }

    @Test
    fun `the Library opened from the browser is over its page, so Back is the page again`() {
        // The phone's Back in the Library, opened from a page, came out on Home: the page's screen had been dropped
        start()
        go(Place.BROWSE)
        val browser = composeRule.runOnIdle { nav.getBackStackEntry(Screen.Browser.route) }

        go(Place.LIBRARY)
        assertEquals(listOf(Screen.Home.route, Screen.Browser.route, Screen.Downloads.route), stack())

        back()
        assertEquals(Screen.Browser.route, composeRule.runOnIdle { nav.currentDestination?.route })
        // The same screen, so what it keeps (its page state) is as it was left
        assertSame(browser, composeRule.runOnIdle { nav.getBackStackEntry(Screen.Browser.route) })
    }

    @Test
    fun `Browse from the Library over the page goes back to the page`() {
        start()
        go(Place.BROWSE)
        go(Place.LIBRARY)
        go(Place.BROWSE)
        assertEquals(listOf(Screen.Home.route, Screen.Browser.route), stack())
    }

    @Test
    fun `the Library opened from Home is over Home, so Back is Home`() {
        start()
        go(Place.LIBRARY)
        assertEquals(listOf(Screen.Home.route, Screen.Downloads.route), stack())
        back()
        assertEquals(listOf(Screen.Home.route), stack())
    }

    @Test
    fun `Browse opened from the Library over Home is over Home, so Back is Home`() {
        start()
        go(Place.LIBRARY)
        go(Place.BROWSE)
        assertEquals(listOf(Screen.Home.route, Screen.Browser.route), stack())
    }

    @Test
    fun `Home from the Library over the page is Home alone`() {
        start()
        go(Place.BROWSE)
        go(Place.LIBRARY)
        go(Place.HOME)
        assertEquals(listOf(Screen.Home.route), stack())
    }
}

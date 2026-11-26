package com.chaya.app.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a place is brought forward: kept when shown, gone back to when behind, opened when not there. */
class PlaceStepTest {

    @Test
    fun `a place already shown stays, so a link shared to the browser on screen keeps its sheet`() {
        // The walkthrough's shared pin: the browser was shown, and was swapped for a new one that had lost the link
        assertEquals(PlaceStep.STAY, PlaceStep.of(shown = true, onStack = true))
    }

    @Test
    fun `a place further back is gone back to, its screen kept`() {
        // The browser under the Library it opened, or History over it
        assertEquals(PlaceStep.GO_BACK, PlaceStep.of(shown = false, onStack = true))
    }

    @Test
    fun `a place not there is opened`() {
        assertEquals(PlaceStep.OPEN, PlaceStep.of(shown = false, onStack = false))
    }

    @Test
    fun `the Library opens over the browser's page when the browser is open, so Back is that page`() {
        assertEquals(Place.BROWSE, Place.LIBRARY.opensOver(browseOpen = true))
        assertEquals(Place.HOME, Place.LIBRARY.opensOver(browseOpen = false))
    }

    @Test
    fun `Browse and Home open over Home`() {
        assertEquals(Place.HOME, Place.BROWSE.opensOver(browseOpen = false))
        assertEquals(Place.HOME, Place.HOME.opensOver(browseOpen = true))
    }
}

package com.chaya.app.home

/** Chaya's places (Stage): Home, Browse (a page in the browser) and the Library. */
enum class Place(val label: String) {
    HOME("Home"),
    BROWSE("Browse"),
    LIBRARY("Library"),
}

/**
 * How a place is brought forward, from what the back stack holds. A place already shown stays as it is, so the screen
 * that is handling something (a link just shared, whose sheet the browser is opening) is not swapped for a new one.
 * A place further back is gone back to, keeping its screen; one not there is opened, over Home.
 */
enum class PlaceStep {
    STAY,
    GO_BACK,
    OPEN;

    companion object {
        fun of(shown: Boolean, onStack: Boolean): PlaceStep = when {
            shown -> STAY
            onStack -> GO_BACK
            else -> OPEN
        }
    }
}

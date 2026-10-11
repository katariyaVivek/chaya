package com.chaya.app.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page whose renderer stops is opened again once, but not in a loop if it keeps stopping. */
class RendererLossTest {

    @Test
    fun `the first loss reloads the page`() {
        assertTrue(RendererLoss.shouldReload(lastGoneAt = 0, now = 5_000))
    }

    @Test
    fun `a second loss within ten seconds waits for the person`() {
        assertFalse(RendererLoss.shouldReload(lastGoneAt = 100_000, now = 104_000))
    }

    @Test
    fun `a loss long after the last one reloads again`() {
        assertTrue(RendererLoss.shouldReload(lastGoneAt = 100_000, now = 100_000 + RendererLoss.RETRY_WINDOW_MILLIS))
    }
}

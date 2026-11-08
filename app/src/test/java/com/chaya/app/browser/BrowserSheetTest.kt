package com.chaya.app.browser

import com.chaya.app.ui.components.QualityPickerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The browser's one sheet: a pill opens or closes its own, and a dismissal closes only its own. */
class BrowserSheetTest {

    @Test
    fun `a pill opens its sheet, closes it when it is shown, and replaces any other`() {
        assertEquals(BrowserSheet.Media, null.toggled(BrowserSheet.Media))
        assertNull(BrowserSheet.Media.toggled(BrowserSheet.Media))
        assertEquals(BrowserSheet.Link, BrowserSheet.Media.toggled(BrowserSheet.Link))
    }

    @Test
    fun `a dismissal closes only a sheet of its own kind`() {
        val picker = BrowserSheet.Quality(QualityPickerState.Loading)

        assertNull(picker.without<BrowserSheet.Quality>())
        assertNull(BrowserSheet.Profile.without<BrowserSheet.Profile>())
        assertEquals(BrowserSheet.Link, BrowserSheet.Link.without<BrowserSheet.Media>())
        assertEquals(picker, picker.without<BrowserSheet.Link>())
        assertNull(null.without<BrowserSheet.Link>())
    }

    @Test
    fun `only the ad blocker's sheet outlives a new document`() {
        assertEquals(BrowserSheet.AdBlock, BrowserSheet.AdBlock.forNewDocument())
        listOf(BrowserSheet.Media, BrowserSheet.Link, BrowserSheet.Profile, BrowserSheet.Quality(QualityPickerState.Loading))
            .forEach { assertNull(it.toString(), it.forNewDocument()) }
        assertNull(null.forNewDocument())
    }
}

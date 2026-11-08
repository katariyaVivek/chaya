package com.chaya.app.browser

import com.chaya.app.model.DetectedMedia
import com.chaya.app.platform.PlatformChoice
import com.chaya.app.ui.components.QualityPickerState

/**
 * The one sheet the browser shows over the page. At most one is open: opening a sheet replaces the one
 * shown. Going home or switching tabs closes it ([BrowserUiState.sheet] = null), and so does a new document
 * in the tab, except the ad-block sheet ([forNewDocument]).
 *
 * These used to be four separate flags, so two sheets could stack (a link shared from another app while
 * the page's media sheet was open), each reset had to name every flag, and a picker dismissed while it
 * was loading came back when its answer arrived. A new sheet is added here, and nowhere has to remember
 * to close it.
 *
 * The tab grid is the screen's own state, not a sheet: it is about the tabs, not the page, so it stays open
 * across the tab switches and new documents that close a sheet. It hides when a sheet opens.
 */
sealed interface BrowserSheet {
    /** The media found on the page, from the media pill. */
    data object Media : BrowserSheet

    /** The video or post behind a link on a supported site ([com.chaya.app.platform.LinkState]). */
    data object Link : BrowserSheet

    /** An account's posts saved as one ZIP ([BrowserUiState.profile]). */
    data object Profile : BrowserSheet

    /** A stream's qualities: reading them, ready to choose, or why they could not be read. */
    data class Quality(val picker: QualityPickerState) : BrowserSheet

    /** The ad blocker on this site and everywhere. Its switches reload the page, and the sheet stays for that. */
    data object AdBlock : BrowserSheet

    /** One sentence before Android asks about notifications, for the [download] the person just asked for. */
    data class Notifications(val download: AskedDownload) : BrowserSheet
}

/** A download asked for while Android's notification permission is not yet given, so it waits for the answer. */
sealed interface AskedDownload {
    /** A file found on the page. */
    data class File(val media: DetectedMedia) : AskedDownload

    /** A quality chosen in a link's sheet. */
    data class LinkQuality(val choice: PlatformChoice) : AskedDownload
}

/**
 * What stays open when the tab shows a new document (a reload, a link followed). The ad-block sheet is about the site,
 * and its own switches reload the page, so it stays. Every other sheet belongs to the document it was opened on.
 */
internal fun BrowserSheet?.forNewDocument(): BrowserSheet? = this as? BrowserSheet.AdBlock

/** [sheet] opened, or closed if it is already the one shown: what tapping a pill does. */
internal fun BrowserSheet?.toggled(sheet: BrowserSheet): BrowserSheet? = if (this == sheet) null else sheet

/**
 * Closed if it is a sheet of kind [S]; any other sheet stays. A sheet's dismissal only ever closes that
 * sheet, so a late dismiss cannot close one opened since.
 */
internal inline fun <reified S : BrowserSheet> BrowserSheet?.without(): BrowserSheet? = if (this is S) null else this

package com.chaya.app.browser

import com.chaya.app.ui.components.QualityPickerState

/**
 * The one sheet the browser shows over the page. At most one is open: opening a sheet replaces the one
 * shown, and leaving the page, going home or switching tabs closes it ([BrowserUiState.sheet] = null).
 *
 * These used to be four separate flags, so two sheets could stack (a link shared from another app while
 * the page's media sheet was open), each reset had to name every flag, and a picker dismissed while it
 * was loading came back when its answer arrived. A new sheet is added here, and nowhere has to remember
 * to close it.
 *
 * The ad-blocking sheet and the notification rationale are still the screen's own state.
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
}

/** [sheet] opened, or closed if it is already the one shown: what tapping a pill does. */
internal fun BrowserSheet?.toggled(sheet: BrowserSheet): BrowserSheet? = if (this == sheet) null else sheet

/**
 * Closed if it is a sheet of kind [S]; any other sheet stays. A sheet's dismissal only ever closes that
 * sheet, so a late dismiss cannot close one opened since.
 */
internal inline fun <reified S : BrowserSheet> BrowserSheet?.without(): BrowserSheet? = if (this is S) null else this

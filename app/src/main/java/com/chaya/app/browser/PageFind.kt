package com.chaya.app.browser

import com.chaya.app.detection.MediaSheetModel
import com.chaya.app.home.BarAction
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.ProfileMatch

/** The sheet a tap on Save opens: the video behind a site's link, an account's posts, or what the page plays. */
internal enum class FoundSheet { LINK, PROFILE, MEDIA }

/**
 * What the page shown has to save, as the bar's ink circle shows it. One value: a video on a site Chaya knows comes
 * first (still looking, or found), then an account's posts, then what the page itself plays. A failed lookup shows
 * nothing, as the pill before it did: a page that only looks like a video page should not nag.
 */
internal sealed interface PageFind {
    /** The circle's face and words. */
    val action: BarAction

    /** Nothing to save: the circle opens a new tab. */
    data object Nothing : PageFind {
        override val action: BarAction = BarAction.NewTab
    }

    /** A site's video, still being looked up; a tap opens its sheet, which says how that is going. */
    data class Looking(val site: String, val what: String) : PageFind {
        override val action: BarAction = BarAction.Looking("Finding the $what on $site")
    }

    /** Something to save, named in [title]; a tap opens [sheet]. */
    data class Found(val title: String, val sheet: FoundSheet) : PageFind {
        override val action: BarAction = BarAction.Save("Save $title")
    }

    companion object {
        fun of(startScreen: Boolean, link: LinkState, profile: ProfileMatch?, media: MediaSheetModel): PageFind {
            if (startScreen) return Nothing
            when (link) {
                is LinkState.Looking -> return Looking(link.match.platform.displayName, link.match.lookingFor)
                is LinkState.Answer -> return Found(link.media.title, FoundSheet.LINK)
                is LinkState.Failed -> return Nothing
                LinkState.Idle -> Unit
            }
            if (profile != null) return Found("@${profile.username}'s posts from ${profile.platform.displayName}", FoundSheet.PROFILE)
            if (media.isEmpty) return Nothing
            val title = media.primary?.title
                ?: if (media.visibleCount == 1) "1 item" else if (media.visibleCount > 1) "${media.visibleCount} items" else "what looks like ads"
            return Found(title, FoundSheet.MEDIA)
        }
    }
}

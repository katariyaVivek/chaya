package com.chaya.app.browser

/*
 * What a long press on a page offers, and pulling the page down to load it again. Plain Kotlin, no Android imports:
 * the WebView's hit test is read into [PressedOn] by `ChayaWebView`, and its touches are fed to [PullGesture].
 */

/** What a long press landed on: a link, a picture, or a picture that is also a link. One value. */
sealed class PressedOn {
    /** A link; [text] is what it says on the page, empty when it says nothing (a picture, an icon). */
    data class Link(val url: String, val text: String) : PressedOn()

    /** A picture, at [src]; [link] is where it leads when it is inside a link. */
    data class Image(val src: String, val link: String?) : PressedOn()

    /** The address shown at the menu's head. */
    val shown: String get() = when (this) {
        is Link -> url
        is Image -> link ?: src
    }

    /** What the menu offers, in order. */
    val actions: List<PageAction> get() = when (this) {
        is Link -> buildList {
            add(PageAction.OPEN_IN_NEW_TAB)
            add(PageAction.COPY_LINK)
            if (text.isNotBlank()) add(PageAction.COPY_LINK_TEXT)
            add(PageAction.DOWNLOAD_LINK)
            add(PageAction.SHARE_LINK)
        }
        is Image -> buildList {
            if (link != null) {
                add(PageAction.OPEN_IN_NEW_TAB)
                add(PageAction.COPY_LINK)
            }
            add(PageAction.OPEN_IMAGE_IN_NEW_TAB)
            add(PageAction.DOWNLOAD_IMAGE)
            add(PageAction.COPY_IMAGE_ADDRESS)
            add(PageAction.SHARE_IMAGE)
        }
    }

    companion object {
        /** Only web addresses get a menu: a `javascript:` or `data:` link is not something to open, copy or save. */
        private fun web(url: String?): String? =
            url?.trim()?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

        /**
         * What WebView's hit test says was pressed: its [kind], its `extra` (a link's or picture's address), and for a
         * link, what `requestFocusNodeHref` adds (the link's `url`, its words as `title`, a picture's `src`). Null for
         * anything else (text, an empty spot), which WebView handles itself: text is selected.
         */
        fun of(kind: HitKind, extra: String?, href: String? = null, title: String? = null, src: String? = null): PressedOn? =
            when (kind) {
                HitKind.LINK -> web(href ?: extra)?.let { Link(it, title?.trim().orEmpty()) }
                HitKind.IMAGE -> web(extra ?: src)?.let { Image(it, link = null) }
                HitKind.IMAGE_LINK -> {
                    // WebView's extra is the picture's address here; the link's comes from requestFocusNodeHref.
                    val picture = web(src) ?: web(extra)
                    val link = web(href)?.takeIf { it != picture }
                    when {
                        picture != null -> Image(picture, link)
                        link != null -> Link(link, title?.trim().orEmpty())
                        else -> null
                    }
                }
                HitKind.OTHER -> null
            }
    }
}

/** WebView's hit test types that have a menu (`SRC_ANCHOR_TYPE`, `IMAGE_TYPE`, `SRC_IMAGE_ANCHOR_TYPE`). */
enum class HitKind {
    LINK,
    IMAGE,
    IMAGE_LINK,
    OTHER,
}

/** One row of the menu. */
enum class PageAction(val label: String) {
    OPEN_IN_NEW_TAB("Open in new tab"),
    COPY_LINK("Copy link address"),
    COPY_LINK_TEXT("Copy link text"),
    DOWNLOAD_LINK("Download link"),
    SHARE_LINK("Share link"),
    OPEN_IMAGE_IN_NEW_TAB("Open image in new tab"),
    DOWNLOAD_IMAGE("Download image"),
    COPY_IMAGE_ADDRESS("Copy image address"),
    SHARE_IMAGE("Share image"),
}

/**
 * Pull to refresh: the page dragged down while it is already at its top. WebView says so by over-scrolling, which it
 * does only when nothing on the page took the drag (a list scrolled inside the page takes it first), so a page whose
 * own list is scrolled down scrolls that list rather than loading again.
 *
 * Fed with the finger's height in pixels; [pulled] is how far, 0 to 1, for the indicator.
 */
class PullGesture(private val threshold: Float) {
    /** Where the finger was when the page was first found at its top during this drag; null until then. */
    private var startedAt: Float? = null
    private var lastY = 0f

    /** How far the page has been pulled, 0 (not at all) to 1 (letting go now loads it again). */
    var pulled: Float = 0f
        private set

    fun down(y: Float) {
        startedAt = null
        lastY = y
        pulled = 0f
    }

    /**
     * The finger moved to [y]. [overScrolledAtTop] is whether WebView over-scrolled at the page's top since the last
     * move; the pull starts from the first such move, so the page's own scrolling before it does not count.
     */
    fun move(y: Float, overScrolledAtTop: Boolean) {
        val start = startedAt
        if (start == null) {
            if (overScrolledAtTop && y > lastY) startedAt = lastY
        }
        lastY = y
        val from = startedAt ?: return
        val distance = y - from
        if (distance <= 0f) {
            // Pushed back up past where it started: the page scrolls again, and a new pull starts from the top.
            startedAt = null
            pulled = 0f
            return
        }
        pulled = (distance / threshold).coerceAtMost(1f)
    }

    /** The finger lifted: true when the page should load again. */
    fun up(): Boolean {
        val refresh = pulled >= 1f
        cancel()
        return refresh
    }

    /** Another gesture took over (a second finger, a zoom): nothing happens. */
    fun cancel() {
        startedAt = null
        pulled = 0f
    }
}

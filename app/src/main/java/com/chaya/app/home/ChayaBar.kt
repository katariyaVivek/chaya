package com.chaya.app.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaya.app.ui.theme.LocalChayaColors
import com.chaya.app.ui.theme.pressScale
import kotlin.math.roundToInt

/** What the bar's middle holds: the way into Browse, or, while browsing, the page's own controls. */
sealed interface BarMiddle {
    /** On Home and in the Library: Browse, a place like the other two. */
    data object Browse : BarMiddle

    /** While browsing: Back, Forward and the tabs. [tabsShown] while the tab grid is open, which Tabs closes. */
    data class Page(
        val canGoBack: Boolean,
        val canGoForward: Boolean,
        val tabCount: Int,
        val tabsShown: Boolean = false,
    ) : BarMiddle
}

/** The ink circle at the bar's end: always the one main thing to do where the person is. */
sealed interface BarAction {
    val label: String

    /** Home and the Library: open a link or search, in the browser. */
    data object Go : BarAction {
        override val label = "Open a link or search"
    }

    /** Browsing with nothing found to save: a new tab. */
    data object NewTab : BarAction {
        override val label = "New tab"
    }

    /** The page is a video on a site Chaya knows, still being looked up; a tap shows how that is going. */
    data class Looking(override val label: String) : BarAction

    /** The page has something to save, named in [label]; a tap opens its sheet. */
    data class Save(override val label: String) : BarAction
}

/** How far above the phone's navigation the bar reaches (62dp tall, 16dp either side), and a little room. */
val ChayaBarClearance = 100.dp

private val EdgeSlot = 64.dp

/**
 * Chaya's one bar, on every screen (DESIGN.md, Bottom bar). Home is always the far-left slot and the Library the
 * far-right one, its amber ring the one sign that something is saving; the middle is Browse, or the page's own
 * controls while browsing; the ink circle is the main thing to do here ([BarAction]). The place shown is lit.
 * A solid sheet: blurring what is behind it needs Android 12, and the phone's oldest is 8.
 */
@Composable
fun ChayaBar(
    place: Place,
    middle: BarMiddle,
    action: BarAction,
    progress: HomeShelf.Progress?,
    onPlace: (Place) -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onForward: () -> Unit = {},
    onTabs: () -> Unit = {},
    /** On the pill itself, for a gesture along the bar (the browser's swipe between tabs). */
    pillModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
            modifier = Modifier.weight(1f).height(62.dp).then(pillModifier),
        ) {
            Row(modifier = Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                PlaceSlot(Place.HOME, Icons.Default.Home, lit = place == Place.HOME, onPlace = onPlace)
                when (middle) {
                    BarMiddle.Browse -> PlaceSlot(
                        Place.BROWSE,
                        Icons.Default.Public,
                        lit = place == Place.BROWSE,
                        onPlace = onPlace,
                        fillsMiddle = true,
                    )
                    is BarMiddle.Page -> PageControls(middle, onBack, onForward, onTabs)
                }
                LibrarySlot(lit = place == Place.LIBRARY, progress = progress, onPlace = onPlace)
            }
        }
        ActionCircle(action, onAction)
    }
}

@Composable
private fun RowScope.PlaceSlot(
    place: Place,
    icon: ImageVector,
    lit: Boolean,
    onPlace: (Place) -> Unit,
    /** The middle slot takes the room between the ends; Home and the Library are the same width everywhere. */
    fillsMiddle: Boolean = false,
    iconContent: @Composable () -> Unit = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
) {
    val content = if (lit) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = (if (fillsMiddle) Modifier.weight(1f) else Modifier.width(EdgeSlot))
            .fillMaxHeight()
            .pressScale()
            .clip(CircleShape)
            .background(if (lit) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { if (!lit) onPlace(place) }
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                selected = lit
                contentDescription = place.label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            IconBox { iconContent() }
            Text(
                place.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = content,
                maxLines = 1,
            )
        }
    }
}

/** Every slot's icon in the same box, the Library's ring the tallest, so the names under them share a line. */
@Composable
private fun IconBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { content() }
}

/** The Library, with an amber ring around its icon while anything is saving: how far, when it is known. */
@Composable
private fun RowScope.LibrarySlot(lit: Boolean, progress: HomeShelf.Progress?, onPlace: (Place) -> Unit) {
    PlaceSlot(Place.LIBRARY, Icons.Default.VideoLibrary, lit = lit, onPlace = onPlace) {
        if (progress == null) {
            Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(22.dp))
        } else {
            val fraction = progress.fraction
            Box(
                modifier = Modifier.size(28.dp).semantics {
                    contentDescription = if (fraction != null) "${(fraction * 100).roundToInt()}% saved"
                    else if (progress.running == 1) "1 saving" else "${progress.running} saving"
                },
                contentAlignment = Alignment.Center,
            ) {
                val signal = LocalChayaColors.current.signal
                val track = MaterialTheme.colorScheme.outlineVariant
                if (fraction != null) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(28.dp),
                        color = signal,
                        trackColor = track,
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = signal, trackColor = track, strokeWidth = 2.5.dp)
                }
                Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** Back, Forward and Tabs, as icons, lit together as the place shown while browsing. */
@Composable
private fun RowScope.PageControls(page: BarMiddle.Page, onBack: () -> Unit, onForward: () -> Unit, onTabs: () -> Unit) {
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .semantics { selected = true },
    ) {
        PageButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", enabled = page.canGoBack && !page.tabsShown, onClick = onBack)
        PageButton(Icons.AutoMirrored.Filled.ArrowForward, "Forward", enabled = page.canGoForward && !page.tabsShown, onClick = onForward)
        PageButton(
            icon = null,
            label = "Tabs",
            enabled = true,
            onClick = onTabs,
            description = if (page.tabsShown) "Close tabs" else "Tabs (${page.tabCount})",
        ) { tint ->
            // The tab count in a rounded square, as browsers show it; filled while the tabs are shown.
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .then(
                        if (page.tabsShown) Modifier.background(tint, RoundedCornerShape(6.dp))
                        else Modifier.border(1.75.dp, tint, RoundedCornerShape(6.dp)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${page.tabCount}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (page.tabsShown) MaterialTheme.colorScheme.primaryContainer else tint,
                )
            }
        }
    }
}

@Composable
private fun RowScope.PageButton(
    icon: ImageVector?,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    description: String = label,
    iconContent: @Composable (tint: Color) -> Unit = { tint ->
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    },
) {
    val tint = MaterialTheme.colorScheme.onPrimaryContainer
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .pressScale()
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Icons only: three names do not fit between Home and the Library on a narrow phone; they are the labels read out
        IconBox { iconContent(tint) }
    }
}

/** The ink circle: + to go somewhere new, or Save with an amber dot when the page has something to save. */
@Composable
private fun ActionCircle(action: BarAction, onAction: () -> Unit) {
    Surface(
        onClick = onAction,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 2.dp,
        modifier = Modifier.size(62.dp).pressScale().semantics { contentDescription = action.label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (action) {
                BarAction.Go, BarAction.NewTab ->
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(26.dp))
                is BarAction.Looking -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(54.dp),
                        color = LocalChayaColors.current.signal,
                        trackColor = Color.Transparent,
                        strokeWidth = 2.5.dp,
                    )
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(26.dp).alpha(0.6f))
                }
                is BarAction.Save -> {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(26.dp))
                    // The signal that something is here: Stage's amber, ringed in ink so it reads on the circle
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 10.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(LocalChayaColors.current.signal),
                    )
                }
            }
        }
    }
}

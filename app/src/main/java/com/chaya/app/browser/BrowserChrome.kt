package com.chaya.app.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaya.app.detection.MediaUrlClassifier
import com.chaya.app.ui.theme.ChayaMotion
import com.chaya.app.ui.theme.StaggeredAppear
import com.chaya.app.ui.theme.pressScale

// --------------------------------------------------------------------- //
// Address bar
// --------------------------------------------------------------------- //

/**
 * On the start screen, or while editing, this is a text field with a Paste
 * shortcut. On a page it collapses to the site name with a lock; tapping it
 * edits the address.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AddressBar(
    url: String,
    homeVisible: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onGo: () -> Unit,
    onPaste: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    /** The ad blocker's shield for the page shown; null on pages it has nothing to say about. */
    adBlock: AdBlockBadge? = null,
    onAdBlock: () -> Unit = {},
    /** Whether the page shown is a bookmark; the star on the site pill adds or removes it. */
    bookmarked: Boolean = false,
    onToggleBookmark: (() -> Unit)? = null,
    /** Bookmarks and history matching what is typed, shown under the field while it has focus. */
    suggestions: List<com.chaya.app.history.Suggestion> = emptyList(),
    onSuggestion: (String) -> Unit = {},
    /** A fresh tab asks for its field ready to type in, keyboard up; [onFocused] says that was done, once. */
    focusNow: Boolean = false,
    onFocused: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    if (homeVisible || editing) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val fill by animateColorAsState(
            targetValue = if (focused) MaterialTheme.colorScheme.surfaceContainerHighest
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            animationSpec = ChayaMotion.tweenShort(),
            label = "fieldFill"
        )

        Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        editing = false
                    }
                },
            interactionSource = interaction,
            placeholder = {
                Text(
                    text = if (homeVisible) "Search or paste a link" else "Search or enter address",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (input.isEmpty() && homeVisible) {
                    PasteChip(onClick = onPaste)
                } else {
                    AnimatedVisibility(
                        visible = input.isNotEmpty(),
                        enter = scaleIn(initialScale = 0.7f) + fadeIn(ChayaMotion.tweenShort()),
                        exit = scaleOut(targetScale = 0.7f) + fadeOut(ChayaMotion.tweenShort())
                    ) {
                        IconButton(onClick = { onInputChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    onGo()
                    // Clearing focus alone left the keyboard open (seen in the emulator walkthrough),
                    // covering the page and the bar, so hide it explicitly too.
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = fill,
                unfocusedContainerColor = fill,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )
        if (focused && suggestions.isNotEmpty()) {
            SuggestionList(suggestions) { url ->
                onSuggestion(url)
                keyboardController?.hide()
                focusManager.clearFocus()
            }
        }
        }

        LaunchedEffect(editing) {
            if (editing) {
                withFrameNanos { }
                runCatching { focusRequester.requestFocus() }
            }
        }

        LaunchedEffect(focusNow) {
            if (focusNow) {
                withFrameNanos { }
                runCatching { focusRequester.requestFocus() }
                keyboardController?.show()
                onFocused()
            }
        }
    } else {
        UrlPill(
            url = url,
            onEdit = { editing = true },
            onReload = onReload,
            adBlock = adBlock,
            onAdBlock = onAdBlock,
            bookmarked = bookmarked,
            onToggleBookmark = onToggleBookmark,
            modifier = modifier,
        )
    }
}

@Composable
private fun PasteChip(onClick: () -> Unit) {
    Text(
        text = "Paste",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/** Bookmarks first, then pages from history, as the person types; tapping one opens it. */
@Composable
private fun SuggestionList(suggestions: List<com.chaya.app.history.Suggestion>, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 4.dp)
    ) {
        suggestions.forEach { suggestion ->
            val host = MediaUrlClassifier.hostOf(suggestion.url)?.removePrefix("www.") ?: suggestion.url
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(suggestion.url) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (suggestion.bookmarked) Icons.Default.Star else Icons.Default.History,
                    contentDescription = if (suggestion.bookmarked) "Bookmark" else "From history",
                    tint = if (suggestion.bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = suggestion.title.ifBlank { host },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = host,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** What the address bar's shield shows: whether ads are blocked on this page, and how many requests were. */
internal data class AdBlockBadge(val active: Boolean, val blocked: Int)

/** The page's site name with a lock for https; the full address appears once it is tapped. */
@Composable
private fun UrlPill(
    url: String,
    onEdit: () -> Unit,
    onReload: () -> Unit,
    adBlock: AdBlockBadge?,
    onAdBlock: () -> Unit,
    bookmarked: Boolean = false,
    onToggleBookmark: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val host = remember(url) { MediaUrlClassifier.hostOf(url)?.removePrefix("www.") ?: url }
    val secure = url.startsWith("https://")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (secure) Icons.Default.Lock else Icons.Default.Public,
            contentDescription = if (secure) "Secure connection" else "Not secure",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = host,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onToggleBookmark != null) {
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (bookmarked) Icons.Default.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (bookmarked) "Remove bookmark" else "Add bookmark",
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (adBlock != null) ShieldButton(adBlock, onAdBlock)
        IconButton(onClick = onReload) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** The ad blocker's shield: filled while it blocks here, with how many requests it blocked on this page. */
@Composable
private fun ShieldButton(badge: AdBlockBadge, onClick: () -> Unit) {
    val label = when {
        !badge.active -> "Ad blocker off here"
        badge.blocked == 1 -> "Ad blocker: 1 blocked"
        else -> "Ad blocker: ${badge.blocked} blocked"
    }
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        BadgedBox(
            badge = {
                if (badge.active && badge.blocked > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) { Text(if (badge.blocked > 99) "99+" else "${badge.blocked}") }
                }
            }
        ) {
            Icon(
                imageVector = if (badge.active) Icons.Filled.Shield else Icons.Outlined.Shield,
                contentDescription = null,
                tint = if (badge.active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// --------------------------------------------------------------------- //
// Start screen
// --------------------------------------------------------------------- //

internal data class QuickSite(val monogram: String, val name: String, val url: String) {
    companion object {
        /** A bookmark as a start-screen tile: its site's first letter, its title (else its site). */
        fun of(url: String, title: String): QuickSite {
            val host = MediaUrlClassifier.hostOf(url)?.removePrefix("www.") ?: url
            return QuickSite(host.take(1).uppercase(), title.ifBlank { host }, url)
        }
    }
}

private val quickSites = listOf(
    QuickSite("Y", "YouTube", "https://m.youtube.com"),
    QuickSite("In", "Instagram", "https://www.instagram.com"),
    QuickSite("Tk", "TikTok", "https://www.tiktok.com"),
    QuickSite("X", "X", "https://x.com"),
)

/**
 * A new tab, before any page is open: only a place to go, the quick sites (bookmarks first) and the way to Bookmarks and
 * History. Chaya's name and what was saved are Home's; the address field above is where a link is pasted.
 */
@Composable
internal fun NewTabPage(
    visible: Boolean,
    showOnboardingHint: Boolean,
    onDismissOnboardingHint: () -> Unit,
    onSelectUrl: (String) -> Unit,
    /** Bookmarks, shown first among the quick sites. */
    bookmarks: List<QuickSite> = emptyList(),
    onOpenBookmarks: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(ChayaMotion.tweenStandard()),
        exit = fadeOut(ChayaMotion.tweenShort())
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(20.dp))

                SectionTitle("Quick sites")
                // Bookmarks first, then the sites Chaya knows; one row, scrolled sideways when long.
                // Just the four known sites spread across the width, as before any bookmark.
                val sites = (bookmarks + quickSites).distinctBy { it.url }
                val sideways = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (bookmarks.isEmpty()) Modifier else Modifier.horizontalScroll(sideways)),
                    horizontalArrangement = if (bookmarks.isEmpty()) Arrangement.SpaceBetween else Arrangement.spacedBy(8.dp)
                ) {
                    sites.forEach { site -> SiteTile(site, onSelectUrl) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeChip(Icons.Default.Star, "Bookmarks", onOpenBookmarks)
                    HomeChip(Icons.Default.History, "History", onOpenHistory)
                }

                // First-run hint: part of the page itself, no overlay, no carousel.
                if (showOnboardingHint) {
                    Spacer(Modifier.height(24.dp))
                    StaggeredAppear(index = 0) {
                        OnboardingHint(onDismiss = onDismissOnboardingHint)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** A pill on the new tab page that opens another screen. */
@Composable
private fun HomeChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .pressScale()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
/** A section's name, as Home's "Recently saved" and the Library's days have it. */
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun SiteTile(site: QuickSite, onClick: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .pressScale()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick(site.url) }
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = site.monogram,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = site.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One-line first-run pointer at the bar's Save. */
@Composable
private fun OnboardingHint(onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDismiss() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.FileDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Open a page with a video. When one is found, the round button at the bottom turns into Save. Tap to dismiss.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

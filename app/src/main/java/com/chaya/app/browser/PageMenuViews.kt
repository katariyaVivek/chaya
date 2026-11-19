package com.chaya.app.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** The menu a long press on a link or picture opens: where it leads at its head, then what can be done with it. */
@Composable
internal fun PageMenu(pressed: PressedOn, onAction: (PageAction) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                val text = (pressed as? PressedOn.Link)?.text.orEmpty()
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    if (text.isNotBlank()) {
                        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        pressed.shown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                pressed.actions.forEach { action ->
                    Text(
                        action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAction(action) }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

/** How far the page is pulled down: a round button with the reload arrow, turning as it comes down; none at rest. */
@Composable
internal fun PullIndicator(pulled: Float, modifier: Modifier = Modifier) {
    if (pulled <= 0f) return
    val ready = pulled >= 1f
    Box(modifier.offset(y = (PULL_TRAVEL_DP * pulled).dp - 40.dp).alpha((pulled * 1.5f).coerceAtMost(1f))) {
        Surface(
            shape = CircleShape,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = if (ready) "Let go to reload" else "Pull to reload",
                    tint = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp).rotate(pulled * 270f),
                )
            }
        }
    }
}

/** How far the indicator travels down the page as it is pulled, in dp. */
private const val PULL_TRAVEL_DP = 72

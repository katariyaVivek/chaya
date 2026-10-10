package com.chaya.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit

/**
 * The ad blocker for the page shown: how much it blocked, the switch for all sites, and the switch for this
 * site. A change reloads the page, so it applies at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdBlockSheet(
    site: String,
    blocked: Int,
    enabled: Boolean,
    blocksOnSite: Boolean,
    /** Rules in use, or null while the lists are still being read. */
    ruleCount: Int?,
    /** When the lists were last fetched, or null while the copies shipped in the app are in use. */
    lastUpdated: Long?,
    onEnabledChange: (Boolean) -> Unit,
    onBlocksOnSiteChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp, top = 4.dp)) {
            Text(
                text = "Ad blocker",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary(site, blocked, enabled, blocksOnSite),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow("Block ads and trackers", checked = enabled, onChange = onEnabledChange)
            if (site.isNotEmpty()) {
                SwitchRow(
                    "Block them on $site",
                    checked = enabled && blocksOnSite,
                    enabled = enabled,
                    onChange = onBlocksOnSiteChange,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = listsNote(ruleCount, lastUpdated, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun summary(site: String, blocked: Int, enabled: Boolean, blocksOnSite: Boolean): String = when {
    !enabled -> "Off: pages show their ads"
    !blocksOnSite -> "Ads are allowed on $site"
    blocked == 1 -> "1 ad or tracker blocked on this page"
    else -> "$blocked ads and trackers blocked on this page"
}

internal fun listsNote(ruleCount: Int?, lastUpdated: Long?, now: Long): String {
    val lists = "Uses EasyList and EasyPrivacy, the lists uBlock Origin uses"
    if (ruleCount == null) return "$lists. Reading them…"
    val rules = "%,d".format(ruleCount)
    val updated = when {
        lastUpdated == null -> "the copies that came with Chaya"
        else -> when (val days = TimeUnit.MILLISECONDS.toDays((now - lastUpdated).coerceAtLeast(0))) {
            0L -> "updated today"
            1L -> "updated yesterday"
            else -> "updated $days days ago"
        }
    }
    return "$lists ($rules rules, $updated). Chaya fetches fresh copies about once a week."
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

package com.chaya.app.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chaya.app.platform.ENGINE_PACKAGES
import com.chaya.app.platform.EngineSets
import java.text.DateFormat
import java.util.Date

/**
 * The video-site engine: which yt-dlp, yt-dlp-ejs and gallery-dl are in use, whether a newer set waits for the
 * next start, when PyPI was last asked, and the switch that turns updates off.
 */
@Composable
fun EngineSection(
    status: EngineSets.EngineStatus,
    onUpdatesChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Video-site engine",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = versionsLine(status.versions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = when {
                status.fromApp && status.started -> "In use: the copy that came with the app"
                status.fromApp -> "The copy that came with the app"
                status.started -> "In use: updated from PyPI"
                else -> "Updated from PyPI"
            },
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        status.waiting?.let { waiting ->
            Text(
                text = "Newer copy ready (${versionsLine(waiting)}); checked and used from the next start",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        Text(
            text = status.checkedAt?.let {
                "Last checked " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            } ?: "Not checked for updates yet",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        status.note?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = status.updatesOn, role = Role.Switch, onValueChange = onUpdatesChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Keep it up to date",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (status.updatesOn) "Asks PyPI about once a day; sends nothing about you"
                    else "Off: the copy that came with the app is used from the next start",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            // The row toggles; the switch only shows the state.
            Switch(checked = status.updatesOn, onCheckedChange = null)
        }
    }
}

private fun versionsLine(versions: Map<String, String>) =
    ENGINE_PACKAGES.joinToString(" · ") { "${it.name} ${versions[it.name] ?: "?"}" }

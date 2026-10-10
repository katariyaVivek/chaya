package com.chaya.app.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaya.app.ChayaApplication
import com.chaya.app.database.HistoryEntity
import com.chaya.app.detection.MediaUrlClassifier
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/** The pages seen, by day, newest first; search, delete one, clear a span, or stop recording. */
@Composable
fun HistoryScreen(onNavigateBack: () -> Unit, onOpen: (String) -> Unit) {
    val record = (LocalContext.current.applicationContext as ChayaApplication).browsing
    val rows by remember(record) { record.historyRows }.collectAsState(initial = emptyList())
    val recording by record.recording.collectAsState()
    val scope = rememberCoroutineScope()
    HistoryContent(
        rows = rows,
        recording = recording,
        onRecordingChange = record::setRecording,
        // The navigation host goes back to the browser once a page is picked.
        onOpen = onOpen,
        onDelete = { url -> scope.launch { record.removeFromHistory(url) } },
        onClear = { range -> scope.launch { record.clearHistory(range) } },
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryContent(
    rows: List<HistoryEntity>,
    recording: Boolean,
    onRecordingChange: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: (ClearRange) -> Unit,
    onNavigateBack: () -> Unit,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val shown = rows.filter { query.isBlank() || it.url.contains(query.trim(), true) || it.title.contains(query.trim(), true) }
    val days = remember(shown, now, zone) { historyByDay(shown, now, zone) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear history") },
                                onClick = {
                                    menuOpen = false
                                    clearing = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "recording") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = recording, role = Role.Switch, onValueChange = onRecordingChange)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Save history", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = if (recording) "Pages you open are kept on this phone only" else "Off: pages you open are not kept",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = recording, onCheckedChange = null)
                }
            }
            item(key = "search") {
                SearchField(query, onChange = { query = it }, placeholder = "Search history")
            }
            if (days.isEmpty()) {
                item(key = "empty") {
                    Empty(if (query.isBlank()) "Pages you open show up here." else "Nothing in history matches “${query.trim()}”")
                }
            }
            days.forEach { (day, pages) ->
                item(key = "day:$day") {
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
                items(pages, key = { "page:${it.url}" }) { page ->
                    PageRow(
                        url = page.url,
                        title = page.title,
                        detail = timeOf(page.visitedAt),
                        onOpen = { onOpen(page.url) },
                        onRemove = { onDelete(page.url) },
                        removeLabel = "Delete ${page.title.ifBlank { page.url }} from history",
                    )
                }
            }
        }
    }

    if (clearing) ClearDialog(onClear = { range ->
        clearing = false
        onClear(range)
    }, onDismiss = { clearing = false })
}

@Composable
private fun ClearDialog(onClear: (ClearRange) -> Unit, onDismiss: () -> Unit) {
    var range by remember { mutableStateOf(ClearRange.LAST_HOUR) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear history") },
        text = {
            Column {
                ClearRange.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .selectable(selected = option == range, role = Role.RadioButton, onClick = { range = option })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == range, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onClear(range) }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A search pill above a list. */
@Composable
internal fun SearchField(query: String, onChange: (String) -> Unit, placeholder: String) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One page: the site's monogram, its title and site, and an × to remove it. */
@Composable
internal fun PageRow(url: String, title: String, detail: String, onOpen: () -> Unit, onRemove: () -> Unit, removeLabel: String) {
    val host = MediaUrlClassifier.hostOf(url)?.removePrefix("www.") ?: url
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = host.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title.ifBlank { host },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (detail.isEmpty()) host else "$host · $detail",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = removeLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun Empty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
    )
}

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** Pages grouped by the day they were last seen, newest day first: "Today", "Yesterday", then dates. */
internal fun historyByDay(rows: List<HistoryEntity>, now: Long, zone: ZoneId): List<Pair<String, List<HistoryEntity>>> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val format = DateTimeFormatter.ofPattern("EEEE, d MMMM")
    val formatWithYear = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
    return rows.sortedByDescending { it.visitedAt }
        .groupBy { Instant.ofEpochMilli(it.visitedAt).atZone(zone).toLocalDate() }
        .map { (day: LocalDate, pages) ->
            val label = when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> day.format(if (day.year == today.year) format else formatWithYear)
            }
            label to pages
        }
}

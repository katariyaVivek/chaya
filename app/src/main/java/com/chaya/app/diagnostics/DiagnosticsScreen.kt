package com.chaya.app.diagnostics

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Lists crash reports (2.1) above the on-device event log (2.3), each with
 * share/delete. Reached from the Downloads top bar — a real-user surface,
 * not a hidden debug menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val eventLog = remember(context) {
        (context.applicationContext as com.chaya.app.ChayaApplication).eventLog
    }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var crashes by remember { mutableStateOf(listOf<CrashReport>()) }
    var insights by remember { mutableStateOf<Insights?>(null) }
    // Opt-in and off by default: aggregation runs only while the section is
    // expanded, so steady-state cost is zero when the user never looks.
    var showInsights by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(eventLog) {
        lines = eventLog.snapshot().map { formatLine(it) }
        crashes = CrashReporter.listReports(context)
    }

    LaunchedEffect(eventLog, showInsights) {
        insights = if (showInsights) Insights.compute(eventLog.snapshot()) else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val text = eventLog.exportText().ifBlank { "No events recorded yet." }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Chaya diagnostics log")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
                            }.onFailure {
                                snackbarHostState.showSnackbar("No app can share this log")
                            }
                        }
                    }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share log")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            if (crashes.isNotEmpty()) {
                Text(
                    text = "Crash reports",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(crashes, key = { it.fileName }) { report ->
                        CrashRow(
                            report = report,
                            onShare = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Chaya crash report")
                                    putExtra(Intent.EXTRA_TEXT, report.toText())
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(intent, "Share crash report"))
                                }.onFailure {
                                    scope.launch { snackbarHostState.showSnackbar("No app can share this report") }
                                }
                            },
                            onDelete = {
                                CrashReporter.deleteReport(context, report.fileName)
                                crashes = CrashReporter.listReports(context)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = "Event log — URLs are scrubbed, nothing leaves the phone unless you share it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            InsightsSection(
                expanded = showInsights,
                insights = insights,
                onToggle = { showInsights = !showInsights },
            )
            Spacer(Modifier.height(12.dp))
            if (lines.isEmpty()) {
                Text(
                    text = "No events yet. Browse a page and download something.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** One crash report row: exception + device line, share and delete actions. */
@Composable
private fun CrashRow(
    report: CrashReport,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Text(
            text = "${report.exceptionName} · ${report.deviceModel}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onShare) {
                Icon(imageVector = Icons.Default.Share, contentDescription = "Share crash report")
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete crash report")
            }
        }
    }
}

/** Collapsed-by-default aggregates; expanding computes from the live snapshot. */
@Composable
private fun InsightsSection(
    expanded: Boolean,
    insights: Insights?,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "Insights (hide)" else "Insights (show)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            val data = insights
            if (data == null) {
                Text(
                    text = "Computing…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val rate = data.successRate
                Text(
                    text = if (rate == null) "No finished downloads yet."
                    else "Success rate: ${(rate * 100).toInt()}% (${data.completed} of ${data.completed + data.failed})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                if (data.detectedPerDomain.isEmpty()) {
                    Text(
                        text = "No media detected yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    data.detectedPerDomain.take(5).forEach { (domain, count) ->
                        Text(
                            text = "$domain: $count",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                data.failuresByKind.forEach { (kind, count) ->
                    Text(
                        text = "$kind failures: $count",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Single-line rendering shared by the list; exportText owns the share format. */
private fun formatLine(event: ChayaEvent): String = when (event) {
    is ChayaEvent.MediaDetected ->
        "detected ${event.mimeType ?: "unknown-type"} via ${event.source}: ${event.url}"
    is ChayaEvent.DownloadStateChanged -> "task ${event.taskId}: ${event.from} -> ${event.to}"
    is ChayaEvent.DownloadFailed ->
        "task ${event.taskId} failed (${event.errorKind}, retryable=${event.retryable})"
    is ChayaEvent.PageLoaded -> "page: ${event.url}"
    is ChayaEvent.CaughtException -> "${event.tag}: ${event.message ?: "no message"}"
}

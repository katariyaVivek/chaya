package com.chaya.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chaya.app.ChayaApplication
import com.chaya.app.speech.SpeechModel
import com.chaya.app.ui.components.AppearanceDialog
import com.chaya.app.ui.components.ScreenHeader
import com.chaya.app.ui.player.SpeechModelsDialog
import com.chaya.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chaya's settings, in one place, opened from Home's top row: how it looks, the AI model for translation and
 * highlights, the speech models on the phone, and Diagnostics. The Library is only for what was saved.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenAiModel: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val app = LocalContext.current.applicationContext as ChayaApplication
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var appearanceOpen by remember { mutableStateOf(false) }
    var speechModelsOpen by remember { mutableStateOf(false) }

    SettingsContent(
        themeMode = themeMode,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onAppearance = { appearanceOpen = true },
        onAiModel = onOpenAiModel,
        onSpeechModels = { speechModelsOpen = true },
        onDiagnostics = onOpenDiagnostics,
    )

    if (appearanceOpen) {
        AppearanceDialog(current = themeMode, onSelect = onThemeModeChange, onDismiss = { appearanceOpen = false })
    }

    if (speechModelsOpen) {
        var fetched by remember { mutableStateOf(SpeechModel.entries.filter(app.speechModels::isFetched)) }
        SpeechModelsDialog(
            fetched = fetched,
            onDelete = { model ->
                scope.launch {
                    withContext(Dispatchers.IO) { app.speechModels.delete(model) }
                    fetched = fetched - model
                    snackbarHostState.showSnackbar("Deleted the ${model.label} model (${model.megabytes})")
                }
            },
            onDismiss = { speechModelsOpen = false },
        )
    }
}

/** The settings as rows, each a tonal circle with its icon, its name, and what it is for. */
@Composable
internal fun SettingsContent(
    themeMode: ThemeMode,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onAppearance: () -> Unit,
    onAiModel: () -> Unit,
    onSpeechModels: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Scaffold(
        topBar = { ScreenHeader("Settings", onBack = onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            SettingRow(Icons.Default.Contrast, "Appearance", themeMode.label, onAppearance)
            SettingRow(Icons.Default.AutoAwesome, "AI model", "Your own model, for translation and highlights", onAiModel)
            SettingRow(Icons.Default.GraphicEq, "Speech models", "The models on this phone that make subtitles", onSpeechModels)
            SettingRow(Icons.Default.Description, "Diagnostics", "What Chaya noted, to share when something goes wrong", onDiagnostics)
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

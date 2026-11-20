package com.chaya.app.ai

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.chaya.app.ChayaApplication
import kotlinx.coroutines.launch
import java.util.Locale

/** What *Test* or the model list last said: nothing yet, asking, or its answer in words. */
sealed class Checking {
    data object Idle : Checking()
    data object Asking : Checking()
    data class Said(val words: String, val worked: Boolean) : Checking()
}

/** What [AiModelContent] does when the person acts; [AiModelScreen] gives the real ones. */
class AiModelActions(
    val choose: (AiSettings) -> Unit = {},
    /** Keeps the key; why not, in words. */
    val saveKey: (service: String, key: String) -> String? = { _, _ -> null },
    val forgetKey: (service: String) -> Unit = {},
    val listModels: () -> Unit = {},
    val test: () -> Unit = {},
    val openPage: (String) -> Unit = {},
)

/** Settings › AI model, reached from the Library's ⋮. */
@Composable
fun AiModelScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ChayaApplication
    val keys = app.aiKeys
    val client = app.aiClient
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(keys.settings) }
    var keySaved by remember { mutableStateOf(settings.service?.let(keys::hasKey) ?: false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var checking by remember { mutableStateOf<Checking>(Checking.Idle) }

    fun spoken() = settings.service()?.spoken ?: "The service"

    AiModelContent(
        settings = settings,
        keySaved = keySaved,
        models = models,
        checking = checking,
        onNavigateBack = onNavigateBack,
        actions = AiModelActions(
            choose = { chosen ->
                if (chosen.service != settings.service) {
                    models = emptyList()
                    keySaved = chosen.service?.let(keys::hasKey) ?: false
                }
                settings = chosen
                keys.settings = chosen
                checking = Checking.Idle
            },
            saveKey = { service, key ->
                keys.setKey(service, key).also { if (it == null) keySaved = true }
            },
            forgetKey = { service ->
                keys.forgetKey(service)
                keySaved = false
            },
            listModels = {
                checking = Checking.Asking
                scope.launch {
                    checking = when (val endpoint = keys.endpoint(settings)) {
                        is AiResult.Failed -> Checking.Said(endpoint.error.message(spoken()), worked = false)
                        is AiResult.Done -> when (val listed = client.models(endpoint.value)) {
                            is AiResult.Failed -> Checking.Said(listed.error.message(spoken()), worked = false)
                            is AiResult.Done -> {
                                models = listed.value
                                if (listed.value.isEmpty()) Checking.Said("${spoken()} lists no models. Type the model's name.", worked = true)
                                else Checking.Idle
                            }
                        }
                    }
                }
            },
            test = {
                checking = Checking.Asking
                scope.launch {
                    val model = settings.model?.trim().orEmpty()
                    checking = when (val endpoint = keys.endpoint(settings)) {
                        is AiResult.Failed -> Checking.Said(endpoint.error.message(spoken()), worked = false)
                        is AiResult.Done -> when (val tested = client.test(endpoint.value, model)) {
                            is AiResult.Failed -> Checking.Said(tested.error.message(spoken()), worked = false)
                            is AiResult.Done -> Checking.Said("$model answered in ${seconds(tested.value)}.", worked = true)
                        }
                    }
                }
            },
            openPage = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        ),
    )
}

/** "1.2 s" for 1234 ms. */
internal fun seconds(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelContent(
    settings: AiSettings,
    keySaved: Boolean,
    models: List<String>,
    checking: Checking,
    onNavigateBack: () -> Unit,
    actions: AiModelActions,
) {
    val service = settings.service()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI model", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                // Above the keyboard, so the field being typed in scrolls into view instead of under it
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Translation and highlights use your own AI model: a service you have a key for, or a model on a PC " +
                    "in your home. Chaya sends a video's subtitles to it only when you ask for one of them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Service", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                AiService.ALL.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option.id == settings.service,
                                onClick = { actions.choose(AiSettings(service = option.id)) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.id == settings.service, onClick = null)
                        Text(option.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
            if (service != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ServiceFields(service, settings, keySaved, models, checking, actions)
            }
        }
    }
}

@Composable
private fun ServiceFields(
    service: AiService,
    settings: AiSettings,
    keySaved: Boolean,
    models: List<String>,
    checking: Checking,
    actions: AiModelActions,
) {
    if (service.local || service.id == AiService.CUSTOM) {
        var address by remember(service.id) { mutableStateOf(settings.address.orEmpty()) }
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                actions.choose(settings.copy(address = it.trim().ifEmpty { null }))
            },
            label = { Text("Address") },
            placeholder = { Text(service.address.ifEmpty { "https://…/v1" }) },
            supportingText = {
                Text(
                    if (service.local) "The PC's address on your Wi-Fi, with ${service.name.substringBefore(" on")}'s port."
                    else "Where it answers OpenAI's chat completions, ending before /chat/completions.",
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (service.key) KeyField(service, keySaved, actions)

    var model by remember(service.id) { mutableStateOf(settings.model.orEmpty()) }
    OutlinedTextField(
        value = model,
        onValueChange = {
            model = it
            actions.choose(settings.copy(model = it.trim().ifEmpty { null }))
        },
        label = { Text("Model") },
        placeholder = { Text(if (models.isEmpty()) "Its name, or list them" else "Pick one below, or type one") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (models.isNotEmpty()) {
        Column {
            models.forEach { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (name == settings.model) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            model = name
                            actions.choose(settings.copy(model = name))
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = actions.listModels, enabled = checking != Checking.Asking) { Text("List models") }
        Button(onClick = actions.test, enabled = checking != Checking.Asking && !settings.model.isNullOrBlank()) { Text("Test") }
    }
    when (checking) {
        Checking.Idle -> Unit
        Checking.Asking -> Text("Asking ${service.spoken}…", style = MaterialTheme.typography.bodyMedium)
        is Checking.Said -> Text(
            checking.words,
            style = MaterialTheme.typography.bodyMedium,
            color = if (checking.worked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun KeyField(service: AiService, keySaved: Boolean, actions: AiModelActions) {
    if (keySaved) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Key saved, encrypted on this phone",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { actions.forgetKey(service.id) }) { Text("Forget") }
        }
        return
    }
    var key by remember(service.id) { mutableStateOf("") }
    var refused by remember(service.id) { mutableStateOf<String?>(null) }
    OutlinedTextField(
        value = key,
        onValueChange = {
            key = it
            refused = null
        },
        label = { Text("${service.spoken.removePrefix("The ")} key".replaceFirstChar { it.uppercase() }) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        isError = refused != null,
        supportingText = { Text(refused ?: "Kept encrypted on this phone, never backed up, sent only to ${service.spoken}.") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { refused = actions.saveKey(service.id, key) }, enabled = key.isNotBlank()) { Text("Save key") }
        if (service.keysAt.isNotEmpty()) {
            TextButton(onClick = { actions.openPage(service.keysAt) }) { Text("Get a key") }
        }
    }
}

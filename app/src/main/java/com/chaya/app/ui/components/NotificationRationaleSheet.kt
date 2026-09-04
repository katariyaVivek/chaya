package com.chaya.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chaya.app.ui.theme.pressScale

/**
 * One-sentence rationale before the system notification permission dialog
 * (Phase 0.5). Matches DetectedMediaSheet's ModalBottomSheet register.
 * "Not now" still downloads — the sheet is an explanation, not a gate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationRationaleSheet(
    fileName: String,
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onNotNow,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Track this download?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Chaya shows progress for \"$fileName\" in a notification so you can follow it outside the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onAllow,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp).pressScale(0.98f),
            ) {
                Text("Allow", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onNotNow, modifier = Modifier.pressScale()) {
                Text("Not now", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

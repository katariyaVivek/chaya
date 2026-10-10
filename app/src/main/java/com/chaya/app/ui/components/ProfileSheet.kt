package com.chaya.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chaya.app.platform.Platform
import com.chaya.app.platform.ProfileMatch
import com.chaya.app.ui.theme.pressScale

/**
 * An Instagram or X account: save everything it has posted as one ZIP. Saving with the person's sign-in is
 * offered only when the browser here is signed in to that site ([signedIn]), and only ever on their tap; without
 * one, the site shows a visitor little or nothing, which the sheet says plainly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    profile: ProfileMatch,
    signedIn: Boolean,
    onSave: (useSignIn: Boolean) -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val site = profile.platform.displayName
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp, top = 4.dp)) {
            Text(
                text = "@${profile.username}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$site account",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Save every photo and video @${profile.username} has posted, as one ZIP in your Downloads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(20.dp))
            if (signedIn) {
                MainButton("Save all posts with my sign-in") { onSave(true) }
                Spacer(Modifier.height(8.dp))
                SecondButton("Try without signing in") { onSave(false) }
                Spacer(Modifier.height(12.dp))
                Note(
                    "Your sign-in lets $site show everything you can see there. Sites can limit accounts that " +
                        "download a lot, so Chaya goes at the site's own pace; a big account takes a while."
                )
            } else {
                MainButton("Try without signing in") { onSave(false) }
                Spacer(Modifier.height(8.dp))
                SecondButton("Sign in to $site first", onSignIn)
                Spacer(Modifier.height(12.dp))
                Note(visitorNote(profile.platform))
            }
        }
    }
}

/** What a site shows someone who is not signed in. */
private fun visitorNote(platform: Platform): String = when (platform) {
    Platform.TWITTER -> "X shows an account's posts only to signed-in people, so without a sign-in this " +
        "usually finds nothing. Sign in here, then come back to this account."
    else -> "Without a sign-in, ${platform.displayName} shows a visitor a few recent posts at most. Sign in " +
        "here, then come back to this account to save all of them."
}

@Composable
private fun MainButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(48.dp).pressScale(0.98f),
    ) {
        Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SecondButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(48.dp).pressScale(0.98f),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

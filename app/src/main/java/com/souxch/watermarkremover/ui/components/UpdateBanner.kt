package com.souxch.watermarkremover.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.souxch.watermarkremover.R
import com.souxch.watermarkremover.ui.UpdateState

/**
 * "A new version is available" card. One tap downloads the APK and opens the installer; since
 * every release shares the same signing key the update installs over the current version.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onInstall: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val update = state.available
    AnimatedVisibility(
        visible = update != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        if (update == null) return@AnimatedVisibility
        val context = LocalContext.current
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.update_available_title, update.version),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            if (update.sizeBytes > 0) stringResource(R.string.update_available_size, formatSize(context, update.sizeBytes))
                            else stringResource(R.string.update_available_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = state.downloadProgress == null) {
                        Icon(Icons.Default.Close, stringResource(R.string.update_later), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    state.downloadProgress != null -> {
                        LinearProgressIndicator(
                            progress = { state.downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth().padding(end = 12.dp).height(6.dp).clip(CircleShape),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.update_downloading, state.downloadProgress),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    state.needsInstallPermission -> {
                        Text(
                            stringResource(R.string.update_permission_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = onOpenPermissionSettings, shape = MaterialTheme.shapes.small) {
                            Text(stringResource(R.string.update_permission_button))
                        }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onInstall, shape = MaterialTheme.shapes.small) {
                            Text(stringResource(R.string.update_install))
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
                    }
                }
            }
        }
    }
}

package com.souxch.watermarkremover.ui.screens

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.souxch.watermarkremover.R
import com.souxch.watermarkremover.data.LibraryRepository
import com.souxch.watermarkremover.data.ProcessedVideo
import com.souxch.watermarkremover.ui.components.DetailRow
import com.souxch.watermarkremover.ui.components.SectionCard
import com.souxch.watermarkremover.ui.components.formatDuration
import com.souxch.watermarkremover.ui.components.formatSize
import com.souxch.watermarkremover.ui.components.methodLabel
import com.souxch.watermarkremover.ui.components.openVideo
import com.souxch.watermarkremover.ui.components.shareVideo
import com.souxch.watermarkremover.ui.theme.heroGradient

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.loading_video), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ExportingScreen(percent: Int, sourceName: String, onCancel: () -> Unit) {
    val animated by animateFloatAsState(targetValue = percent / 100f, animationSpec = tween(300), label = "progress")
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { if (percent <= 0) 0f else animated },
                modifier = Modifier.size(140.dp),
                strokeWidth = 10.dp,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            if (percent <= 0) {
                CircularProgressIndicator(Modifier.size(140.dp), strokeWidth = 10.dp)
            }
            Text(
                if (percent <= 0) "…" else "$percent %",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(sourceName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { if (percent <= 0) 0f else animated },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (percent <= 0) stringResource(R.string.export_preparing) else stringResource(R.string.export_keep_open),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
fun DoneScreen(
    item: ProcessedVideo,
    onAnother: () -> Unit,
    onOpenLibrary: () -> Unit,
    onDelete: (ProcessedVideo) -> Unit,
) {
    val context = LocalContext.current
    val uri = Uri.parse(item.uri)
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(heroGradient()).padding(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.export_done_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.export_done_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)) {
            VideoThumbnail(item, Modifier.fillMaxWidth().height(200.dp))
        }
        Spacer(Modifier.height(16.dp))

        SectionCard {
            Column {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                if (item.width > 0) DetailRow(stringResource(R.string.detail_resolution), "${item.width} × ${item.height}")
                DetailRow(stringResource(R.string.detail_duration), formatDuration(item.durationMs))
                DetailRow(stringResource(R.string.detail_size), formatSize(context, item.sizeBytes))
                DetailRow(stringResource(R.string.detail_method), methodLabel(context, item.method))
                DetailRow(stringResource(R.string.detail_zones), item.zoneCount.toString())
                if (item.sourceName.isNotBlank()) DetailRow(stringResource(R.string.detail_source), item.sourceName)
                DetailRow(stringResource(R.string.detail_location), "Movies/${LibraryRepository.ALBUM}")
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { openVideo(context, uri) }, modifier = Modifier.weight(1f).height(48.dp), shape = MaterialTheme.shapes.small) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open))
            }
            OutlinedButton(onClick = { shareVideo(context, uri) }, modifier = Modifier.weight(1f).height(48.dp), shape = MaterialTheme.shapes.small) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.share))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onOpenLibrary) {
                Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.go_to_library))
            }
            TextButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAnother, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.export_another))
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, item.displayName)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(item) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun ErrorScreen(message: String, canRetry: Boolean, onRetry: () -> Unit, onHome: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.export_failed_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.error_generic, message),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        if (canRetry) {
            Button(onClick = onRetry, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.export_retry)) }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = onHome) { Text(stringResource(R.string.back_home)) }
    }
}

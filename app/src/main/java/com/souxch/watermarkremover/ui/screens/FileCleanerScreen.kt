package com.souxch.watermarkremover.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.souxch.watermarkremover.R
import com.souxch.watermarkremover.cleaner.CleanFileKind
import com.souxch.watermarkremover.cleaner.FileSelection
import com.souxch.watermarkremover.cleaner.NativeCleanReport
import java.util.Locale

@Composable
fun FileCleanerScreen(
    selection: FileSelection,
    report: NativeCleanReport?,
    onClean: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.file_clean_title),
            subtitle = stringResource(R.string.file_clean_subtitle),
            icon = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
        )
        FileCard(selection)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Lock, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(stringResource(R.string.file_scope), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.file_scope_details), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (selection.kind == CleanFileKind.PDF || selection.kind == CleanFileKind.TIFF) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(R.string.file_limited_format), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (report != null) {
            ReportCard(report)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onClean,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            enabled = selection.kind != CleanFileKind.UNSUPPORTED,
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(Icons.Default.AutoAwesome, null)
            Spacer(Modifier.size(9.dp))
            Text(stringResource(R.string.file_clean_action), fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(50.dp), shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
fun FileCleaningScreen(selection: FileSelection, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(22.dp))
        Text(stringResource(R.string.file_cleaning), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.file_cleaning_message, selection.displayName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth(0.8f))
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
fun FileDoneScreen(
    selection: FileSelection,
    report: NativeCleanReport,
    onAnother: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (report.changed) Icons.Default.CheckCircle else Icons.Default.Info,
            null,
            Modifier.size(64.dp),
            tint = if (report.changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (report.changed) R.string.file_done_title else R.string.file_done_unchanged),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            selection.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(18.dp))
        ReportCard(report)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.file_save_location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.small) {
            Icon(Icons.Default.OpenInNew, null)
            Spacer(Modifier.size(9.dp))
            Text(stringResource(R.string.file_open))
        }
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().height(50.dp), shape = MaterialTheme.shapes.small) {
            Icon(Icons.Default.Share, null)
            Spacer(Modifier.size(9.dp))
            Text(stringResource(R.string.file_share))
        }
        OutlinedButton(onClick = onAnother, modifier = Modifier.fillMaxWidth().height(50.dp), shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.file_another))
        }
    }
}

@Composable
private fun FileCard(selection: FileSelection) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.size(50.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(Icons.Default.Description, null, Modifier.padding(13.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(selection.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${selection.kind.label} • ${formatBytes(selection.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReportCard(report: NativeCleanReport) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (report.warnings.isEmpty()) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (report.warnings.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Warning,
                    null,
                    Modifier.size(18.dp),
                    tint = if (report.warnings.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(8.dp))
                Text(report.summary, style = MaterialTheme.typography.titleSmall)
            }
            if (report.actions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                report.actions.take(5).forEach { action ->
                    Text("• $action", style = MaterialTheme.typography.bodySmall)
                }
            }
            report.warnings.take(3).forEach { warning ->
                Spacer(Modifier.height(5.dp))
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(42.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
            icon()
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBytes(size: Long): String {
    if (size <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = size.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toInt()} ${units[unit]}" else String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

/** Intent helpers kept here so the Android UI never exposes a local file path. */
fun openCleanedFile(context: android.content.Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
}

fun shareCleanedFile(context: android.content.Context, uri: Uri, mimeType: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ContextCompat.startActivity(context, Intent.createChooser(send, null), null)
}

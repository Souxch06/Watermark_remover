package com.souxch.watermarkremover.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.souxch.watermarkremover.R
import com.souxch.watermarkremover.data.ProcessedVideo
import com.souxch.watermarkremover.ui.components.Pill
import com.souxch.watermarkremover.ui.components.formatDuration
import com.souxch.watermarkremover.ui.components.formatRelativeDate
import com.souxch.watermarkremover.ui.components.formatSize
import com.souxch.watermarkremover.ui.components.methodLabel
import com.souxch.watermarkremover.ui.components.openVideo
import com.souxch.watermarkremover.ui.components.shareVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** List of all processed videos with play / share / rename / delete actions. */
@Composable
fun LibraryScreen(
    videos: List<ProcessedVideo>,
    loaded: Boolean,
    onDelete: (ProcessedVideo) -> Unit,
    onRename: (ProcessedVideo, String) -> Unit,
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<ProcessedVideo?>(null) }
    var pendingRename by remember { mutableStateOf<ProcessedVideo?>(null) }

    if (loaded && videos.isEmpty()) {
        EmptyLibrary()
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp)) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.library_count, videos.size, formatSize(context, videos.sumOf { it.sizeBytes })),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(videos, key = { it.id }) { item ->
            LibraryCard(
                item = item,
                onPlay = { openVideo(context, Uri.parse(item.uri)) },
                onShare = { shareVideo(context, Uri.parse(item.uri)) },
                onRename = { pendingRename = item },
                onDelete = { pendingDelete = item },
            )
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, item.displayName)) },
            confirmButton = {
                TextButton(onClick = { onDelete(item); pendingDelete = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pendingRename?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.displayName.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.rename_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rename_label)) },
                    suffix = { Text(".mp4") },
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onRename(item, name); pendingRename = null }) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun EmptyLibrary() {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.VideoLibrary, null, Modifier.size(46.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.library_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LibraryCard(
    item: ProcessedVideo,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoThumbnail(item, Modifier.width(132.dp).height(84.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(
                        formatRelativeDate(context, item.createdAtMillis),
                        if (item.width > 0) "${item.width}×${item.height}" else null,
                        formatSize(context, item.sizeBytes),
                    ).filterNotNull().filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(methodLabel(context, item.method), container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (item.zoneCount > 1) {
                        Pill(
                            stringResource(R.string.zones_badge, item.zoneCount),
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            content = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open)) },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                        onClick = { menuOpen = false; onPlay() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** Thumbnail with a play glyph and the duration pill; falls back to an icon when missing. */
@Composable
fun VideoThumbnail(item: ProcessedVideo, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = item.thumbnailPath) {
        value = withContext(Dispatchers.IO) {
            item.thumbnailPath?.let { path ->
                runCatching { if (File(path).exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null }.getOrNull()
            }
        }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF24107A), modifier = Modifier.size(20.dp))
        }
        if (item.durationMs > 0) {
            Text(
                formatDuration(item.durationMs),
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

package com.souxch.watermarkremover.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.souxch.watermarkremover.R
import com.souxch.watermarkremover.model.RemovalMethod
import com.souxch.watermarkremover.model.VideoInfo
import com.souxch.watermarkremover.processing.WatermarkShader
import com.souxch.watermarkremover.ui.EditorState
import com.souxch.watermarkremover.ui.EditorViewModel
import com.souxch.watermarkremover.ui.components.OverlayChip
import com.souxch.watermarkremover.ui.components.Pill
import com.souxch.watermarkremover.ui.components.SectionCard
import com.souxch.watermarkremover.ui.components.ZoneOverlay
import com.souxch.watermarkremover.ui.components.formatDuration

@Composable
fun EditorScreen(info: VideoInfo, frame: Bitmap?, state: EditorState, vm: EditorViewModel) {
    val zones = state.zones
    val settings = state.settings
    // While the user holds the frame we show the original ("peek"), like photo editors do.
    var peeking by remember { mutableStateOf(false) }

    // Android 7-9 need WRITE_EXTERNAL_STORAGE to save into the public Movies folder.
    val context = LocalContext.current
    val permissionDenied = stringResource(R.string.permission_needed)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startExport() else vm.showMessage(permissionDenied)
    }
    val onExport = {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.startExport()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.editor_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        info.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = vm::goHome) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            },
            actions = {
                Pill(
                    "${info.displayWidth}×${info.displayHeight} • ${formatDuration(info.durationMs)}",
                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            // ----- Frame + overlay -----
            val showProcessed = state.showAfter && !peeking && settings.method.usesShader
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(Color.Black).aspectRatio(info.aspectRatio),
                contentAlignment = Alignment.Center,
            ) {
                // The original frame is always drawn underneath, so the video never disappears
                // (a failed / pending render must not leave the user with a black box).
                if (frame != null) {
                    Image(
                        frame.asImageBitmap(),
                        stringResource(R.string.content_description_video_preview),
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                }
                val processed = state.previewFrame
                Crossfade(targetState = if (showProcessed) processed else null, label = "preview") { bmp ->
                    if (bmp != null && !bmp.isRecycled) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                }
                // Crop method: darken what will be removed.
                if (settings.method == RemovalMethod.CROP) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                }
                ZoneOverlay(
                    zones = zones,
                    selectedId = state.selectedZoneId,
                    onSelect = vm::selectZone,
                    onMove = vm::moveZone,
                    onResize = vm::resizeZone,
                    onPeek = { peeking = it },
                )
                // Status chip (top-left)
                val label = when {
                    !settings.method.usesShader -> stringResource(R.string.preview_crop_hint)
                    peeking || !state.showAfter -> stringResource(R.string.preview_before)
                    else -> stringResource(R.string.preview_after)
                }
                OverlayChip(
                    label,
                    Modifier.align(Alignment.TopStart).padding(10.dp),
                    leading = if (state.previewLoading && showProcessed) {
                        { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White) }
                    } else null,
                )
                // Before / after switch (top-right), only for the shader methods.
                if (settings.method.usesShader) {
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape).padding(3.dp),
                    ) {
                        ToggleSegment(stringResource(R.string.preview_before), Icons.Outlined.VisibilityOff, selected = !state.showAfter) { vm.setShowAfter(false) }
                        ToggleSegment(stringResource(R.string.preview_after), Icons.Outlined.Visibility, selected = state.showAfter) { vm.setShowAfter(true) }
                    }
                }
            }
            Text(
                stringResource(R.string.editor_instructions),
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ----- Zones -----
            SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.zones_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (zones.isNotEmpty()) {
                            IconButton(onClick = vm::removeSelectedZone) {
                                Icon(Icons.Default.Delete, stringResource(R.string.remove_zone), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        zones.forEach { z ->
                            FilterChip(
                                selected = z.id == state.selectedZoneId,
                                onClick = { vm.selectZone(z.id) },
                                label = { Text(stringResource(R.string.zone_label, z.id)) },
                                leadingIcon = if (z.id == state.selectedZoneId) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                                } else null,
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                        if (zones.size < WatermarkShader.MAX_ZONES) {
                            FilterChip(
                                selected = false,
                                onClick = vm::addZone,
                                label = { Text(stringResource(R.string.add_zone)) },
                                leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) },
                                shape = CircleShape,
                            )
                        }
                    }
                    if (zones.isEmpty()) {
                        Text(stringResource(R.string.no_zone), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    // Watermark analysis status (reconstruction method only).
                    if (settings.method == RemovalMethod.INPAINT && zones.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        AnalysisStatus(state)
                    }
                }
            }

            // ----- Method -----
            SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column {
                    Text(stringResource(R.string.method_label), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MethodTile(RemovalMethod.INPAINT, Icons.Default.AutoAwesome, R.string.method_inpaint_short, settings.method, vm::setMethod, Modifier.weight(1f))
                        MethodTile(RemovalMethod.BLUR, Icons.Default.BlurOn, R.string.method_blur, settings.method, vm::setMethod, Modifier.weight(1f))
                        MethodTile(RemovalMethod.PIXELATE, Icons.Default.GridOn, R.string.method_pixelate, settings.method, vm::setMethod, Modifier.weight(1f))
                        MethodTile(RemovalMethod.CROP, Icons.Default.Crop, R.string.method_crop, settings.method, vm::setMethod, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Crossfade(settings.method, label = "method-desc") { method ->
                        Text(
                            stringResource(
                                when (method) {
                                    RemovalMethod.INPAINT -> R.string.method_inpaint_desc
                                    RemovalMethod.BLUR -> R.string.method_blur_desc
                                    RemovalMethod.PIXELATE -> R.string.method_pixelate_desc
                                    RemovalMethod.CROP -> R.string.method_crop_desc
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(settings.method == RemovalMethod.CROP && zones.size > 1) {
                        Text(
                            stringResource(R.string.crop_needs_single_zone),
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            // Quality is automatic (see ExportQuality.MAXIMUM): just tell the user.
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.HighQuality, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.quality_auto_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // ----- Pinned call to action -----
        Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Button(
                    onClick = onExport,
                    enabled = zones.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.export), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** One half of the floating Before / After switch drawn over the preview. */
@Composable
private fun ToggleSegment(text: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (selected) Color(0xFF24107A) else Color.White)
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) Color(0xFF24107A) else Color.White)
    }
}

@Composable
private fun AnalysisStatus(state: EditorState) {
    val progress = state.analysisProgress
    val layer = state.layer
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                progress != null -> {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.analysis_running, progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
                layer != null && layer.hasWatermark -> {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.analysis_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.analysisFoundNothing -> {
                    Text(
                        stringResource(R.string.analysis_not_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.analysis_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Square-ish selectable tile for one removal method. */
@Composable
private fun MethodTile(
    method: RemovalMethod,
    icon: ImageVector,
    title: Int,
    current: RemovalMethod,
    onSelect: (RemovalMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = current == method
    Surface(
        modifier = modifier.selectable(selected = selected, onClick = { onSelect(method) }),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            Modifier.padding(PaddingValues(horizontal = 6.dp, vertical = 12.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon, null, Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(title),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

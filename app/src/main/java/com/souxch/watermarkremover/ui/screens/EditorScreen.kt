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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
                Pill("${info.displayWidth}×${info.displayHeight} • ${formatDuration(info.durationMs)}", container = MaterialTheme.colorScheme.surfaceContainerHigh, content = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ----- Frame + overlay -----
            val showProcessed = state.showAfter && !peeking && settings.method.usesShader
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(Color.Black).aspectRatio(info.aspectRatio),
                contentAlignment = Alignment.Center,
            ) {
                val displayed = if (showProcessed) (state.previewFrame ?: frame) else frame
                Crossfade(targetState = displayed, label = "preview") { bmp ->
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(),
                            stringResource(R.string.content_description_video_preview),
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
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
                Row(Modifier.align(Alignment.TopStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label = when {
                        !settings.method.usesShader -> stringResource(R.string.preview_crop_hint)
                        peeking || !state.showAfter -> stringResource(R.string.preview_before)
                        else -> stringResource(R.string.preview_after)
                    }
                    Box(Modifier.clip(MaterialTheme.shapes.extraSmall).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                    if (state.previewLoading && showProcessed) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                }
            }
            Text(
                stringResource(R.string.editor_instructions),
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ----- Before / after toggle -----
            if (settings.method.usesShader) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    SegmentedButton(
                        selected = !state.showAfter,
                        onClick = { vm.setShowAfter(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.preview_before)) }
                    SegmentedButton(
                        selected = state.showAfter,
                        onClick = { vm.setShowAfter(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { Icon(Icons.Default.Visibility, null, Modifier.size(16.dp)) },
                    ) { Text(stringResource(R.string.preview_after)) }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ----- Zones -----
            SectionCard(Modifier.padding(horizontal = 16.dp)) {
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
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            )
                        }
                        if (zones.size < WatermarkShader.MAX_ZONES) {
                            FilterChip(
                                selected = false,
                                onClick = vm::addZone,
                                label = { Text(stringResource(R.string.add_zone)) },
                                leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                    if (zones.isEmpty()) {
                        Text(stringResource(R.string.no_zone), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ----- Method -----
            SectionCard(Modifier.padding(horizontal = 16.dp)) {
                Column {
                    Text(stringResource(R.string.method_label), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    MethodOption(RemovalMethod.INPAINT, Icons.Default.AutoFixHigh, R.string.method_inpaint, R.string.method_inpaint_desc, settings.method, vm::setMethod)
                    MethodOption(RemovalMethod.BLUR, Icons.Default.BlurOn, R.string.method_blur, R.string.method_blur_desc, settings.method, vm::setMethod)
                    MethodOption(RemovalMethod.PIXELATE, Icons.Default.Grid4x4, R.string.method_pixelate, R.string.method_pixelate_desc, settings.method, vm::setMethod)
                    MethodOption(RemovalMethod.CROP, Icons.Default.Crop, R.string.method_crop, R.string.method_crop_desc, settings.method, vm::setMethod)
                    AnimatedVisibility(settings.method == RemovalMethod.CROP && zones.size > 1) {
                        Text(stringResource(R.string.crop_needs_single_zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    AnimatedVisibility(
                        visible = settings.method.usesShader,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            LabeledSlider(stringResource(R.string.strength_label), settings.strength, vm::setStrength)
                            LabeledSlider(stringResource(R.string.feather_label), settings.feather, vm::setFeather)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onExport,
                enabled = zones.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(Icons.Default.AutoFixHigh, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text("${(value * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value = value, onValueChange = onChange)
}

@Composable
private fun MethodOption(
    method: RemovalMethod,
    icon: ImageVector,
    title: Int,
    desc: Int,
    current: RemovalMethod,
    onSelect: (RemovalMethod) -> Unit,
) {
    val selected = current == method
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .selectable(selected = selected, onClick = { onSelect(method) })
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

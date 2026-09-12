package com.souxch.watermarkremover.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.souxch.watermarkremover.BuildConfig
import com.souxch.watermarkremover.R
import com.souxch.watermarkremover.data.ProcessedVideo
import com.souxch.watermarkremover.ui.UpdateState
import com.souxch.watermarkremover.ui.components.SectionCard
import com.souxch.watermarkremover.ui.components.SectionHeader
import com.souxch.watermarkremover.ui.components.StepRow
import com.souxch.watermarkremover.ui.components.UpdateBanner
import com.souxch.watermarkremover.ui.theme.brandGradient

@Composable
fun HomeScreen(
    recent: List<ProcessedVideo>,
    update: UpdateState,
    onPick: (Uri) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenItem: (ProcessedVideo) -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    onDismissUpdate: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPick) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // App title row
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(brandGradient()), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        }

        UpdateBanner(
            state = update,
            onInstall = onInstallUpdate,
            onOpenPermissionSettings = onOpenInstallPermission,
            onDismiss = onDismissUpdate,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )

        // Hero card
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(MaterialTheme.shapes.large).background(brandGradient()),
        ) {
            // Soft decorative discs, like the icon.
            Box(Modifier.size(220.dp).offset(x = 210.dp, y = (-110).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)))
            Box(Modifier.size(160.dp).offset(x = (-60).dp, y = 150.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.10f)))
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                )
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = pick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF4A31B8)),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Icon(Icons.Default.VideoLibrary, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.pick_video), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pick_video_hint), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }

        // Recent exports strip
        if (recent.isNotEmpty()) {
            SectionHeader(
                stringResource(R.string.recent_title),
                Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp),
                trailing = {
                    TextButton(onClick = onOpenLibrary) {
                        Text(stringResource(R.string.see_all))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
                    }
                },
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent.take(8), key = { it.id }) { item ->
                    Column(Modifier.width(156.dp).clip(MaterialTheme.shapes.small).clickable { onOpenItem(item) }) {
                        VideoThumbnail(item, Modifier.fillMaxWidth().height(100.dp))
                        Text(
                            item.displayName,
                            Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // How it works
        SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column {
                Text(stringResource(R.string.how_it_works), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                StepRow(1, Icons.Default.VideoLibrary, stringResource(R.string.step1_title), stringResource(R.string.step1_desc))
                StepRow(2, Icons.Default.CropFree, stringResource(R.string.step2_title), stringResource(R.string.step2_desc))
                StepRow(3, Icons.Default.SaveAlt, stringResource(R.string.step3_title), stringResource(R.string.step3_desc), last = true)
            }
        }

        // Privacy + legal
        SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), contentPadding = PaddingValues(16.dp)) {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.about_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.legal_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCheckUpdate, enabled = !update.checking) {
                if (update.checking) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.check_updates))
            }
        }
        Spacer(Modifier.height(96.dp)) // room for the bottom bar
    }
}

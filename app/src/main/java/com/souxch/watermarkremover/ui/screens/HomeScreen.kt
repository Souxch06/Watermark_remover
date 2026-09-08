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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.unit.dp
import com.souxch.watermarkremover.BuildConfig
import com.souxch.watermarkremover.R
import com.souxch.watermarkremover.data.ProcessedVideo
import com.souxch.watermarkremover.ui.UpdateState
import com.souxch.watermarkremover.ui.components.SectionCard
import com.souxch.watermarkremover.ui.components.UpdateBanner
import com.souxch.watermarkremover.ui.components.StepRow
import com.souxch.watermarkremover.ui.theme.heroGradient

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
        UpdateBanner(
            state = update,
            onInstall = onInstallUpdate,
            onOpenPermissionSettings = onOpenInstallPermission,
            onDismiss = onDismissUpdate,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        // Hero header
        Box(
            Modifier.fillMaxWidth().padding(16.dp).clip(MaterialTheme.shapes.large).background(heroGradient()).padding(24.dp),
        ) {
            Column {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.AutoFixHigh, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f))
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = pick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(Icons.Default.VideoLibrary, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pick_video), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.pick_video_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
        }

        // Recent exports strip
        if (recent.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.recent_title), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onOpenLibrary) {
                    Text(stringResource(R.string.see_all))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent.take(8), key = { it.id }) { item ->
                    Box(Modifier.width(150.dp).clickable { onOpenItem(item) }) {
                        VideoThumbnail(item, Modifier.fillMaxWidth().height(96.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // How it works
        SectionCard(Modifier.padding(horizontal = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.how_it_works), style = MaterialTheme.typography.titleMedium)
                StepRow(1, Icons.Default.VideoLibrary, stringResource(R.string.step1_title), stringResource(R.string.step1_desc))
                StepRow(2, Icons.Default.CropFree, stringResource(R.string.step2_title), stringResource(R.string.step2_desc))
                StepRow(3, Icons.Default.SaveAlt, stringResource(R.string.step3_title), stringResource(R.string.step3_desc))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.about_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(R.string.legal_note),
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                Modifier.padding(start = 8.dp),
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

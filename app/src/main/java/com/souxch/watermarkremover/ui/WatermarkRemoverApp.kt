package com.souxch.watermarkremover.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.souxch.watermarkremover.R
import com.souxch.watermarkremover.ui.components.openVideo
import com.souxch.watermarkremover.ui.screens.DoneScreen
import com.souxch.watermarkremover.ui.screens.EditorScreen
import com.souxch.watermarkremover.ui.screens.ErrorScreen
import com.souxch.watermarkremover.ui.screens.ExportingScreen
import com.souxch.watermarkremover.ui.screens.HomeScreen
import com.souxch.watermarkremover.ui.screens.LibraryScreen
import com.souxch.watermarkremover.ui.screens.LoadingScreen

@Composable
fun WatermarkRemoverApp(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val deletedText = stringResource(R.string.snack_deleted)
    val renamedText = stringResource(R.string.snack_renamed)
    val upToDateText = stringResource(R.string.snack_up_to_date)
    val updateFailedText = stringResource(R.string.snack_update_failed)
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbar.showSnackbar(
                when (msg) {
                    UiMessage.Deleted -> deletedText
                    UiMessage.Renamed -> renamedText
                    UiMessage.UpToDate -> upToDateText
                    UiMessage.UpdateDownloadFailed -> updateFailedText
                    is UiMessage.Error -> msg.text
                },
            )
        }
    }

    // System back: editor/done/error -> main; exporting -> cancel; library tab -> home tab.
    val screen = state.screen
    BackHandler(enabled = screen !is Screen.Main || state.tab != Tab.HOME) {
        when (screen) {
            is Screen.Exporting -> viewModel.cancelExport()
            is Screen.Main -> viewModel.selectTab(Tab.HOME)
            else -> viewModel.goHome()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (screen is Screen.Main) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = state.tab == Tab.HOME,
                        onClick = { viewModel.selectTab(Tab.HOME) },
                        icon = { Icon(if (state.tab == Tab.HOME) Icons.Filled.Home else Icons.Outlined.Home, null) },
                        label = { Text(stringResource(R.string.tab_home)) },
                    )
                    NavigationBarItem(
                        selected = state.tab == Tab.LIBRARY,
                        onClick = { viewModel.selectTab(Tab.LIBRARY) },
                        icon = {
                            BadgedBox(badge = { if (state.library.isNotEmpty()) Badge { Text(state.library.size.toString()) } }) {
                                Icon(if (state.tab == Tab.LIBRARY) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary, null)
                            }
                        },
                        label = { Text(stringResource(R.string.tab_library)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 20 }) togetherWith fadeOut()
                },
                contentKey = { it::class },
                label = "screen",
            ) { target ->
                when (target) {
                    Screen.Main -> AnimatedContent(
                        targetState = state.tab,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "tab",
                    ) { tab ->
                        when (tab) {
                            Tab.HOME -> HomeScreen(
                                recent = state.library,
                                update = state.update,
                                onPick = viewModel::openVideo,
                                onOpenLibrary = { viewModel.selectTab(Tab.LIBRARY) },
                                onOpenItem = { openVideo(context, Uri.parse(it.uri)) },
                                onInstallUpdate = viewModel::installUpdate,
                                onOpenInstallPermission = viewModel::openInstallPermissionSettings,
                                onDismissUpdate = viewModel::dismissUpdate,
                                onCheckUpdate = { viewModel.checkForUpdate() },
                            )
                            Tab.LIBRARY -> LibraryScreen(
                                videos = state.library,
                                loaded = state.libraryLoaded,
                                onDelete = viewModel::deleteFromLibrary,
                                onRename = viewModel::renameInLibrary,
                            )
                        }
                    }
                    Screen.Loading -> LoadingScreen()
                    is Screen.Editor -> EditorScreen(target.info, target.frame, state, viewModel)
                    is Screen.Exporting -> ExportingScreen(target.percent, target.info.displayName, onCancel = viewModel::cancelExport)
                    is Screen.Done -> DoneScreen(
                        item = target.item,
                        onAnother = viewModel::goHome,
                        onOpenLibrary = viewModel::goLibrary,
                        onDelete = viewModel::deleteFromLibrary,
                    )
                    is Screen.Error -> ErrorScreen(
                        message = target.message,
                        canRetry = target.info != null,
                        onRetry = { target.info?.let(viewModel::reopenEditor) },
                        onHome = viewModel::goHome,
                    )
                }
            }
        }
    }
}

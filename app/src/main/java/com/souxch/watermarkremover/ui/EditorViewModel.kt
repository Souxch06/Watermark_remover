package com.souxch.watermarkremover.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.souxch.watermarkremover.BuildConfig
import com.souxch.watermarkremover.data.AppUpdate
import com.souxch.watermarkremover.data.LibraryRepository
import com.souxch.watermarkremover.data.UpdateChecker
import com.souxch.watermarkremover.data.UpdateInstaller
import com.souxch.watermarkremover.data.ProcessedVideo
import com.souxch.watermarkremover.model.Corner
import com.souxch.watermarkremover.model.RemovalMethod
import com.souxch.watermarkremover.model.RemovalSettings
import com.souxch.watermarkremover.model.VideoInfo
import com.souxch.watermarkremover.model.WatermarkZone
import com.souxch.watermarkremover.processing.ExportEvent
import com.souxch.watermarkremover.processing.PreviewRenderer
import com.souxch.watermarkremover.processing.VideoExporter
import com.souxch.watermarkremover.processing.VideoRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which top-level destination is shown. */
enum class Tab { HOME, LIBRARY }

sealed interface Screen {
    /** Tabs: pick a video / browse processed videos. */
    data object Main : Screen
    data object Loading : Screen
    data class Editor(val info: VideoInfo, val frame: Bitmap?) : Screen
    data class Exporting(val info: VideoInfo, val percent: Int) : Screen
    data class Done(val item: ProcessedVideo) : Screen
    data class Error(val message: String, val info: VideoInfo?) : Screen
}

/** One-off UI messages (snackbars). */
sealed interface UiMessage {
    data object Deleted : UiMessage
    data object Renamed : UiMessage
    data object UpToDate : UiMessage
    data object UpdateDownloadFailed : UiMessage
    data class Error(val text: String) : UiMessage
}

/** State of the in-app update banner. */
data class UpdateState(
    val available: AppUpdate? = null,
    val checking: Boolean = false,
    /** 0..100 while downloading, null otherwise. */
    val downloadProgress: Int? = null,
    /** Android 8+: the user must allow this app to install packages first. */
    val needsInstallPermission: Boolean = false,
)

data class EditorState(
    val screen: Screen = Screen.Main,
    val tab: Tab = Tab.HOME,
    val zones: List<WatermarkZone> = listOf(WatermarkZone(1, WatermarkZone.DEFAULT_RECT)),
    val selectedZoneId: Int = 1,
    val settings: RemovalSettings = RemovalSettings(),
    /** Rendered "after" frame for the current zones/settings; null while computing. */
    val previewFrame: Bitmap? = null,
    val previewLoading: Boolean = false,
    /** true = show processed frame, false = show original (before/after toggle). */
    val showAfter: Boolean = true,
    val library: List<ProcessedVideo> = emptyList(),
    val libraryLoaded: Boolean = false,
    val update: UpdateState = UpdateState(),
)

@OptIn(FlowPreview::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = VideoRepository(app)
    private val exporter = VideoExporter(app)
    private val library = LibraryRepository(app)
    private val previewRenderer = PreviewRenderer()
    private val updateChecker = UpdateChecker(app, BuildConfig.VERSION_NAME)
    private val updateInstaller = UpdateInstaller(app)

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

    private var exportJob: Job? = null
    private var previewJob: Job? = null
    private var nextZoneId = 2

    init {
        viewModelScope.launch { library.items.collect { items -> _state.update { it.copy(library = items) } } }
        checkForUpdate(force = false, manual = false)
        viewModelScope.launch {
            runCatching { library.refresh() }
            _state.update { it.copy(libraryLoaded = true) }
        }
        // Re-render the "after" preview whenever zones or settings change, debounced so dragging
        // a handle stays smooth (the overlay itself is drawn synchronously by Compose).
        viewModelScope.launch {
            _state.map { PreviewKey(it.screen as? Screen.Editor, it.zones, it.settings) }
                .distinctUntilChanged()
                .debounce(120)
                .collect { key -> renderPreview(key) }
        }
    }

    private data class PreviewKey(val editor: Screen.Editor?, val zones: List<WatermarkZone>, val settings: RemovalSettings)

    private fun renderPreview(key: PreviewKey) {
        previewJob?.cancel()
        val frame = key.editor?.frame ?: run {
            _state.update { it.copy(previewFrame = null, previewLoading = false) }
            return
        }
        _state.update { it.copy(previewLoading = true) }
        previewJob = viewModelScope.launch {
            val rendered = runCatching { previewRenderer.render(frame, key.zones, key.settings) }.getOrNull()
            _state.update { s ->
                // Ignore stale results if the editor frame changed meanwhile.
                if ((s.screen as? Screen.Editor)?.frame !== frame) s
                else s.copy(previewFrame = rendered, previewLoading = false)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------------------------------

    fun selectTab(tab: Tab) = _state.update { it.copy(tab = tab) }

    fun showMessage(text: String) {
        messages.tryEmit(UiMessage.Error(text))
    }

    fun goHome() = _state.update { it.copy(screen = Screen.Main, previewFrame = null) }

    fun goLibrary() = _state.update { it.copy(screen = Screen.Main, tab = Tab.LIBRARY, previewFrame = null) }

    // ------------------------------------------------------------------------------------------
    // Opening & editing
    // ------------------------------------------------------------------------------------------

    fun openVideo(uri: Uri) {
        _state.update { it.copy(screen = Screen.Loading) }
        viewModelScope.launch {
            try {
                val info = repository.readInfo(uri)
                val frame = repository.loadFrame(uri, (info.durationMs * 1000L) / 3)
                nextZoneId = 2
                _state.update {
                    it.copy(
                        screen = Screen.Editor(info, frame),
                        zones = listOf(WatermarkZone(1, WatermarkZone.DEFAULT_RECT)),
                        selectedZoneId = 1,
                        previewFrame = null,
                        showAfter = true,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(screen = Screen.Error(e.localizedMessage ?: e.javaClass.simpleName, null)) }
            }
        }
    }

    fun selectZone(id: Int) = _state.update { it.copy(selectedZoneId = id) }

    fun addZone() = _state.update { s ->
        val zone = WatermarkZone(nextZoneId++, WatermarkZone.ADDITIONAL_RECT)
        s.copy(zones = s.zones + zone, selectedZoneId = zone.id)
    }

    fun removeSelectedZone() = _state.update { s ->
        val remaining = s.zones.filter { it.id != s.selectedZoneId }
        s.copy(zones = remaining, selectedZoneId = remaining.lastOrNull()?.id ?: -1)
    }

    fun moveZone(id: Int, dx: Float, dy: Float) = _state.update { s ->
        s.copy(zones = s.zones.map { if (it.id == id) it.copy(rect = it.rect.translated(dx, dy)) else it })
    }

    fun resizeZone(id: Int, corner: Corner, dx: Float, dy: Float) = _state.update { s ->
        s.copy(zones = s.zones.map { if (it.id == id) it.copy(rect = it.rect.resized(corner, dx, dy)) else it })
    }

    fun setMethod(method: RemovalMethod) = _state.update { it.copy(settings = it.settings.copy(method = method)) }
    fun setShowAfter(after: Boolean) = _state.update { it.copy(showAfter = after) }

    // ------------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------------

    fun startExport() {
        val info = (_state.value.screen as? Screen.Editor)?.info ?: return
        val zones = _state.value.zones
        if (zones.isEmpty()) return
        val settings = _state.value.settings
        val output = repository.newExportFile()
        _state.update { it.copy(screen = Screen.Exporting(info, 0)) }
        exportJob = viewModelScope.launch {
            exporter.export(info, zones, settings, output).collect { event ->
                when (event) {
                    is ExportEvent.Progress -> _state.update { it.copy(screen = Screen.Exporting(info, event.percent)) }
                    is ExportEvent.Failed -> _state.update {
                        it.copy(screen = Screen.Error(event.error.localizedMessage ?: event.error.javaClass.simpleName, info))
                    }
                    is ExportEvent.Done -> try {
                        val saved = library.saveExport(event.file, info, settings.method, zones.size)
                        _state.update { it.copy(screen = Screen.Done(saved)) }
                    } catch (e: Exception) {
                        _state.update { it.copy(screen = Screen.Error(e.localizedMessage ?: "storage", info)) }
                    }
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        val info = (_state.value.screen as? Screen.Exporting)?.info
        if (info != null) reopenEditor(info) else goHome()
    }

    fun reopenEditor(info: VideoInfo) {
        _state.update { it.copy(screen = Screen.Loading) }
        viewModelScope.launch {
            val frame = repository.loadFrame(info.uri, (info.durationMs * 1000L) / 3)
            _state.update { it.copy(screen = Screen.Editor(info, frame), previewFrame = null) }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Updates
    // ------------------------------------------------------------------------------------------

    fun checkForUpdate(force: Boolean = true, manual: Boolean = true) {
        if (_state.value.update.checking) return
        _state.update { it.copy(update = it.update.copy(checking = true)) }
        viewModelScope.launch {
            val found = updateChecker.check(force)
            val show = found != null && (manual || !updateChecker.isDismissed(found.version))
            _state.update { it.copy(update = it.update.copy(checking = false, available = if (show) found else null)) }
            if (manual && found == null) messages.tryEmit(UiMessage.UpToDate)
        }
    }

    fun dismissUpdate() {
        _state.value.update.available?.let { updateChecker.dismiss(it.version) }
        _state.update { it.copy(update = it.update.copy(available = null, needsInstallPermission = false)) }
    }

    /** Called when the user comes back from the "install unknown apps" settings screen. */
    fun onResumed() {
        if (_state.value.update.needsInstallPermission && updateInstaller.canInstallPackages()) {
            _state.update { it.copy(update = it.update.copy(needsInstallPermission = false)) }
            installUpdate()
        }
    }

    fun openInstallPermissionSettings() = updateInstaller.openInstallPermissionSettings()

    fun installUpdate() {
        val update = _state.value.update.available ?: return
        if (_state.value.update.downloadProgress != null) return
        if (!updateInstaller.canInstallPackages()) {
            _state.update { it.copy(update = it.update.copy(needsInstallPermission = true)) }
            return
        }
        _state.update { it.copy(update = it.update.copy(downloadProgress = 0)) }
        updateInstaller.downloadAndInstall(
            update,
            onProgress = { p -> _state.update { it.copy(update = it.update.copy(downloadProgress = p)) } },
            onDone = { ok ->
                _state.update { it.copy(update = it.update.copy(downloadProgress = null)) }
                if (!ok) {
                    messages.tryEmit(UiMessage.UpdateDownloadFailed)
                    updateInstaller.openInBrowser(update)
                }
            },
        )
    }

    override fun onCleared() {
        updateInstaller.unregister()
        super.onCleared()
    }

    // ------------------------------------------------------------------------------------------
    // Library
    // ------------------------------------------------------------------------------------------

    fun refreshLibrary() = viewModelScope.launch { runCatching { library.refresh() } }

    fun deleteFromLibrary(item: ProcessedVideo) = viewModelScope.launch {
        runCatching { library.delete(item) }
            .onSuccess {
                messages.tryEmit(UiMessage.Deleted)
                // If the user deletes the video from the "Done" screen, go back to the library.
                if ((_state.value.screen as? Screen.Done)?.item?.id == item.id) goLibrary()
            }
            .onFailure { messages.tryEmit(UiMessage.Error(it.localizedMessage ?: "delete")) }
    }

    fun renameInLibrary(item: ProcessedVideo, newName: String) = viewModelScope.launch {
        runCatching { library.rename(item, newName) }
            .onSuccess { messages.tryEmit(UiMessage.Renamed) }
            .onFailure { messages.tryEmit(UiMessage.Error(it.localizedMessage ?: "rename")) }
    }
}

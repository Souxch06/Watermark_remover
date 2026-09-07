package com.souxch.watermarkremover.processing

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.souxch.watermarkremover.model.RemovalMethod
import com.souxch.watermarkremover.model.RemovalSettings
import com.souxch.watermarkremover.model.VideoInfo
import com.souxch.watermarkremover.model.WatermarkZone
import com.souxch.watermarkremover.model.ZoneGeometry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.io.File

sealed interface ExportEvent {
    data class Progress(val percent: Int) : ExportEvent
    data class Done(val file: File) : ExportEvent
    data class Failed(val error: Throwable) : ExportEvent
}

/** Runs the Media3 Transformer: decode -> GL effect (or crop) -> H.264/AAC encode into an MP4. */
class VideoExporter(private val context: Context) {

    fun export(info: VideoInfo, zones: List<WatermarkZone>, settings: RemovalSettings, output: File): Flow<ExportEvent> =
        callbackFlow {
            val mainHandler = Handler(Looper.getMainLooper())
            val effects: List<Effect> = buildEffects(zones, settings)
            val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
                .setEffects(Effects(/* audioProcessors= */ emptyList(), effects))
                .build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        trySend(ExportEvent.Progress(100))
                        trySend(ExportEvent.Done(output))
                        close()
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        trySend(ExportEvent.Failed(exportException))
                        close()
                    }
                })
                .build()

            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        trySend(ExportEvent.Progress(holder.progress))
                    }
                    mainHandler.postDelayed(this, 300)
                }
            }
            mainHandler.post {
                transformer.start(item, output.absolutePath)
                mainHandler.post(poll)
            }

            awaitClose {
                mainHandler.removeCallbacks(poll)
                mainHandler.post { transformer.cancel() }
            }
        }

    private fun buildEffects(zones: List<WatermarkZone>, settings: RemovalSettings): List<Effect> {
        if (zones.isEmpty()) return emptyList()
        return if (settings.method == RemovalMethod.CROP) {
            val crop = ZoneGeometry.cropRectExcluding(zones.first().rect)
            val (left, right, bottom, top) = ZoneGeometry.toNdcCrop(crop)
            listOf(Crop(left, right, bottom, top))
        } else {
            listOf(WatermarkRemovalEffect(zones, settings))
        }
    }
}

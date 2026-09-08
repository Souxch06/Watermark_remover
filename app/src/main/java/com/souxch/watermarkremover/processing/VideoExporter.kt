package com.souxch.watermarkremover.processing

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import android.media.MediaCodecInfo
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
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

    fun export(
        info: VideoInfo,
        zones: List<WatermarkZone>,
        settings: RemovalSettings,
        output: File,
        layer: WatermarkLayer? = null,
    ): Flow<ExportEvent> =
        callbackFlow {
            val mainHandler = Handler(Looper.getMainLooper())
            val effects: List<Effect> = buildEffects(zones, settings, layer)
            val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
                .setEffects(Effects(/* audioProcessors= */ emptyList(), effects))
                .build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(buildEncoderFactory(info, settings))
                // Audio is untouched: it is copied as-is when the container allows it, which
                // avoids a lossy AAC re-encode. (Falls back to AAC transcoding automatically.)
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

    /**
     * Encoder tuned for quality rather than Media3's conservative defaults:
     *  - bitrate from the source bitrate / resolution floor ([ExportQuality.targetBitrate]),
     *  - VBR so static areas do not waste bits,
     *  - H.264 High profile when the device encoder supports it,
     *  - the source resolution and frame rate are kept (no fallback to a smaller encoder size).
     */
    private fun buildEncoderFactory(info: VideoInfo, settings: RemovalSettings): DefaultEncoderFactory {
        val bitrate = settings.quality.targetBitrate(info.displayWidth, info.displayHeight, info.frameRate, info.bitrate)
        val videoSettings = VideoEncoderSettings.Builder()
            .setBitrate(bitrate)
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .setEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, VideoEncoderSettings.NO_VALUE)
            .setiFrameIntervalSeconds(1f)
            .build()
        return DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoSettings)
            .setEnableFallback(true)
            .build()
    }

    /** Human readable target bitrate, e.g. "18 Mb/s" – shown in the editor. */
    fun describeTargetBitrate(info: VideoInfo, settings: RemovalSettings): String {
        val bps = settings.quality.targetBitrate(info.displayWidth, info.displayHeight, info.frameRate, info.bitrate)
        return "%.0f Mb/s".format(bps / 1_000_000f)
    }

    private fun buildEffects(zones: List<WatermarkZone>, settings: RemovalSettings, layer: WatermarkLayer?): List<Effect> {
        if (zones.isEmpty()) return emptyList()
        return if (settings.method == RemovalMethod.CROP) {
            val crop = ZoneGeometry.cropRectExcluding(zones.first().rect)
            val (left, right, bottom, top) = ZoneGeometry.toNdcCrop(crop)
            listOf(Crop(left, right, bottom, top))
        } else {
            listOf(WatermarkRemovalEffect(zones, settings, layer))
        }
    }
}

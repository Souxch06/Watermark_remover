package com.souxch.watermarkremover.model

import com.souxch.watermarkremover.processing.WatermarkShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneGeometryTest {

    @Test
    fun `translated never leaves the unit square`() {
        val r = NormalizedRect(0.6f, 0.86f, 0.97f, 0.97f).translated(0.5f, 0.5f)
        assertEquals(1f, r.right, 1e-6f)
        assertEquals(1f, r.bottom, 1e-6f)
        assertEquals(0.37f, r.width, 1e-5f)
    }

    @Test
    fun `resized enforces minimum size`() {
        val r = NormalizedRect(0.2f, 0.2f, 0.4f, 0.4f).resized(Corner.BOTTOM_RIGHT, -1f, -1f)
        assertEquals(NormalizedRect.MIN_SIZE, r.width, 1e-6f)
        assertEquals(NormalizedRect.MIN_SIZE, r.height, 1e-6f)
    }

    @Test
    fun `crop keeps largest region for a bottom-right watermark`() {
        val crop = ZoneGeometry.cropRectExcluding(NormalizedRect(0.6f, 0.86f, 0.97f, 0.97f))
        assertEquals(NormalizedRect(0f, 0f, 1f, 0.86f), crop)
    }

    @Test
    fun `crop keeps largest region for a left-side watermark`() {
        val crop = ZoneGeometry.cropRectExcluding(NormalizedRect(0f, 0.1f, 0.2f, 0.9f))
        assertEquals(NormalizedRect(0.2f, 0f, 1f, 1f), crop)
    }

    @Test
    fun `ndc crop flips the y axis`() {
        val ndc = ZoneGeometry.toNdcCrop(NormalizedRect(0f, 0f, 1f, 0.5f))
        assertEquals(-1f, ndc[0], 1e-6f) // left
        assertEquals(1f, ndc[1], 1e-6f)  // right
        assertEquals(0f, ndc[2], 1e-6f)  // bottom
        assertEquals(1f, ndc[3], 1e-6f)  // top
    }

    @Test
    fun `cropped size is even`() {
        val (w, h) = ZoneGeometry.croppedSize(1081, 1919, NormalizedRect(0f, 0f, 1f, 0.86f))
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test
    fun `zone uniforms are in texture space with y up`() {
        val u = WatermarkShader.zoneUniforms(listOf(WatermarkZone(1, NormalizedRect(0.6f, 0.86f, 0.97f, 0.97f))))
        assertEquals(0.6f, u[0], 1e-6f)
        assertEquals(0.03f, u[1], 1e-6f)
        assertEquals(0.97f, u[2], 1e-6f)
        assertEquals(0.14f, u[3], 1e-6f)
    }

    @Test
    fun `target bitrate never goes below the source bitrate`() {
        val src = 20_000_000
        ExportQuality.entries.forEach { q ->
            assertTrue(q.targetBitrate(1920, 1080, 30f, src) >= src)
        }
    }

    @Test
    fun `target bitrate has a resolution floor when the source is poor or unknown`() {
        val hd = ExportQuality.HIGH.targetBitrate(1920, 1080, 30f, 0)
        val uhd = ExportQuality.HIGH.targetBitrate(3840, 2160, 30f, 0)
        assertTrue(hd >= 8_000_000)
        assertTrue(uhd > hd * 3)
        assertTrue(ExportQuality.STANDARD.targetBitrate(1280, 720, 30f, 500_000) >= ExportQuality.MIN_BITRATE)
    }

    @Test
    fun `default settings export at maximum quality and at least twice the source bitrate`() {
        val settings = RemovalSettings()
        assertEquals(ExportQuality.MAXIMUM, settings.quality)
        val src = 18_000_000
        assertTrue(settings.quality.targetBitrate(1920, 1080, 30f, src) >= src * 2)
    }

    @Test
    fun `target bitrate is capped`() {
        assertEquals(ExportQuality.MAX_BITRATE.toInt(), ExportQuality.MAXIMUM.targetBitrate(7680, 4320, 60f, 400_000_000))
    }

    @Test
    fun `reconstruction uses a tight anti-aliasing band, other methods a soft feather`() {
        val inpaint = WatermarkShader.featherTextureUnits(RemovalSettings(method = RemovalMethod.INPAINT), 1920, 1080)
        assertEquals(WatermarkShader.INPAINT_EDGE_PIXELS / 1920f, inpaint, 1e-6f)
        val blur = WatermarkShader.featherTextureUnits(RemovalSettings(method = RemovalMethod.BLUR), 1920, 1080)
        assertTrue(blur > inpaint * 4)
    }
}

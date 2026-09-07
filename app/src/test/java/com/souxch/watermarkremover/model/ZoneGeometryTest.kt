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
}

package com.souxch.watermarkremover.data

import com.souxch.watermarkremover.model.RemovalMethod
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedVideoTest {

    private val sample = ProcessedVideo(
        id = 42L,
        uri = "content://media/external/video/media/1",
        displayName = "clip_sans_filigrane.mp4",
        sourceName = "clip.mp4",
        createdAtMillis = 1_700_000_000_000L,
        durationMs = 12_345L,
        width = 1920,
        height = 1080,
        sizeBytes = 9_876_543L,
        method = RemovalMethod.BLUR,
        zoneCount = 2,
        thumbnailPath = "/data/thumbs/42.jpg",
    )

    @Test
    fun `json round trip preserves every field`() {
        val json = ProcessedVideo.listToJson(listOf(sample))
        val parsed = ProcessedVideo.listFromJson(json)
        assertEquals(listOf(sample), parsed)
    }

    @Test
    fun `null thumbnail survives round trip`() {
        val item = sample.copy(thumbnailPath = null)
        val parsed = ProcessedVideo.fromJson(item.toJson())!!
        assertNull(parsed.thumbnailPath)
    }

    @Test
    fun `unknown method falls back to inpaint`() {
        val o = sample.toJson().put("method", "LASER")
        assertEquals(RemovalMethod.INPAINT, ProcessedVideo.fromJson(o)!!.method)
    }

    @Test
    fun `malformed entries are skipped, not fatal`() {
        val text = """{"version":1,"items":[{"foo":"bar"}, ${sample.toJson()}]}"""
        val parsed = ProcessedVideo.listFromJson(text)
        assertEquals(1, parsed.size)
        assertEquals(42L, parsed[0].id)
    }

    @Test
    fun `garbage index yields empty list`() {
        assertTrue(ProcessedVideo.listFromJson("not json").isEmpty())
        assertTrue(ProcessedVideo.listFromJson("").isEmpty())
        assertNull(ProcessedVideo.fromJson(JSONObject()))
    }

    @Test
    fun `display name is derived from the source without double extension`() {
        assertEquals("IMG_1234_sans_filigrane.mp4", LibraryRepository.buildDisplayName("IMG_1234.mp4"))
        assertEquals("video_sans_filigrane.mp4", LibraryRepository.buildDisplayName(".mp4"))
        assertEquals("my.clip_sans_filigrane.mp4", LibraryRepository.buildDisplayName("my.clip.mov"))
    }
}

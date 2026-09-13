package com.souxch.watermarkremover.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryTest {

    @Test
    fun `display name keeps a clean base and always ends in mp4`() {
        assertEquals("IMG_1234_sans_filigrane.mp4", LibraryRepository.buildDisplayName("IMG_1234.mp4"))
        assertEquals("video_sans_filigrane.mp4", LibraryRepository.buildDisplayName(""))
        assertEquals("clip_sans_filigrane.mp4", LibraryRepository.buildDisplayName("clip.MOV"))
    }

    @Test
    fun `a name coming from another app cannot escape the album directory`() {
        // The picker result is attacker-controllable: no separator, no traversal, no control char.
        for (hostile in listOf("../../evil.mp4", "..\\..\\evil.mp4", "/sdcard/evil.mp4", "a/b/c.mp4", "a\u0000b.mp4")) {
            val name = LibraryRepository.buildDisplayName(hostile)
            assertFalse(name, name.contains('/'))
            assertFalse(name, name.contains('\\'))
            assertFalse(name, name.contains(".."))
            assertTrue(name.endsWith(".mp4"))
        }
    }

    @Test
    fun `the sanitized base is never blank and stays short`() {
        assertEquals("video", LibraryRepository.sanitize("   "))
        assertEquals("video", LibraryRepository.sanitize("..."))
        assertTrue(LibraryRepository.sanitize("x".repeat(500)).length <= 96)
    }
}

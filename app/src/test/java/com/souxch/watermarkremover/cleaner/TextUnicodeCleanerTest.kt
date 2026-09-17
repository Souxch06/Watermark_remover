package com.souxch.watermarkremover.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUnicodeCleanerTest {
    @Test
    fun removesInvisibleCharactersButKeepsEmojiSequence() {
        val result = TextUnicodeCleaner.clean("A\u200BB\u200D😀\uFE0F\u200D🚀❤️\uFE0F")

        assertEquals("AB😀\uFE0F\u200D🚀❤️", result.text)
        assertTrue(result.removedCount >= 1)
    }

    @Test
    fun normalizesSpaceLookalikes() {
        val result = TextUnicodeCleaner.clean("hello\u00A0world\u2009!")

        assertEquals("hello world !", result.text)
        assertEquals(2, result.replacedCount)
    }

    @Test
    fun doesNotRemoveLegitimateRtlDirectionMarksByDefault() {
        val mark = '\u200F'
        val result = TextUnicodeCleaner.clean("שלום${mark}world")

        assertTrue(result.text.contains(mark))
    }

    @Test
    fun aggressiveModeConvertsFullWidthAndCyrillicLookalikes() {
        val result = TextUnicodeCleaner.clean("Ｈello ОK", aggressiveConfusables = true)

        assertEquals("Hello OK", result.text)
        assertTrue(result.replacedCount >= 2)
    }
}

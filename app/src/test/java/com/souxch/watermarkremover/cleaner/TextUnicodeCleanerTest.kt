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

    @Test
    fun preservesCompleteBidiEmbeddingsAndFlagTagSequences() {
        val bidi = "A\u202Bمرحبا\u202CB"
        assertEquals(bidi, TextUnicodeCleaner.clean(bidi).text)
        assertEquals("AمرحباB", TextUnicodeCleaner.clean(bidi, stripBidi = true).text)

        val flag = String(Character.toChars(0x1F3F4)) + listOf(
            0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE007F,
        ).joinToString("") { String(Character.toChars(it)) }
        assertEquals(flag, TextUnicodeCleaner.clean(flag).text)
        assertTrue(TextUnicodeCleaner.clean(flag, stripEmojiGlue = true).removedCount > 0)
    }

    @Test
    fun inspectReportsInvisibleAndSpaceCarriers() {
        val report = TextUnicodeCleaner.inspect("A\u200BB\u00A0C")

        assertTrue(report.suspiciousTotal >= 2)
        assertTrue(report.hits.any { it.codePoint == "U+200B" })
        assertTrue(report.hits.any { it.codePoint == "U+00A0" })
    }
}

package com.souxch.watermarkremover.cleaner

import java.text.Normalizer

/**
 * Result of the deterministic text pass.
 *
 * The original project calls this Layer A: it removes invisible format
 * characters and normalises space look-alikes without attempting to rewrite
 * the author's prose. Statistical, token-sampling watermarks are deliberately
 * not guessed at on the phone.
 */
data class TextCleanResult(
    val text: String,
    val removedCount: Int,
    val replacedCount: Int,
    val removedCodePoints: Map<String, Int>,
    val changedByNfkc: Boolean,
)

/**
 * Small, dependency-free port of the safe part of the upstream Unicode pass.
 *
 * Android's normal text widgets are UTF-16, so the implementation walks Unicode
 * code points rather than Kotlin Char values. Emoji joiners and variation
 * selectors are retained when they are visibly part of an emoji sequence; a
 * naked zero-width character is removed. RTL direction marks are retained by
 * default because they are legitimate in Arabic/Hebrew text.
 */
object TextUnicodeCleaner {
    private val spaceReplacements = mapOf(
        0x00A0 to ' ', // no-break space
        0x1680 to ' ',
        0x2000 to ' ',
        0x2001 to ' ',
        0x2002 to ' ',
        0x2003 to ' ',
        0x2004 to ' ',
        0x2005 to ' ',
        0x2006 to ' ',
        0x2007 to ' ',
        0x2008 to ' ',
        0x2009 to ' ',
        0x200A to ' ',
        0x202F to ' ',
        0x205F to ' ',
        0x3000 to ' ',
    )

    private val bidiToPreserve = setOf(
        0x061C, // Arabic letter mark
        0x200E, // LRM
        0x200F, // RLM
        0x2066, // LRI
        0x2067, // RLI
        0x2068, // FSI
        0x2069, // PDI
    )

    private val basicConfusables = mapOf(
        0x0410 to 'A', 0x0412 to 'B', 0x0415 to 'E', 0x041A to 'K',
        0x041C to 'M', 0x041D to 'H', 0x041E to 'O', 0x0420 to 'P',
        0x0421 to 'C', 0x0422 to 'T', 0x0425 to 'X',
        0x0430 to 'a', 0x0435 to 'e', 0x043E to 'o', 0x0440 to 'p',
        0x0441 to 'c', 0x0443 to 'y', 0x0445 to 'x', 0x0456 to 'i',
    )

    fun clean(
        input: String,
        normalizeSpaces: Boolean = true,
        nfkc: Boolean = false,
        aggressiveConfusables: Boolean = false,
    ): TextCleanResult {
        val output = StringBuilder(input.length)
        val removed = linkedMapOf<String, Int>()
        var removedCount = 0
        var replacedCount = 0
        var previousKeptCodePoint = -1
        var index = 0

        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val source = input.substring(index, index + charCount)
            val nextCodePoint = if (index + charCount < input.length) {
                input.codePointAt(index + charCount)
            } else {
                -1
            }
            val previousInputCodePoint = if (index > 0) input.codePointBefore(index) else -1

            when {
                isEmojiVariationSelector(codePoint) &&
                    isEmojiBase(previousKeptCodePoint) &&
                    !isAnyVariationSelector(previousInputCodePoint) -> {
                    output.append(source)
                    // Keep the visible selector attached to the base, but do
                    // not make it replace that base for a following ZWJ.
                }

                codePoint == 0x200D &&
                    isEmojiBase(previousKeptCodePoint) &&
                    isEmojiBase(nextCodePoint) -> {
                    // Keep the joiner only when it visibly joins two emoji.
                    output.append(source)
                }

                isCjkVariationSelector(codePoint) &&
                    isCjkBase(previousKeptCodePoint) &&
                    !isAnyVariationSelector(previousInputCodePoint) -> {
                    output.append(source)
                    // The selector belongs to the CJK base and must not
                    // become the previous visible character.
                }

                codePoint in bidiToPreserve -> {
                    output.append(source)
                    // A direction mark is not a visible base for the next
                    // emoji/script decision.
                }

                codePoint in spaceReplacements && normalizeSpaces -> {
                    output.append(spaceReplacements.getValue(codePoint))
                    replacedCount++
                    previousKeptCodePoint = ' '.code
                }

                aggressiveConfusables && isFullWidthAscii(codePoint) -> {
                    val replacement = if (codePoint in 0xFF01..0xFF5E) {
                        (codePoint - 0xFEE0).toChar()
                    } else {
                        null
                    }
                    if (replacement != null) {
                        output.append(replacement)
                        replacedCount++
                        previousKeptCodePoint = replacement.code
                    } else {
                        appendKept(output, source, codePoint) { previousKeptCodePoint = it }
                    }
                }

                aggressiveConfusables && basicConfusables.containsKey(codePoint) -> {
                    val replacement = basicConfusables.getValue(codePoint)
                    output.append(replacement)
                    replacedCount++
                    previousKeptCodePoint = replacement.code
                }

                isStripCandidate(codePoint) -> {
                    val key = "U+%04X".format(codePoint)
                    removed[key] = (removed[key] ?: 0) + 1
                    removedCount++
                }

                else -> {
                    appendKept(output, source, codePoint) { previousKeptCodePoint = it }
                }
            }
            index += charCount
        }

        var result = output.toString()
        var changedByNfkc = false
        if (nfkc) {
            val normalized = Normalizer.normalize(result, Normalizer.Form.NFKC)
            changedByNfkc = normalized != result
            if (changedByNfkc) replacedCount++
            result = normalized
        }

        return TextCleanResult(
            text = result,
            removedCount = removedCount,
            replacedCount = replacedCount,
            removedCodePoints = removed,
            changedByNfkc = changedByNfkc,
        )
    }

    private inline fun appendKept(
        output: StringBuilder,
        source: String,
        codePoint: Int,
        setPrevious: (Int) -> Unit,
    ) {
        output.append(source)
        // Combining marks keep the previous visible base; otherwise a later
        // emoji joiner should still be associated with that base.
        if (Character.getType(codePoint) != Character.NON_SPACING_MARK.toInt() &&
            Character.getType(codePoint) != Character.COMBINING_SPACING_MARK.toInt() &&
            Character.getType(codePoint) != Character.ENCLOSING_MARK.toInt()
        ) {
            setPrevious(codePoint)
        }
    }

    private fun isStripCandidate(codePoint: Int): Boolean {
        if (codePoint in bidiToPreserve) return false
        if (codePoint in 0xE0001..0xE007F) return true // Unicode tag characters
        if (codePoint in 0xE0080..0xE01FF) return true // reserved tag/variation area
        if (codePoint in 0xFDD0..0xFDEF || (codePoint and 0xFFFE) == 0xFFFE) return true
        if (codePoint in 0xE000..0xF8FF || codePoint in 0xF0000..0xFFFFD || codePoint in 0x100000..0x10FFFD) return true

        // Format controls which do not carry a visible bidi layout by default.
        if (codePoint in setOf(
                0x00AD, 0x034F,
                0x115F, 0x1160, 0x17B4, 0x17B5,
                0x180B, 0x180C, 0x180D, 0x180E, 0x180F,
                0x200B, 0x200C, 0x200D,
                0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
                0x2060, 0x2061, 0x2062, 0x2063, 0x2064,
                0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F,
                0xFE00, 0xFE01, 0xFE02, 0xFE03, 0xFE04, 0xFE05, 0xFE06, 0xFE07,
                0xFE08, 0xFE09, 0xFE0A, 0xFE0B, 0xFE0C, 0xFE0D, 0xFE0E, 0xFE0F,
                0xFEFF,
                0xFFF9, 0xFFFA, 0xFFFB,
            )
        ) return true

        // Supplementary variation selectors are not needed by normal Android
        // text and are a common edit-based carrier. Emoji VS16 was handled
        // above, as were the visible CJK selectors.
        if (codePoint in 0xE0100..0xE01EF) return true
        return Character.getType(codePoint) == Character.FORMAT.toInt()
    }

    private fun isEmojiVariationSelector(codePoint: Int): Boolean = codePoint == 0xFE0E || codePoint == 0xFE0F

    private fun isAnyVariationSelector(codePoint: Int): Boolean =
        codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF

    private fun isCjkVariationSelector(codePoint: Int): Boolean =
        codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF

    private fun isCjkBase(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF || codePoint in 0xF900..0xFAFF

    private fun isEmojiBase(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2190..0x27BF ||
            codePoint in 0x2B00..0x2BFF ||
            codePoint in setOf(0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x2934, 0x2935)

    private fun isFullWidthAscii(codePoint: Int): Boolean = codePoint in 0xFF01..0xFF5E
}

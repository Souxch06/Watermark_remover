package com.souxch.watermarkremover.cleaner

import java.text.Normalizer
import java.util.Locale

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
    /** Replacements keyed by code point or the synthetic NFKC label. */
    val replacedCodePoints: Map<String, Int> = emptyMap(),
)

data class TextInspectionHit(
    val codePoint: String,
    val label: String,
    val count: Int,
    val kind: String,
)

data class TextInspectionResult(
    val length: Int,
    val suspiciousTotal: Int,
    val hits: List<TextInspectionHit>,
    val notes: List<String> = emptyList(),
)

/**
 * Dependency-free port of the upstream deterministic Unicode pass.
 *
 * Android's normal text widgets are UTF-16, so this implementation walks
 * Unicode code points rather than Kotlin Char values. It removes edit-based
 * invisible carriers, but keeps load-bearing emoji, script and bidi controls
 * unless the caller explicitly asks for aggressive stripping.
 */
object TextUnicodeCleaner {
    private val spaceReplacements = mapOf(
        0x00A0 to ' ', 0x1680 to ' ',
        0x2000 to ' ', 0x2001 to ' ', 0x2002 to ' ', 0x2003 to ' ',
        0x2004 to ' ', 0x2005 to ' ', 0x2006 to ' ', 0x2007 to ' ',
        0x2008 to ' ', 0x2009 to ' ', 0x200A to ' ', 0x202F to ' ',
        0x205F to ' ', 0x3000 to ' ',
    )

    private val stripCodePoints = setOf(
        0x00AD, 0x034F, 0x061C,
        0x115F, 0x1160, 0x17B4, 0x17B5,
        0x180B, 0x180C, 0x180D, 0x180E, 0x180F,
        0x200B, 0x200C, 0x200D,
        0x200E, 0x200F,
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
        0x2060, 0x2061, 0x2062, 0x2063, 0x2064,
        0x2066, 0x2067, 0x2068, 0x2069,
        0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F,
        0xFE00, 0xFE01, 0xFE02, 0xFE03, 0xFE04, 0xFE05, 0xFE06, 0xFE07,
        0xFE08, 0xFE09, 0xFE0A, 0xFE0B, 0xFE0C, 0xFE0D, 0xFE0E, 0xFE0F,
        0xFEFF,
        0xFFF9, 0xFFFA, 0xFFFB,
    )

    private val preservableBidi = setOf(
        0x061C, 0x200E, 0x200F,
        0x2066, 0x2067, 0x2068, 0x2069,
    )

    private val orthographicCf = setOf(
        0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605,
        0x06DD, 0x070F, 0x08E2, 0x110BD, 0x110CD,
    )

    private val mongolianFvs = setOf(0x180B, 0x180C, 0x180D, 0x180F)
    private val khmerVowels = setOf(0x17B4, 0x17B5)
    private val hangulFillers = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)
    private val scriptJoiners = setOf(0x200C, 0x200D)

    private val basicConfusables = mapOf(
        0x0410 to 'A', 0x0412 to 'B', 0x0415 to 'E', 0x041A to 'K',
        0x041C to 'M', 0x041D to 'H', 0x041E to 'O', 0x0420 to 'P',
        0x0421 to 'C', 0x0422 to 'T', 0x0425 to 'X',
        0x0430 to 'a', 0x0435 to 'e', 0x043E to 'o', 0x0440 to 'p',
        0x0441 to 'c', 0x0443 to 'y', 0x0445 to 'x', 0x0456 to 'i',
    )

    private val fullWidthConfusables: Map<Int, Char> by lazy {
        buildMap {
            for (codePoint in 0xFF01..0xFF5E) put(codePoint, (codePoint - 0xFEE0).toChar())
        }
    }

    fun clean(
        input: String,
        normalizeSpaces: Boolean = true,
        nfkc: Boolean = false,
        aggressiveConfusables: Boolean = false,
        stripEmojiGlue: Boolean = false,
        stripBidi: Boolean = false,
    ): TextCleanResult {
        val output = StringBuilder(input.length)
        val removed = linkedMapOf<String, Int>()
        val replaced = linkedMapOf<String, Int>()
        var removedCount = 0
        var replacedCount = 0
        var previousKeptCodePoint = -1
        var index = 0
        val validFlags = validFlagTagIndices(input)
        val validBidiEmbeddings = validBidiEmbeddingIndices(input)

        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val source = input.substring(index, index + charCount)
            val nextIndex = index + charCount
            val nextCodePoint = if (nextIndex < input.length) input.codePointAt(nextIndex) else -1
            val previousInputCodePoint = if (index > 0) input.codePointBefore(index) else -1

            when {
                !stripBidi && index in validBidiEmbeddings -> {
                    output.append(source)
                }

                !stripBidi && codePoint in preservableBidi -> {
                    output.append(source)
                }

                !stripEmojiGlue && isCjkVariationSelector(codePoint) &&
                    isCjkBase(previousInputCodePoint) -> {
                    output.append(source)
                }

                !stripEmojiGlue && isMongolianFvs(codePoint) &&
                    isMongolianBase(previousInputCodePoint) -> {
                    output.append(source)
                }

                !stripEmojiGlue && isEmojiVariationSelector(codePoint) &&
                    isEmojiBase(previousInputCodePoint) -> {
                    output.append(source)
                }

                !stripEmojiGlue && codePoint == 0x200D &&
                    isEmojiBase(previousKeptCodePoint) &&
                    isEmojiBase(nextCodePoint) -> {
                    output.append(source)
                }

                !stripEmojiGlue && codePoint in scriptJoiners &&
                    joiningScript(previousInputCodePoint) != null &&
                    joiningScript(previousInputCodePoint) == joiningScript(nextCodePoint) -> {
                    output.append(source)
                }

                !stripEmojiGlue && codePoint in 0xE0020..0xE007F && index in validFlags -> {
                    output.append(source)
                }

                !stripEmojiGlue && codePoint in khmerVowels && isKhmerLetter(previousKeptCodePoint) -> {
                    output.append(source)
                }

                !stripEmojiGlue && codePoint in hangulFillers && isHangulJamo(previousKeptCodePoint) -> {
                    output.append(source)
                }

                !stripEmojiGlue && codePoint in orthographicCf -> {
                    output.append(source)
                }

                !stripEmojiGlue && isLayoutControl(codePoint) &&
                    (isLayoutScriptCodePoint(previousInputCodePoint) || isLayoutScriptCodePoint(nextCodePoint)) -> {
                    output.append(source)
                }

                isStripCandidate(codePoint, stripBidi) -> {
                    val key = "U+%04X".format(Locale.US, codePoint)
                    removed[key] = (removed[key] ?: 0) + 1
                    removedCount++
                }

                normalizeSpaces && spaceReplacements.containsKey(codePoint) -> {
                    output.append(spaceReplacements.getValue(codePoint))
                    val key = "U+%04X".format(Locale.US, codePoint)
                    replaced[key] = (replaced[key] ?: 0) + 1
                    replacedCount++
                    previousKeptCodePoint = ' '.code
                }

                aggressiveConfusables && fullWidthConfusables.containsKey(codePoint) -> {
                    val replacement = fullWidthConfusables.getValue(codePoint)
                    output.append(replacement)
                    val key = "U+%04X".format(Locale.US, codePoint)
                    replaced[key] = (replaced[key] ?: 0) + 1
                    replacedCount++
                    previousKeptCodePoint = replacement.code
                }

                aggressiveConfusables && basicConfusables.containsKey(codePoint) -> {
                    val replacement = basicConfusables.getValue(codePoint)
                    output.append(replacement)
                    val key = "U+%04X".format(Locale.US, codePoint)
                    replaced[key] = (replaced[key] ?: 0) + 1
                    replacedCount++
                    previousKeptCodePoint = replacement.code
                }

                else -> {
                    appendKept(output, source, codePoint) { previousKeptCodePoint = it }
                }
            }
            index = nextIndex
        }

        var result = output.toString()
        var changedByNfkc = false
        if (nfkc) {
            val normalized = Normalizer.normalize(result, Normalizer.Form.NFKC)
            changedByNfkc = normalized != result
            if (changedByNfkc) {
                replacedCount++
                replaced["NFKC"] = 1
            }
            result = normalized
        }

        return TextCleanResult(
            text = result,
            removedCount = removedCount,
            replacedCount = replacedCount,
            removedCodePoints = removed,
            changedByNfkc = changedByNfkc,
            replacedCodePoints = replaced,
        )
    }

    /**
     * Inspect the same deterministic carriers without changing the input.
     * Statistical/token-sampling watermarks are intentionally reported as an
     * unsupported capability rather than guessed from prose.
     */
    fun inspect(
        input: String,
        aggressiveConfusables: Boolean = false,
        normalizeSpaces: Boolean = true,
        stripEmojiGlue: Boolean = false,
        stripBidi: Boolean = false,
    ): TextInspectionResult {
        val cleaned = clean(
            input,
            normalizeSpaces = normalizeSpaces,
            aggressiveConfusables = aggressiveConfusables,
            stripEmojiGlue = stripEmojiGlue,
            stripBidi = stripBidi,
        )
        val hits = (cleaned.removedCodePoints + cleaned.replacedCodePoints).entries.map { (codePoint, count) ->
            val cp = codePoint.removePrefix("U+").toIntOrNull(16)
            val replacement = cp == null
            TextInspectionHit(
                codePoint = codePoint,
                label = if (replacement) "NFKC / forme compatible" else "Caractère $codePoint",
                count = count,
                kind = if (replacement) "normalization" else inspectKind(cp!!),
            )
        }.sortedByDescending { it.count }
        val notes = mutableListOf(
            "Détection déterministe uniquement : caractères Unicode invisibles, contrôles de format et espaces homoglyphes.",
            "Les filigranes statistiques/token sampling ne sont pas détectables nativement sur Android.",
        )
        if (cleaned.replacedCount > 0) notes += "${cleaned.replacedCount} espace(s) ou homoglyphe(s) seraient normalisé(s)."
        if (hits.isEmpty() && cleaned.replacedCount == 0) notes += "Aucun porteur Unicode déterministe détecté."
        return TextInspectionResult(
            length = input.codePointCount(0, input.length),
            suspiciousTotal = cleaned.removedCount + cleaned.replacedCount,
            hits = hits,
            notes = notes,
        )
    }

    private inline fun appendKept(
        output: StringBuilder,
        source: String,
        codePoint: Int,
        setPrevious: (Int) -> Unit,
    ) {
        output.append(source)
        val type = Character.getType(codePoint)
        if (type != Character.NON_SPACING_MARK.toInt() &&
            type != Character.COMBINING_SPACING_MARK.toInt() &&
            type != Character.ENCLOSING_MARK.toInt()
        ) {
            setPrevious(codePoint)
        }
    }

    private fun isStripCandidate(codePoint: Int, stripBidi: Boolean): Boolean {
        if (!stripBidi && codePoint in preservableBidi) return false
        if (codePoint in 0xE0001..0xE007F) return true
        if (codePoint in 0xE0080..0xE00FF) return true
        if (codePoint in 0xFDD0..0xFDEF || (codePoint and 0xFFFE) == 0xFFFE) return true
        if (isPrivateUse(codePoint) || isReservedIgnorable(codePoint)) return true
        if (codePoint in stripCodePoints) return true
        if (codePoint in 0xE0100..0xE01EF) return true
        return Character.getType(codePoint) == Character.FORMAT.toInt()
    }

    private fun inspectKind(codePoint: Int): String = when {
        codePoint in 0xE0001..0xE007F -> "tag_chars"
        codePoint in 0xFDD0..0xFDEF || (codePoint and 0xFFFE) == 0xFFFE -> "noncharacter"
        isReservedIgnorable(codePoint) -> "reserved_ignorable"
        codePoint in 0xE0100..0xE01EF || codePoint in 0xFE00..0xFE0F -> "variation_selector"
        codePoint in preservableBidi || codePoint in 0x202A..0x202E -> "bidi"
        codePoint in setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x180E) -> "zwj_family"
        isPrivateUse(codePoint) -> "private_use"
        else -> "other_cf"
    }

    private fun isPrivateUse(codePoint: Int): Boolean =
        codePoint in 0xE000..0xF8FF || codePoint in 0xF0000..0xFFFFD || codePoint in 0x100000..0x10FFFD

    private fun isReservedIgnorable(codePoint: Int): Boolean =
        codePoint == 0x2065 || codePoint == 0xE0000 ||
            codePoint in 0xFFF0..0xFFF8 || codePoint in 0xE0080..0xE00FF || codePoint in 0xE01F0..0xE0FFF

    private fun validFlagTagIndices(text: String): Set<Int> {
        val valid = mutableSetOf<Int>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val count = Character.charCount(codePoint)
            if (codePoint != 0x1F3F4) {
                index += count
                continue
            }
            var cursor = index + count
            while (cursor < text.length) {
                val tag = text.codePointAt(cursor)
                if (tag !in 0xE0020..0xE007E) break
                cursor += Character.charCount(tag)
            }
            if (cursor > index + count && cursor < text.length && text.codePointAt(cursor) == 0xE007F) {
                var mark = index + count
                while (mark <= cursor) {
                    valid += mark
                    mark += Character.charCount(text.codePointAt(mark))
                }
                index = cursor + Character.charCount(text.codePointAt(cursor))
            } else {
                index += count
            }
        }
        return valid
    }

    private fun validBidiEmbeddingIndices(text: String): Set<Int> {
        val valid = mutableSetOf<Int>()
        val stack = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when (codePoint) {
                0x202A, 0x202B, 0x202D, 0x202E -> stack += codePoint to index
                0x202C -> {
                    if (stack.isNotEmpty()) {
                        val (opener, openerIndex) = stack.removeAt(stack.lastIndex)
                        if (opener == 0x202A || opener == 0x202B) {
                            valid += openerIndex
                            valid += index
                        }
                    }
                }
            }
            index += Character.charCount(codePoint)
        }
        return valid
    }

    private fun isEmojiVariationSelector(codePoint: Int): Boolean = codePoint == 0xFE0E || codePoint == 0xFE0F

    private fun isCjkVariationSelector(codePoint: Int): Boolean =
        codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF

    private fun isEmojiBase(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2190..0x25FF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0x2B00..0x2BFF ||
            codePoint in setOf(0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x2934, 0x2935, 0x3030, 0x303D, 0x3297, 0x3299) ||
            codePoint == 0x0023 || codePoint == 0x002A || codePoint in 0x0030..0x0039

    private fun isCjkBase(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x323AF

    private fun isMongolianFvs(codePoint: Int): Boolean = codePoint in mongolianFvs

    private fun isMongolianBase(codePoint: Int): Boolean = codePoint in 0x1800..0x18AF

    private fun isMongolianLetter(codePoint: Int): Boolean =
        isMongolianBase(codePoint) && Character.isLetter(codePoint)

    private fun isKhmerLetter(codePoint: Int): Boolean =
        codePoint in 0x1780..0x17FF && Character.isLetter(codePoint)

    private fun isHangulJamo(codePoint: Int): Boolean =
        codePoint in 0x1100..0x11FF || codePoint in 0xA960..0xA97C ||
            codePoint in 0xD7B0..0xD7C6 || codePoint in 0x3131..0x318E ||
            codePoint in 0xFFA1..0xFFDC

    private fun isCjkScript(codePoint: Int): Boolean =
        isCjkBase(codePoint) || codePoint in 0x3040..0x30FF || codePoint in 0xAC00..0xD7AF

    private fun joiningScript(codePoint: Int): String? {
        val inScript = when {
            codePoint in 0x0600..0x08FF -> "arabic"
            codePoint in 0x0900..0x0DFF -> "indic"
            codePoint in 0x0F00..0x109F -> "south-asian"
            codePoint in 0x1780..0x17FF -> "khmer"
            codePoint in 0x1800..0x18AF -> "mongolian"
            else -> null
        }
        return if (inScript != null && (Character.isLetter(codePoint) || Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt())) inScript else null
    }

    private fun isLayoutControl(codePoint: Int): Boolean =
        codePoint in 0x13430..0x1343F || codePoint in 0x1BCA0..0x1BCA3 || codePoint in 0x1D173..0x1D17A

    private fun isLayoutScriptCodePoint(codePoint: Int): Boolean =
        codePoint in 0x13000..0x143FF || codePoint in 0x1BC00..0x1BCA3 || codePoint in 0x1D100..0x1D1FF
}

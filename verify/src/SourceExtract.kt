// Extracts a top-level/enum/companion declaration body (brace-balanced) from Kotlin source text,
// starting at the first occurrence of [anchor]. Returns the substring from the anchor up to and
// including the closing brace of the enclosing '{ ... }' block, or null if not found. Used to
// diff the mirrored pure functions against the real app sources (see verify/README.md).

package com.souxch.watermarkremover.verify

object SourceExtract {
    fun declaration(source: String, anchor: String): String? {
        val start = source.indexOf(anchor)
        if (start < 0) return null
        var i = start
        var depth = 0
        var started = false
        while (i < source.length) {
            val c = source[i]
            when (c) {
                '{' -> { depth++; started = true }
                '}' -> {
                    depth--
                    if (started && depth == 0) return source.substring(start, i + 1)
                }
                '"' -> { // skip string literals (braces inside them must not count)
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                }
                '\'' -> { i++; while (i < source.length && source[i] != '\'') { if (source[i] == '\\') i++; i++ } }
                '/' -> {
                    if (i + 1 < source.length && source[i + 1] == '/') {
                        while (i < source.length && source[i] != '\n') i++
                    } else if (i + 1 < source.length && source[i + 1] == '*') {
                        i += 2
                        while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                        i++
                    }
                }
            }
            i++
        }
        return null
    }

    /** Comparison that ignores whitespace and comments (quote-aware), so re-indentation or a
     *  comment rewrite does not flag a mirror while an actual code change (constant, operator,
     *  visibility) does. */
    fun equivalent(a: String?, b: String?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return stripInsignificant(a) == stripInsignificant(b)
    }

    private fun stripInsignificant(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> { i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    sb.append(quote)
                    i++
                    while (i < text.length && text[i] != quote) {
                        if (text[i] == '\\' && i + 1 < text.length) { sb.append(text[i]); i++; sb.append(text[i]); i++ }
                        else { sb.append(text[i]); i++ }
                    }
                    if (i < text.length) { sb.append(quote); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}

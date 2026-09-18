package com.souxch.watermarkremover.cleaner

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Kind understood by the on-device deterministic cleaner. */
enum class CleanFileKind(val label: String) {
    TEXT("Texte"),
    PNG("PNG"),
    JPEG("JPEG"),
    WEBP("WebP"),
    GIF("GIF"),
    BMP("BMP"),
    TIFF("TIFF"),
    AVIF("AVIF"),
    HEIC("HEIC"),
    WAV("WAV"),
    MP3("MP3"),
    FLAC("FLAC"),
    MP4("MP4 / MOV"),
    ZIP_CONTAINER("Document / archive"),
    PDF("PDF"),
    UNSUPPORTED("Format non pris en charge"),
}

data class NativeCleanReport(
    val kind: CleanFileKind,
    val bytesIn: Int,
    val bytesOut: Int,
    val changed: Boolean,
    val actions: List<String>,
    val removedCount: Int = 0,
    val replacedCount: Int = 0,
    val warnings: List<String> = emptyList(),
    /** Marker names still detectable after the native pass. */
    val residualMarkers: List<String> = emptyList(),
) {
    val summary: String
        get() = when {
            residualMarkers.isNotEmpty() -> "Résidus détectés : ${residualMarkers.joinToString(", ")}"
            !changed && warnings.isEmpty() -> "Aucun marqueur déterministe trouvé"
            changed -> actions.firstOrNull() ?: "Métadonnées retirées"
            else -> warnings.firstOrNull() ?: "Aucune modification"
        }
}

data class NativeCleanOutput(
    val bytes: ByteArray,
    val report: NativeCleanReport,
)

/** Options that correspond to the deterministic, dependency-free part of the upstream CLI. */
data class NativeCleanOptions(
    val aggressiveText: Boolean = false,
    val normalizeSpaces: Boolean = true,
    val nfkc: Boolean = false,
    val stripEmojiGlue: Boolean = false,
    val stripBidi: Boolean = false,
    /** When false, only provenance-looking metadata is removed where supported. */
    val stripAllMetadata: Boolean = true,
)

/**
 * Offline Android port of the upstream repository's safe metadata pipeline.
 *
 * It intentionally does not ship a Python runtime, ffmpeg, qpdf or a diffusion
 * model. Those optional desktop backends cannot be made reliable by simply
 * copying them into an APK. Instead this class performs lossless container
 * surgery for common formats and keeps coded media bytes untouched. The visible
 * video watermark editor remains available through the existing Media3 path.
 */
object NativeWatermarkCleaner {
    private const val MAX_ZIP_ENTRIES = 2_000
    private const val MAX_ZIP_UNCOMPRESSED = 64L * 1024L * 1024L

    private val textExtensions = setOf(
        "txt", "text", "md", "markdown", "mdx", "html", "htm", "xhtml", "css", "js", "jsx", "mjs", "cjs",
        "ts", "tsx", "py", "rs", "go", "java", "kt", "kts", "swift", "dart", "json", "yaml", "yml",
        "toml", "csv", "tsv", "xml", "svg", "tex", "ltx", "rst", "adoc", "asciidoc", "org", "po", "pot",
        "strings", "arb", "properties", "ini", "cfg", "conf", "opf", "rels", "ncx", "jsonld", "map", "sh", "bash", "zsh", "ps1", "sql", "lua",
        "rb", "php", "c", "h", "cpp", "cc", "hpp", "cs", "scala", "vue", "svelte", "astro",
    )

    fun classify(displayName: String, mimeType: String?, data: ByteArray? = null): CleanFileKind {
        val extension = extensionOf(displayName)
        val byExtension = when (extension) {
            in textExtensions -> CleanFileKind.TEXT
            "png" -> CleanFileKind.PNG
            "jpg", "jpeg" -> CleanFileKind.JPEG
            "webp" -> CleanFileKind.WEBP
            "gif" -> CleanFileKind.GIF
            "bmp", "dib" -> CleanFileKind.BMP
            "tif", "tiff" -> CleanFileKind.TIFF
            "avif" -> CleanFileKind.AVIF
            "heic", "heif" -> CleanFileKind.HEIC
            "wav" -> CleanFileKind.WAV
            "mp3" -> CleanFileKind.MP3
            "flac" -> CleanFileKind.FLAC
            "mp4", "m4v", "mov", "m4a", "3gp", "3g2" -> CleanFileKind.MP4
            "zip", "docx", "docm", "xlsx", "xlsm", "pptx", "pptm", "odt", "ods", "odp", "odg", "epub", "cbz", "jar" -> CleanFileKind.ZIP_CONTAINER
            "pdf" -> CleanFileKind.PDF
            else -> null
        }
        if (byExtension != null) return byExtension

        val mime = mimeType.orEmpty().lowercase(Locale.US)
        if (mime.startsWith("text/")) return CleanFileKind.TEXT
        if (mime == "image/png") return CleanFileKind.PNG
        if (mime == "image/jpeg") return CleanFileKind.JPEG
        if (mime == "image/webp") return CleanFileKind.WEBP
        if (mime == "image/gif") return CleanFileKind.GIF
        if (mime == "image/bmp") return CleanFileKind.BMP
        if (mime == "image/tiff") return CleanFileKind.TIFF
        if (mime == "application/pdf") return CleanFileKind.PDF
        if (mime == "application/zip" || mime == "application/x-zip-compressed" || mime.endsWith("+zip")) return CleanFileKind.ZIP_CONTAINER
        if (mime == "image/avif") return CleanFileKind.AVIF
        if (mime == "image/heic" || mime == "image/heif") return CleanFileKind.HEIC
        if (mime.startsWith("audio/")) {
            return when {
                mime.contains("wav") -> CleanFileKind.WAV
                mime.contains("mpeg") || mime.contains("mp3") -> CleanFileKind.MP3
                mime.contains("flac") -> CleanFileKind.FLAC
                else -> CleanFileKind.UNSUPPORTED
            }
        }
        if (mime in setOf("audio/mp4", "video/mp4", "video/quicktime", "video/x-m4v", "video/3gpp", "video/3gpp2")) return CleanFileKind.MP4

        if (data != null) {
            when {
                startsWith(data, PNG_SIGNATURE) -> return CleanFileKind.PNG
                startsWith(data, JPEG_SIGNATURE) -> return CleanFileKind.JPEG
                startsWith(data, GIF87_SIGNATURE) || startsWith(data, GIF89_SIGNATURE) -> return CleanFileKind.GIF
                startsWith(data, BMP_SIGNATURE) -> return CleanFileKind.BMP
                startsWith(data, TIFF_LE_SIGNATURE) || startsWith(data, TIFF_BE_SIGNATURE) || startsWith(data, TIFF_LE_BIG_SIGNATURE) || startsWith(data, TIFF_BE_BIG_SIGNATURE) -> return CleanFileKind.TIFF
                startsWith(data, PDF_SIGNATURE) -> return CleanFileKind.PDF
                startsWith(data, RIFF_SIGNATURE) && data.size >= 12 && data.copyOfRange(8, 12).contentEquals(WAVE_SIGNATURE) -> return CleanFileKind.WAV
                startsWith(data, ID3_SIGNATURE) -> return CleanFileKind.MP3
                startsWith(data, FLAC_SIGNATURE) -> return CleanFileKind.FLAC
                startsWith(data, RIFF_SIGNATURE) && data.size >= 12 && data.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE) -> return CleanFileKind.WEBP
                data.size >= 12 && data.copyOfRange(4, 8).contentEquals(FTYP_SIGNATURE) -> {
                    val brands = String(data, 8, minOf(48, data.size - 8), Charsets.ISO_8859_1).lowercase(Locale.US)
                    return when {
                        brands.contains("avif") || brands.contains("avis") -> CleanFileKind.AVIF
                        brands.contains("heic") || brands.contains("heif") || brands.contains("mif1") -> CleanFileKind.HEIC
                        else -> CleanFileKind.MP4
                    }
                }
                startsWith(data, ZIP_SIGNATURE) || startsWith(data, ZIP_EMPTY_SIGNATURE) || startsWith(data, ZIP_CENTRAL_SIGNATURE) -> return CleanFileKind.ZIP_CONTAINER
                looksLikeUtf8Text(data) -> return CleanFileKind.TEXT
            }
        }
        return CleanFileKind.UNSUPPORTED
    }

    fun clean(
        displayName: String,
        mimeType: String?,
        input: ByteArray,
        aggressiveText: Boolean = false,
    ): NativeCleanOutput = clean(
        displayName,
        mimeType,
        input,
        NativeCleanOptions(aggressiveText = aggressiveText),
    )

    fun clean(
        displayName: String,
        mimeType: String?,
        input: ByteArray,
        options: NativeCleanOptions,
    ): NativeCleanOutput {
        val kind = classify(displayName, mimeType, input)
        return when (kind) {
            CleanFileKind.TEXT -> cleanText(input, displayName, mimeType, options)
            CleanFileKind.PNG -> cleanPng(input, options)
            CleanFileKind.JPEG -> cleanJpeg(input, options)
            CleanFileKind.WEBP -> cleanWebp(input, options)
            CleanFileKind.GIF -> cleanGif(input, options)
            CleanFileKind.BMP -> cleanBmp(input, options)
            CleanFileKind.WAV -> cleanWav(input, options)
            CleanFileKind.MP3 -> cleanMp3(input, options)
            CleanFileKind.FLAC -> cleanFlac(input, options)
            CleanFileKind.MP4, CleanFileKind.AVIF, CleanFileKind.HEIC -> cleanIsoBmff(input, kind, options)
            CleanFileKind.ZIP_CONTAINER -> cleanZip(input, displayName, options)
            CleanFileKind.TIFF -> cleanTiff(input, options)
            CleanFileKind.PDF -> cleanPdf(input, options)
            CleanFileKind.UNSUPPORTED -> NativeCleanOutput(
                input,
                NativeCleanReport(kind, input.size, input.size, false, emptyList(), warnings = listOf("Format non pris en charge sur Android.")),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Text and ZIP containers
    // ---------------------------------------------------------------------------------------------

    private fun cleanText(
        input: ByteArray,
        displayName: String,
        mimeType: String?,
        options: NativeCleanOptions,
    ): NativeCleanOutput {
        val hasUnicodeBom = (input.size >= 2 && ((input[0] == 0xFF.toByte() && input[1] == 0xFE.toByte()) || (input[0] == 0xFE.toByte() && input[1] == 0xFF.toByte()))) ||
            (input.size >= 3 && input[0] == 0xEF.toByte() && input[1] == 0xBB.toByte() && input[2] == 0xBF.toByte())
        if (!hasUnicodeBom && input.any { it == 0.toByte() }) {
            return NativeCleanOutput(
                input,
                NativeCleanReport(
                    CleanFileKind.TEXT,
                    input.size,
                    input.size,
                    false,
                    emptyList(),
                    warnings = listOf("Octets binaires détectés dans le texte : fichier conservé pour éviter une réécriture destructive."),
                ),
            )
        }
        val (text, charset, bom) = decodeText(input)
        val cleaned = TextUnicodeCleaner.clean(
            text,
            normalizeSpaces = options.normalizeSpaces,
            nfkc = options.nfkc,
            aggressiveConfusables = options.aggressiveText,
            stripEmojiGlue = options.stripEmojiGlue,
            stripBidi = options.stripBidi,
        )
        var resultText = cleaned.text
        val actions = mutableListOf<String>()
        val extension = extensionOf(displayName)
        val lowerMime = mimeType.orEmpty().lowercase(Locale.US)
        val document = when {
            extension in setOf("html", "htm", "xhtml") || lowerMime.contains("html") -> cleanHtmlDocument(resultText, options)
            extension in setOf("md", "markdown", "mdx") || lowerMime.contains("markdown") -> cleanMarkdownDocument(resultText, options)
            extension in setOf("tex", "ltx") -> cleanLatexDocument(resultText)
            extension == "svg" || lowerMime.contains("svg") || resultText.trimStart().startsWith("<svg", ignoreCase = true) -> cleanSvgDocument(resultText, options)
            extension in setOf("xml", "opf", "rels", "ncx") || lowerMime.contains("xml") -> cleanXmlDocument(resultText, options)
            extension in setOf("json", "jsonld", "map") || lowerMime.contains("json") -> cleanJsonDocument(resultText, options)
            else -> resultText to emptyList()
        }
        if (document.first != resultText) {
            resultText = document.first
            actions += document.second
        }
        if (cleaned.removedCount > 0) {
            val details = cleaned.removedCodePoints.entries.take(5).joinToString { "${it.key} ×${it.value}" }
            actions += "Retrait de ${cleaned.removedCount} caractère(s) Unicode invisible(s)${if (details.isBlank()) "" else " ($details)"}"
        }
        if (cleaned.replacedCount > 0) {
            val details = cleaned.replacedCodePoints.entries.take(5).joinToString { "${it.key} ×${it.value}" }
            actions += "Normalisation de ${cleaned.replacedCount} espace(s) ou caractère(s)${if (details.isBlank()) "" else " ($details)"}"
        }
        if (cleaned.changedByNfkc) actions += "Normalisation Unicode NFKC"
        val output = bom + resultText.toByteArray(charset)
        val residuals = findResidualMarkers(output)
        val warnings = buildList {
            if (residuals.isNotEmpty()) add(
                "Marqueurs encore présents après le nettoyage : ${residuals.joinToString(", ")}",
            )
            add("Seul le nettoyage déterministe Unicode et des métadonnées est exécuté : la réécriture IA des filigranes statistiques/token sampling n'est pas embarquée.")
        }
        return NativeCleanOutput(
            output,
            NativeCleanReport(
                CleanFileKind.TEXT,
                input.size,
                output.size,
                !input.contentEquals(output),
                actions,
                cleaned.removedCount,
                cleaned.replacedCount,
                warnings,
                residuals,
            ),
        )
    }

    private fun cleanXmlDocument(text: String, options: NativeCleanOptions): Pair<String, List<String>> {
        var result = text
        val actions = mutableListOf<String>()
        val metadata = stripXmlMetadata(result, options)
        if (metadata != result) actions += "Retrait des métadonnées XML"
        result = metadata

        val entities = cleanXmlEntityReferences(result, options)
        if (entities.first != result) {
            result = entities.first
            actions += entities.second
        }

        val doctype = Regex("""(?is)<!DOCTYPE(?:\[[\s\S]*?\]|[^>])*>""")
        val withoutDoctype = doctype.replace(result, "")
        if (withoutDoctype != result) actions += "Retrait de la déclaration XML/DOCTYPE"
        result = withoutDoctype

        val packetRe = Regex("""(?is)<\?xpacket.*?\?>""")
        val withoutPackets = packetRe.replace(result) { match ->
            if (options.stripAllMetadata || containsProvenanceMarker(match.value.lowercase(Locale.US))) {
                actions += "Retrait d'un paquet XMP XML"
                ""
            } else match.value
        }
        result = withoutPackets

        val commentRe = Regex("""(?s)<!--.*?-->""")
        result = commentRe.replace(result) { match ->
            if (containsProvenanceMarker(match.value.lowercase(Locale.US))) {
                actions += "Retrait d'un commentaire XML de provenance"
                ""
            } else match.value
        }
        val dataAiRe = Regex("""(?i)\sdata-(?:ai|c2pa|provenance)[\w-]*\s*=\s*["'][^"']*["']""")
        val withoutDataAi = dataAiRe.replace(result, "")
        if (withoutDataAi != result) actions += "Retrait des attributs XML de provenance"
        result = withoutDataAi

        val embedded = cleanEmbeddedDataImages(result, options)
        if (embedded.first != result) {
            result = embedded.first
            actions += embedded.second
        }
        return result to actions
    }

    private fun cleanXmlEntityReferences(text: String, options: NativeCleanOptions): Pair<String, List<String>> {
        val actions = mutableListOf<String>()
        val entityRe = Regex("""&#(?:x([0-9A-Fa-f]+)|([0-9]+));""")
        val result = entityRe.replace(text) { match ->
            val codePoint = runCatching {
                match.groupValues[1].takeIf { it.isNotEmpty() }?.toInt(16)
                    ?: match.groupValues[2].toInt()
            }.getOrNull() ?: return@replace match.value
            val replacement = when {
                codePoint in setOf(0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x202F, 0x205F, 0x3000) && options.normalizeSpaces -> " "
                codePoint in setOf(0x061C, 0x200E, 0x200F, 0x2066, 0x2067, 0x2068, 0x2069) && options.stripBidi -> ""
                codePoint in setOf(0x00AD, 0x034F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180C, 0x180D, 0x180E, 0x200B, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F, 0xFEFF) -> ""
                (codePoint == 0x200C || codePoint == 0x200D || codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF) && options.stripEmojiGlue -> ""
                else -> match.value
            }
            if (replacement != match.value) {
                actions += if (replacement.isEmpty()) "Retrait d'un caractère Unicode encodé en entité XML" else "Normalisation d'un espace encodé en entité XML"
            }
            replacement
        }
        return result to actions
    }

    private fun cleanJsonDocument(text: String, options: NativeCleanOptions): Pair<String, List<String>> {
        val metadataKeys = setOf(
            "author", "authors", "creator", "createdby", "created_with", "createdwith", "producer", "software",
            "generator", "subject", "keywords", "copyright", "creationdate", "createdate", "moddate",
            "provenance", "c2pa", "c2ma", "contentcredentials", "content_credentials", "aigc", "synthid", "model", "llm", "ai_model", "ai_model_version",
            "digitalsourcetype", "digital_source_type", "trainedalgorithmicmedia", "algorithmicmedia", "claim_generator", "claimgenerator", "generated_by", "generatedby", "tool", "engine",
        )
        val fieldRe = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"")
        val candidates = mutableListOf<Pair<Int, Int>>()
        val actions = mutableListOf<String>()
        for (match in fieldRe.findAll(text)) {
            val key = match.groupValues[1].lowercase(Locale.US).replace("-", "_")
            val valueStart = skipJsonWhitespace(text, match.range.last + 1)
            val valueEnd = findJsonValueEnd(text, valueStart)
            if (valueStart < 0 || valueEnd <= valueStart) continue
            val value = text.substring(valueStart, valueEnd)
            val drop = (options.stripAllMetadata && key in metadataKeys) ||
                containsProvenanceMarker(key) || containsProvenanceMarker(value.lowercase(Locale.US))
            if (!drop) continue
            var start = match.range.first
            var end = valueEnd
            var before = match.range.first - 1
            while (before >= 0 && text[before].isWhitespace()) before--
            if (before >= 0 && text[before] == ',') {
                // Prefer the separator before a field. This keeps adjacent
                // removals valid instead of leaving a comma from the first
                // removed member behind.
                start = before
            } else {
                val after = skipJsonWhitespace(text, valueEnd)
                if (after < text.length && text[after] == ',') end = after + 1
            }
            candidates += start to end
            actions += "Retrait du champ JSON $key"
        }
        if (candidates.isEmpty()) return text to emptyList()

        // A parent provenance object can also contain matching child keys. Keep
        // the largest range so reverse replacements never use stale offsets.
        val selected = candidates.filter { candidate ->
            candidates.none { other ->
                other !== candidate && other.first <= candidate.first && other.second >= candidate.second &&
                    (other.first < candidate.first || other.second > candidate.second)
            }
        }.distinct()
        var result = text
        selected.sortedByDescending { it.first }.forEach { (start, end) ->
            result = result.removeRange(start.coerceAtLeast(0), end.coerceAtMost(result.length))
        }
        return result to actions.distinct()
    }

    private fun skipJsonWhitespace(text: String, start: Int): Int {
        var index = start.coerceAtLeast(0)
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun findJsonValueEnd(text: String, start: Int): Int {
        if (start < 0 || start >= text.length) return -1
        return when (text[start]) {
            '"' -> {
                var index = start + 1
                var escaped = false
                while (index < text.length) {
                    val char = text[index]
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') return index + 1
                    index++
                }
                -1
            }
            '{', '[' -> {
                val open = text[start]
                val close = if (open == '{') '}' else ']'
                var depth = 0
                var index = start
                var inString = false
                var escaped = false
                while (index < text.length) {
                    val char = text[index]
                    if (inString) {
                        if (escaped) escaped = false
                        else if (char == '\\') escaped = true
                        else if (char == '"') inString = false
                    } else {
                        when (char) {
                            '"' -> inString = true
                            open -> depth++
                            close -> {
                                depth--
                                if (depth == 0) return index + 1
                            }
                        }
                    }
                    index++
                }
                -1
            }
            else -> {
                var index = start
                while (index < text.length && text[index] !in ",}]\r\n") index++
                index
            }
        }
    }

    private fun cleanZip(input: ByteArray, displayName: String, options: NativeCleanOptions): NativeCleanOutput {
        val output = ByteArrayOutputStream(input.size)
        val actions = mutableListOf<String>()
        var entries = 0
        var uncompressed = 0L
        var failed = false
        try {
            ZipInputStream(ByteArrayInputStream(input)).use { zin ->
                ZipOutputStream(output).use { zout ->
                    while (true) {
                        val entry = zin.nextEntry ?: break
                        entries++
                        if (entries > MAX_ZIP_ENTRIES) throw IOException("too many archive entries")
                        val content = readZipEntry(zin, MAX_ZIP_UNCOMPRESSED - uncompressed)
                        uncompressed += content.size
                        if (uncompressed > MAX_ZIP_UNCOMPRESSED) throw IOException("archive is too large")
                        val name = entry.name
                        val lower = name.lowercase(Locale.US)
                        var cleaned = content

                        if (!entry.isDirectory) {
                            val memberKind = classify(name, null, content)
                            val canCleanMember = memberKind in setOf(
                                CleanFileKind.TEXT, CleanFileKind.PNG, CleanFileKind.JPEG, CleanFileKind.WEBP,
                                CleanFileKind.GIF, CleanFileKind.BMP, CleanFileKind.TIFF, CleanFileKind.PDF,
                                CleanFileKind.WAV, CleanFileKind.MP3, CleanFileKind.FLAC,
                                CleanFileKind.MP4, CleanFileKind.AVIF, CleanFileKind.HEIC,
                            )
                            if (canCleanMember) {
                                val nested = clean(name, null, content, options)
                                if (nested.report.changed) actions += "Nettoyage de $name"
                                cleaned = nested.bytes
                            }
                        }
                        val outEntry = ZipEntry(entry)
                        if (lower == "mimetype") {
                            // EPUB requires this first member to be STORED.
                            outEntry.method = ZipEntry.STORED
                            outEntry.size = cleaned.size.toLong()
                            outEntry.compressedSize = cleaned.size.toLong()
                            outEntry.crc = crc32(cleaned)
                        } else if (outEntry.method == ZipEntry.STORED && !cleaned.contentEquals(content)) {
                            outEntry.size = cleaned.size.toLong()
                            outEntry.compressedSize = cleaned.size.toLong()
                            outEntry.crc = crc32(cleaned)
                        }
                        zout.putNextEntry(outEntry)
                        zout.write(cleaned)
                        zout.closeEntry()
                        zin.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            failed = true
        }

        if (failed) {
            return NativeCleanOutput(
                input,
                NativeCleanReport(
                    CleanFileKind.ZIP_CONTAINER,
                    input.size,
                    input.size,
                    false,
                    emptyList(),
                    warnings = listOf("Archive illisible ou trop volumineuse : aucun octet n'a été modifié."),
                ),
            )
        }
        actions.removeAll { it.isEmpty() }
        if (actions.isEmpty()) {
            return NativeCleanOutput(
                input,
                NativeCleanReport(CleanFileKind.ZIP_CONTAINER, input.size, input.size, false, emptyList()),
            )
        }
        val bytes = output.toByteArray()
        val residuals = findResidualMarkers(bytes)
        val warnings = if (residuals.isEmpty()) emptyList() else listOf(
            "Marqueurs encore présents dans l'archive : ${residuals.joinToString(", ")}",
        )
        return NativeCleanOutput(
            bytes,
            NativeCleanReport(
                CleanFileKind.ZIP_CONTAINER,
                input.size,
                bytes.size,
                !input.contentEquals(bytes),
                actions.distinct(),
                warnings = warnings,
                residualMarkers = residuals,
            ),
        )
    }

    private fun readZipEntry(input: ZipInputStream, remainingBudget: Long): ByteArray {
        if (remainingBudget < 0) throw IOException("archive is too large")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > remainingBudget) throw IOException("archive is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun stripXmlMetadata(text: String, options: NativeCleanOptions = NativeCleanOptions()): String {
        var result = text
        val elementNames = listOf(
            "dc:creator", "dc:title", "dc:description", "dc:publisher", "dc:subject", "dc:rights", "dc:language", "dc:date",
            "cp:keywords", "cp:lastModifiedBy", "cp:revision", "cp:category", "cp:contentStatus", "cp:version", "cp:identifier",
            "dcterms:created", "dcterms:modified", "meta:generator", "meta:initial-creator", "meta:creation-date", "meta:editing-duration",
            "meta:editing-cycles", "meta:printed-by", "meta:print-date", "meta:document-statistic", "meta:user-defined", "office:meta",
            "xmp:CreatorTool", "xmp:MetadataDate", "xmpMM:InstanceID", "photoshop:Credit", "rdf:Description",
            "Application", "Company", "Manager", "HyperlinkBase", "LastPrinted", "AppVersion",
        )
        for (name in elementNames) {
            val blockRe = Regex("""(?is)<$name\b[^>]*>.*?</$name\s*>""")
            result = blockRe.replace(result) { match ->
                if (options.stripAllMetadata || containsProvenanceMarker(match.value.lowercase(Locale.US)) || containsAiVendorMarker(match.value)) "" else match.value
            }
            val selfClosingRe = Regex("""(?is)<$name\b[^>]*/\s*>""")
            result = selfClosingRe.replace(result) { match ->
                if (options.stripAllMetadata || containsProvenanceMarker(match.value.lowercase(Locale.US)) || containsAiVendorMarker(match.value)) "" else match.value
            }
        }
        // SVG metadata is a dedicated element; removing it does not touch the
        // visible vector paths.
        result = Regex("""(?is)<metadata\b[^>]*>.*?</metadata\s*>""").replace(result) { match ->
            if (options.stripAllMetadata || containsProvenanceMarker(match.value.lowercase(Locale.US)) || containsAiVendorMarker(match.value)) "" else match.value
        }
        // HTML meta tags are only removed when their attributes clearly identify
        // provenance or generator information; ordinary viewport/charset tags stay.
        result = Regex("""(?is)<meta\b[^>]*(?:generator|author|creator|provenance|c2pa|ai-generated)[^>]*/?\s*>""").replace(result) { match ->
            if (options.stripAllMetadata || containsProvenanceMarker(match.value.lowercase(Locale.US)) || containsAiVendorMarker(match.value)) "" else match.value
        }
        return result
    }

    private fun cleanHtmlDocument(text: String, options: NativeCleanOptions): Pair<String, List<String>> {
        var result = text
        val actions = mutableListOf<String>()

        val xmlCleaned = stripXmlMetadata(result, options)
        if (xmlCleaned != result) actions += "Retrait des métadonnées HTML/XML"
        result = xmlCleaned

        val metaRe = Regex("""(?is)<meta\b[^>]*>""")
        result = metaRe.replace(result) { match ->
            val tag = match.value
            val lower = tag.lowercase(Locale.US)
            val declaredGenerator = Regex(
                """(?i)(?:name|property|scheme|http-equiv)\s*=\s*["'](?:generator|creator|producer|software|created[_ -]?with)["']""",
            ).containsMatchIn(tag)
            val aiNamed = containsProvenanceMarker(lower) ||
                (declaredGenerator && Regex(
                    """(?i)(?:claude|anthropic|openai|chatgpt|gemini|synthid|copilot|midjourney|dall.?e|stable.?diffusion)""",
                ).containsMatchIn(tag))
            if (aiNamed) {
                actions += "Retrait d'une balise meta HTML de provenance"
                ""
            } else tag
        }

        val jsonLdRe = Regex(
            """(?is)<script\b[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>.*?</script\s*>""",
        )
        result = jsonLdRe.replace(result) { match ->
            if (containsProvenanceMarker(match.value.lowercase(Locale.US))) {
                actions += "Retrait d'un bloc JSON-LD de provenance"
                ""
            } else match.value
        }

        val dataAiRe = Regex("""(?i)\sdata-(?:ai|c2pa|provenance)[\w-]*\s*=\s*["'][^"']*["']""")
        val withoutDataAi = dataAiRe.replace(result, "")
        if (withoutDataAi != result) actions += "Retrait des attributs HTML de provenance"
        result = withoutDataAi

        val embedded = cleanEmbeddedDataImages(result, options)
        if (embedded.first != result) {
            result = embedded.first
            actions += embedded.second
        }
        return result to actions
    }

    private fun cleanMarkdownDocument(text: String, options: NativeCleanOptions): Pair<String, List<String>> {
        val actions = mutableListOf<String>()
        var result = text

        val lineEnd = when {
            result.startsWith("---\r\n") -> "\r\n"
            result.startsWith("---\n") -> "\n"
            else -> null
        }
        if (lineEnd != null) {
            val close = result.indexOf("${lineEnd}---", 3 + lineEnd.length)
            if (close >= 0) {
                val blockEnd = close + lineEnd.length + 3
                val lines = result.substring(3 + lineEnd.length, close).split(Regex("\\r?\\n"))
                val kept = mutableListOf<String>()
                var droppingContinuation = false
                val keyRe = Regex("""^([A-Za-z0-9_.-]+)\s*:\s*(.*)$""")
                val keys = setOf(
                    "generator", "ai", "ai_generated", "ai-generated", "claude", "anthropic", "openai",
                    "gemini", "synthid", "c2pa", "content_credentials", "contentcredentials", "provenance",
                    "digital_source_type", "digitalsourcetype", "created_with", "createdwith", "model", "llm",
                )
                for (line in lines) {
                    val match = keyRe.matchEntire(line)
                    if (match != null && !line.startsWith(" ") && !line.startsWith("\t")) {
                        val key = match.groupValues[1].lowercase(Locale.US)
                        val value = match.groupValues[2].lowercase(Locale.US)
                        droppingContinuation = key in keys || containsProvenanceMarker(value)
                        if (droppingContinuation) actions += "Retrait de la clé frontmatter $key" else kept += line
                    } else if (!droppingContinuation) {
                        kept += line
                    }
                }
                val body = result.substring(blockEnd).removePrefix(lineEnd)
                result = if (kept.isEmpty()) {
                    body.trimStart('\r', '\n')
                } else {
                    "---$lineEnd${kept.joinToString(lineEnd)}$lineEnd---$lineEnd$body"
                }
            }
        }

        val commentRe = Regex("""(?is)<!--.*?-->""")
        result = commentRe.replace(result) { match ->
            if (containsProvenanceMarker(match.value.lowercase(Locale.US))) {
                actions += "Retrait d'un commentaire Markdown de provenance"
                ""
            } else match.value
        }
        val embedded = cleanEmbeddedDataImages(result, options)
        if (embedded.first != result) {
            result = embedded.first
            actions += embedded.second
        }
        return result to actions
    }

    private fun cleanLatexDocument(text: String): Pair<String, List<String>> {
        val actions = mutableListOf<String>()
        val commandStartRe = Regex("""\\(hypersetup|pdfinfo)\b\s*\{""")
        val output = StringBuilder(text.length)
        var copiedUntil = 0
        var search = 0
        while (true) {
            val match = commandStartRe.find(text, search) ?: break
            if (isLatexCommentPosition(text, match.range.first) || isInsideLatexLiteral(text, match.range.first)) {
                search = match.range.last + 1
                continue
            }
            val openBrace = text.indexOf('{', match.range.first)
            val closeBrace = findBalancedBrace(text, openBrace)
            if (openBrace < 0 || closeBrace < 0) {
                search = match.range.last + 1
                continue
            }
            output.append(text, copiedUntil, match.range.first)
            val command = match.groupValues[1]
            val argument = text.substring(openBrace + 1, closeBrace)
            val kept = if (command.equals("pdfinfo", ignoreCase = true)) {
                cleanPdfInfoOptions(argument, actions)
            } else {
                splitLatexOptions(argument).mapNotNull { item ->
                    val trimmed = item.trim()
                    if (trimmed.isEmpty()) return@mapNotNull null
                    val key = trimmed.substringBefore('=').trim().removePrefix("/").lowercase(Locale.US)
                    val value = trimmed.substringAfter('=', "").lowercase(Locale.US)
                    val removableKeys = setOf(
                        "pdfauthor", "pdfsubject", "pdfcreator", "pdfproducer", "pdfkeywords", "pdfcreationdate", "pdfmoddate",
                        "author", "subject", "creator", "producer", "keywords", "creationdate", "moddate",
                    )
                    val clear = key in removableKeys || containsProvenanceMarker(key) || containsProvenanceMarker(value)
                    if (clear) {
                        actions += "Retrait de la métadonnée LaTeX $key"
                        null
                    } else trimmed
                }
            }
            if (kept.isNotEmpty()) {
                output.append('\\').append(command).append('{').append(kept.joinToString(if (command.equals("pdfinfo", true)) " " else ", ")).append('}')
            } else {
                actions += "Retrait du bloc LaTeX $command"
            }
            copiedUntil = closeBrace + 1
            search = copiedUntil
        }
        output.append(text, copiedUntil, text.length)

        val lines = output.toString().split(Regex("(?<=\\n)"))
        var inVerbatim = false
        val cleanedLines = lines.filterNot { line ->
            val lower = line.trimStart().lowercase(Locale.US)
            val beginsVerbatim = Regex("""\\begin\{(verbatim\\*?|lstlisting\\*?|minted|verbatim)\}""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
            val endsVerbatim = Regex("""\\end\{(verbatim\\*?|lstlisting\\*?|minted|verbatim)\}""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
            if (inVerbatim || beginsVerbatim) {
                if (endsVerbatim) inVerbatim = false
                false
            } else if (!lower.startsWith("%")) {
                false
            } else {
                val drop = lower.contains("!tex") || lower.contains("-*-") || lower.contains("vim:") || containsProvenanceMarker(lower)
                if (drop) actions += "Retrait d'un commentaire LaTeX de configuration"
                drop
            }
        }
        return cleanedLines.joinToString("") to actions
    }

    private fun splitLatexOptions(argument: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var escaped = false
        var inComment = false
        var start = 0
        argument.forEachIndexed { index, char ->
            if (inComment) {
                if (char == '\n' || char == '\r') inComment = false
            } else if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '%') {
                inComment = true
            } else if (char == '{') {
                depth++
            } else if (char == '}' && depth > 0) {
                depth--
            } else if (char == ',' && depth == 0) {
                result += argument.substring(start, index)
                start = index + 1
            }
        }
        result += argument.substring(start)
        return result
    }

    private fun cleanPdfInfoOptions(argument: String, actions: MutableList<String>): List<String> {
        val result = mutableListOf<String>()
        val tokenRe = Regex("""(?s)(/\s*[A-Za-z][A-Za-z0-9_-]*)\s*(\([^)]*\)|<[^>]*>|[^/\s]+)""")
        var cursor = 0
        for (match in tokenRe.findAll(argument)) {
            if (match.range.first > cursor && argument.substring(cursor, match.range.first).isNotBlank()) {
                result += argument.substring(cursor, match.range.first).trim()
            }
            val key = match.groupValues[1].removePrefix("/").trim().lowercase(Locale.US)
            val value = match.groupValues[2]
            val removable = setOf("author", "subject", "creator", "producer", "keywords", "creationdate", "moddate")
            if (key in removable || containsProvenanceMarker(key) || containsProvenanceMarker(value.lowercase(Locale.US))) {
                actions += "Retrait de la métadonnée LaTeX $key"
            } else {
                result += match.value.trim()
            }
            cursor = match.range.last + 1
        }
        if (cursor < argument.length && argument.substring(cursor).isNotBlank()) result += argument.substring(cursor).trim()
        if (result.isEmpty() && argument.isNotBlank() && actions.isEmpty()) result += argument.trim()
        return result
    }

    private fun isLatexCommentPosition(text: String, offset: Int): Boolean {
        var index = offset - 1
        var backslashes = 0
        while (index >= 0 && text[index] == '\\') {
            backslashes++
            index--
        }
        if (backslashes % 2 != 0) return true
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val comment = text.indexOf('%', lineStart)
        if (comment < 0 || comment >= offset) return false
        var before = comment - 1
        var count = 0
        while (before >= lineStart && text[before] == '\\') {
            count++
            before--
        }
        return count % 2 == 0
    }

    private fun findBalancedBrace(text: String, openBrace: Int): Int {
        if (openBrace < 0 || openBrace >= text.length || text[openBrace] != '{') return -1
        var depth = 0
        var escaped = false
        var inComment = false
        for (index in openBrace until text.length) {
            val char = text[index]
            if (inComment) {
                if (char == '\n' || char == '\r') inComment = false
                continue
            }
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '%') {
                inComment = true
            } else if (char == '{') {
                depth++
            } else if (char == '}') {
                depth--
                if (depth == 0) return index
            }
        }
        return -1
    }

    private fun isInsideLatexLiteral(text: String, offset: Int): Boolean {
        val beginEndRe = Regex("""\\(?:begin|end)\{(?:verbatim\\*?|lstlisting\\*?|minted)\}""", RegexOption.IGNORE_CASE)
        var inVerbatim = false
        for (match in beginEndRe.findAll(text)) {
            if (match.range.first >= offset) break
            if (match.value.lowercase(Locale.US).startsWith("\\begin")) inVerbatim = true else inVerbatim = false
        }
        if (inVerbatim) return true

        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val line = text.substring(lineStart, offset)
        val verbRe = Regex("""\\(?:verb|lstinline)\*?\s*(.)""", RegexOption.IGNORE_CASE)
        for (match in verbRe.findAll(line)) {
            val delimiter = match.groupValues[1].firstOrNull() ?: continue
            val end = line.indexOf(delimiter, match.range.last + 1)
            if (end < 0) return true
            val absoluteEnd = lineStart + end
            if (offset <= absoluteEnd) return true
        }
        return false
    }

    private fun cleanSvgDocument(text: String, options: NativeCleanOptions): Pair<String, List<String>> {
        val actions = mutableListOf<String>()
        var result = text
        val before = result
        result = stripXmlMetadata(result, options)
        if (result != before) actions += "Retrait des blocs SVG/XMP metadata"

        val doctype = Regex("""(?is)<!DOCTYPE(?:\[[\s\S]*?\]|[^>])*>""")
        result = doctype.replace(result) {
            actions += "Retrait de la déclaration SVG DOCTYPE"
            ""
        }
        val commentRe = Regex("""(?s)<!--.*?-->""")
        result = commentRe.replace(result) { comment ->
            if (containsProvenanceMarker(comment.value.lowercase(Locale.US)) || containsAiVendorMarker(comment.value)) {
                actions += "Retrait d'un commentaire SVG de provenance"
                ""
            } else comment.value
        }
        val rootRe = Regex("""(?is)<svg\b[^>]*>""")
        val rootMatch = rootRe.find(result)
        if (rootMatch != null) {
            val attrRe = Regex(
                """(?i)\s((?:data-(?:ai|c2pa|provenance)[\w-]*|(?:ai|c2pa|provenance|generator|creator|software)))\s*=\s*(["'])([^"']*)\2""",
            )
            val stripped = attrRe.replace(rootMatch.value) { attr ->
                val key = attr.groupValues[1].lowercase(Locale.US)
                val value = attr.groupValues[3]
                val drop = options.stripAllMetadata || key.startsWith("data-") ||
                    containsProvenanceMarker(key) || containsAiVendorMarker(value)
                if (drop) "" else attr.value
            }
            if (stripped != rootMatch.value) {
                actions += "Retrait des attributs SVG de provenance"
                result = result.replaceRange(rootMatch.range, stripped)
            }
        }
        val embedded = cleanEmbeddedDataImages(result, options)
        if (embedded.first != result) {
            result = embedded.first
            actions += embedded.second
        }
        return result to actions
    }

    private fun cleanEmbeddedDataImages(text: String, options: NativeCleanOptions): Pair<String, List<String>> {
        val actions = mutableListOf<String>()
        val imageRe = Regex(
            """(?is)data:image/(png|jpe?g|webp|gif|bmp|tiff?|avif|heic|svg\+xml)((?:;[^,"'<>]*)?),([a-z0-9+/=%\r\n]+)""",
        )
        val result = imageRe.replace(text) { match ->
            val mime = match.groupValues[1]
            val params = match.groupValues[2]
            val encoded = match.groupValues[3]
            val isBase64 = params.contains("base64", ignoreCase = true)
            val bytes = if (isBase64) {
                decodeBase64(encoded.replace(Regex("""\s+"""), ""))
            } else {
                percentDecode(encoded)
            } ?: return@replace match.value
            val nested = clean("embedded.$mime", "image/$mime", bytes, options)
            if (!nested.report.changed) return@replace match.value
            actions += "Nettoyage d'une image data URI $mime"
            val rebuilt = if (isBase64) encodeBase64(nested.bytes) else percentEncode(nested.bytes)
            "data:image/$mime$params,$rebuilt"
        }
        return result to actions
    }

    // ---------------------------------------------------------------------------------------------
    // PNG / JPEG / WebP / GIF
    // ---------------------------------------------------------------------------------------------

    private fun cleanPng(input: ByteArray, options: NativeCleanOptions = NativeCleanOptions()): NativeCleanOutput {
        if (!startsWith(input, PNG_SIGNATURE)) return unsupportedImage(input, CleanFileKind.PNG, "PNG invalide")
        val out = ByteArrayOutputStream(input.size)
        out.write(PNG_SIGNATURE)
        var pos = 8
        val actions = mutableListOf<String>()
        while (pos + 12 <= input.size) {
            val length = readBe32(input, pos)
            if (length < 0 || pos.toLong() + 12L + length > input.size) {
                out.write(input, pos, input.size - pos)
                actions += "Fin PNG tronquée conservée"
                break
            }
            val type = input.copyOfRange(pos + 4, pos + 8)
            val payload = input.copyOfRange(pos + 8, pos + 8 + length)
            val name = String(type, Charsets.ISO_8859_1)
            val lower = name.lowercase(Locale.US)
            val metadataChunk = name in setOf("tEXt", "zTXt", "iTXt", "eXIf", "caBX", "iCCP")
            val provenanceChunk = lower.startsWith("c2") || lower == "jumb"
            val markerHit = pngTextLooksProvenance(name, payload)
            val drop = provenanceChunk || (options.stripAllMetadata && metadataChunk) ||
                (!options.stripAllMetadata && metadataChunk && markerHit)
            if (drop) {
                actions += "Retrait du bloc PNG $name"
            } else {
                out.write(input, pos, 12 + length)
            }
            pos += 12 + length
            if (name == "IEND") break
        }
        if (pos < input.size && !actions.any { it.startsWith("Fin PNG") }) out.write(input, pos, input.size - pos)
        return outputFor(CleanFileKind.PNG, input, out.toByteArray(), actions)
    }

    private fun cleanJpeg(input: ByteArray, options: NativeCleanOptions = NativeCleanOptions()): NativeCleanOutput {
        if (!startsWith(input, JPEG_SIGNATURE)) return unsupportedImage(input, CleanFileKind.JPEG, "JPEG invalide")
        val out = ByteArrayOutputStream(input.size)
        out.write(JPEG_SIGNATURE)
        val actions = mutableListOf<String>()
        var pos = 2
        while (pos < input.size) {
            if (input[pos].toInt() and 0xFF != 0xFF) {
                out.write(input, pos, input.size - pos)
                break
            }
            val markerStart = pos
            while (pos < input.size && input[pos].toInt() and 0xFF == 0xFF) pos++
            if (pos >= input.size) break
            val marker = input[pos++].toInt() and 0xFF
            if (marker == 0xD9) {
                out.write(0xFF)
                out.write(marker)
                break
            }
            if (marker == 0xDA) {
                // Entropy-coded data may contain arbitrary FF bytes. Once SOS
                // starts, copying the rest is safer than trying to parse it.
                out.write(input, markerStart, input.size - markerStart)
                break
            }
            if (marker in 0xD0..0xD7 || marker == 0x01) {
                out.write(0xFF)
                out.write(marker)
                continue
            }
            if (pos + 2 > input.size) {
                out.write(input, markerStart, input.size - markerStart)
                break
            }
            val segmentLength = readBe16(input, pos)
            if (segmentLength < 2 || pos + segmentLength > input.size) {
                out.write(input, markerStart, input.size - markerStart)
                break
            }
            val markerHit = containsProvenanceMarker(String(input, pos, segmentLength, Charsets.ISO_8859_1).lowercase(Locale.US))
            val metadataSegment = marker == 0xFE || (marker in 0xE0..0xEF && marker != 0xE0)
            val drop = metadataSegment && (options.stripAllMetadata || markerHit)
            if (drop) actions += "Retrait du segment JPEG ${if (marker == 0xFE) "COM" else "APP${marker - 0xE0}"}"
            else out.write(input, markerStart, 2 + segmentLength)
            pos += segmentLength
        }
        return outputFor(CleanFileKind.JPEG, input, out.toByteArray(), actions)
    }

    private fun cleanWebp(input: ByteArray, options: NativeCleanOptions = NativeCleanOptions()): NativeCleanOutput {
        if (input.size < 12 || !startsWith(input, RIFF_SIGNATURE) || !input.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)) {
            return unsupportedImage(input, CleanFileKind.WEBP, "WebP invalide")
        }
        val outBody = ByteArrayOutputStream(input.size - 8)
        outBody.write(WEBP_SIGNATURE)
        val actions = mutableListOf<String>()
        val metadataFlags = mapOf("ICCP" to 0x20, "EXIF" to 0x08, "XMP " to 0x04)
        var removedFlags = 0
        var scan = 12
        while (scan + 8 <= input.size) {
            val scanSize = readLe32(input, scan + 4)
            if (scanSize < 0 || scan.toLong() + 8L + scanSize + (scanSize and 1) > input.size) break
            val scanName = String(input, scan, 4, Charsets.ISO_8859_1)
            val scanPayload = input.copyOfRange(scan + 8, scan + 8 + scanSize)
            val scanMarker = containsProvenanceMarker(String(scanPayload, Charsets.ISO_8859_1).lowercase(Locale.US))
            if (scanName in metadataFlags && (options.stripAllMetadata || scanMarker) ||
                scanName.uppercase(Locale.US) == "C2PA" || scanName.uppercase(Locale.US) == "JUMB") {
                removedFlags = removedFlags or (metadataFlags[scanName] ?: 0)
            }
            scan += 8 + scanSize + (scanSize and 1)
        }
        var pos = 12
        while (pos + 8 <= input.size) {
            val type = input.copyOfRange(pos, pos + 4)
            val size = readLe32(input, pos + 4)
            if (size < 0 || pos.toLong() + 8L + size + (size and 1) > input.size) {
                outBody.write(input, pos, input.size - pos)
                pos = input.size
                break
            }
            var payload = input.copyOfRange(pos + 8, pos + 8 + size)
            val name = String(type, Charsets.ISO_8859_1)
            val upperName = name.uppercase(Locale.US)
            val metadataChunk = name in setOf("EXIF", "XMP ", "ICCP")
            val provenanceChunk = upperName == "C2PA" || upperName == "JUMB"
            val markerHit = containsProvenanceMarker(String(payload, Charsets.ISO_8859_1).lowercase(Locale.US))
            val drop = provenanceChunk || (options.stripAllMetadata && metadataChunk) ||
                (!options.stripAllMetadata && metadataChunk && markerHit)
            if (drop) actions += "Retrait du bloc WebP $name"
            else {
                if (name == "VP8X" && payload.isNotEmpty() && removedFlags != 0) {
                    payload = payload.copyOf()
                    payload[0] = (payload[0].toInt() and removedFlags.inv()).toByte()
                }
                outBody.write(type)
                outBody.write(le32(size))
                outBody.write(payload)
                if ((size and 1) != 0) outBody.write(0)
            }
            pos += 8 + size + (size and 1)
        }
        if (pos < input.size) outBody.write(input, pos, input.size - pos)
        val body = outBody.toByteArray()
        val result = ByteArrayOutputStream(body.size + 8)
        result.write(RIFF_SIGNATURE)
        result.write(le32(body.size))
        result.write(body)
        return outputFor(CleanFileKind.WEBP, input, result.toByteArray(), actions)
    }

    private fun cleanGif(input: ByteArray, options: NativeCleanOptions = NativeCleanOptions()): NativeCleanOutput {
        if (!(startsWith(input, GIF87_SIGNATURE) || startsWith(input, GIF89_SIGNATURE)) || input.size < 13) {
            return unsupportedImage(input, CleanFileKind.GIF, "GIF invalide")
        }
        val out = ByteArrayOutputStream(input.size)
        val actions = mutableListOf<String>()
        var pos = 0
        // Header + logical screen descriptor + optional global colour table.
        out.write(input, 0, 13)
        pos = 13
        val packed = input[10].toInt() and 0xFF
        if ((packed and 0x80) != 0) {
            val table = 3 * (1 shl ((packed and 0x07) + 1))
            if (pos + table > input.size) return unsupportedImage(input, CleanFileKind.GIF, "GIF tronqué")
            out.write(input, pos, table)
            pos += table
        }
        while (pos < input.size) {
            when (input[pos].toInt() and 0xFF) {
                0x3B -> {
                    out.write(input, pos, input.size - pos)
                    pos = input.size
                }
                0x21 -> {
                    val end = gifExtensionEnd(input, pos)
                    if (end == null) {
                        out.write(input, pos, input.size - pos)
                        pos = input.size
                    } else {
                        val label = input[pos + 1].toInt() and 0xFF
                        val payload = input.copyOfRange(pos + 2, end)
                        val appId = if (label == 0xFF && pos + 3 + 11 <= end && (input[pos + 2].toInt() and 0xFF) >= 11) String(input, pos + 3, 11, Charsets.ISO_8859_1) else ""
                        val markerHit = containsProvenanceMarker(String(payload, Charsets.ISO_8859_1).lowercase(Locale.US))
                        val drop = when {
                            label == 0xFE -> options.stripAllMetadata || markerHit
                            label != 0xFF -> false
                            appId == "XMP DataXMP" -> options.stripAllMetadata || markerHit
                            appId == "NETSCAPE2.0" || appId == "ICCRGBG1012" -> markerHit
                            else -> options.stripAllMetadata || markerHit
                        }
                        if (drop) actions += "Retrait d'une extension GIF de métadonnées" else out.write(input, pos, end - pos)
                        pos = end
                    }
                }
                0x2C -> {
                    val end = gifImageEnd(input, pos)
                    if (end == null) {
                        out.write(input, pos, input.size - pos)
                        pos = input.size
                    } else {
                        out.write(input, pos, end - pos)
                        pos = end
                    }
                }
                else -> {
                    out.write(input[pos].toInt())
                    pos++
                }
            }
        }
        return outputFor(CleanFileKind.GIF, input, out.toByteArray(), actions)
    }

    // ---------------------------------------------------------------------------------------------
    // BMP / TIFF / PDF (formats without an Android metadata writer)
    // ---------------------------------------------------------------------------------------------

    /** Truncates non-standard bytes after the BMP pixel payload and fixes bfSize. */
    private fun cleanBmp(input: ByteArray, options: NativeCleanOptions): NativeCleanOutput {
        if (!startsWith(input, BMP_SIGNATURE) || input.size < 26) {
            return unsupportedImage(input, CleanFileKind.BMP, "BMP invalide")
        }
        val pixelOffset = readLe32(input, 10)
        val dibSize = readLe32(input, 14)
        if (pixelOffset < 14 || dibSize < 12 || 14L + dibSize > input.size) {
            return unsupportedImage(input, CleanFileKind.BMP, "En-tête BMP incomplet")
        }

        val width: Long
        val height: Long
        val bitsPerPixel: Int
        val compression: Int
        if (dibSize == 12) {
            width = readLe16(input, 18).toLong()
            height = readLe16(input, 20).toLong()
            bitsPerPixel = readLe16(input, 24)
            compression = 0
        } else {
            if (14 + 40 > input.size) return unsupportedImage(input, CleanFileKind.BMP, "DIB BMP incomplet")
            width = kotlin.math.abs(readLe32(input, 18).toLong())
            height = kotlin.math.abs(readLe32(input, 22).toLong())
            bitsPerPixel = readLe16(input, 28)
            compression = readLe32(input, 30)
        }
        // RLE/JPEG/PNG-compressed BMPs do not expose a safely computable pixel
        // extent here. Preserving them is safer than cutting their payload.
        if (width <= 0 || height <= 0 || bitsPerPixel <= 0 || compression !in setOf(0, 3, 6)) {
            return NativeCleanOutput(input, NativeCleanReport(CleanFileKind.BMP, input.size, input.size, false, emptyList(), warnings = listOf("BMP compressé : fichier conservé.")))
        }
        val rowBytes = ((width * bitsPerPixel + 31L) / 32L) * 4L
        val payloadEnd = pixelOffset.toLong() + rowBytes * height
        if (payloadEnd > input.size || payloadEnd < 0) {
            return NativeCleanOutput(input, NativeCleanReport(CleanFileKind.BMP, input.size, input.size, false, emptyList(), warnings = listOf("Extension BMP impossible à déterminer : fichier conservé.")))
        }
        if (payloadEnd == input.size.toLong()) return outputFor(CleanFileKind.BMP, input, input, emptyList())
        val trailing = input.copyOfRange(payloadEnd.toInt(), input.size)
        val markerHit = containsProvenanceMarker(String(trailing, Charsets.ISO_8859_1).lowercase(Locale.US))
        if (!options.stripAllMetadata && !markerHit) {
            return NativeCleanOutput(
                input,
                NativeCleanReport(
                    CleanFileKind.BMP,
                    input.size,
                    input.size,
                    false,
                    emptyList(),
                    warnings = listOf("Octets BMP supplémentaires conservés en mode sélectif."),
                ),
            )
        }
        val output = input.copyOf(payloadEnd.toInt())
        copyInto(output, 2, le32(output.size))
        return outputFor(CleanFileKind.BMP, input, output, listOf("Retrait des ${input.size - output.size} octets BMP après l'image"))
    }

    /**
     * Removes descriptive TIFF IFD entries in place. No strip/tile payload is
     * moved, so offsets used by decoders remain valid for classic TIFF and
     * BigTIFF. Exif/GPS sub-IFDs are recursively treated as metadata.
     */
    private fun cleanTiff(input: ByteArray, options: NativeCleanOptions): NativeCleanOutput {
        val layout = when {
            input.size >= 8 && input[0] == 'I'.code.toByte() && input[1] == 'I'.code.toByte() && input[2] == 42.toByte() && input[3] == 0.toByte() -> false to false
            input.size >= 8 && input[0] == 'M'.code.toByte() && input[1] == 'M'.code.toByte() && input[2] == 0.toByte() && input[3] == 42.toByte() -> true to false
            input.size >= 16 && input[0] == 'I'.code.toByte() && input[1] == 'I'.code.toByte() && input[2] == 43.toByte() && input[3] == 0.toByte() -> false to true
            input.size >= 16 && input[0] == 'M'.code.toByte() && input[1] == 'M'.code.toByte() && input[2] == 0.toByte() && input[3] == 43.toByte() -> true to true
            else -> return unsupportedImage(input, CleanFileKind.TIFF, "TIFF invalide")
        }
        val bigEndian = layout.first
        val bigTiff = layout.second
        val offsetBytes = if (bigTiff) 8 else 4
        val countBytes = if (bigTiff) 8 else 2
        val entryBytes = if (bigTiff) 20 else 12
        val headerBytes = if (bigTiff) 16 else 8
        val first = if (bigTiff) readEndian64(input, headerBytes - 8, bigEndian) else readEndian32(input, headerBytes - 4, bigEndian).toLong()
        if (first <= 0 || first > input.size - countBytes) {
            return NativeCleanOutput(input, NativeCleanReport(CleanFileKind.TIFF, input.size, input.size, false, emptyList(), warnings = listOf("TIFF sans IFD lisible : fichier conservé.")))
        }

        val metadataTags = setOf(269, 270, 271, 272, 305, 306, 315, 316, 33432, 33723, 34377, 34665, 34853, 37500, 40091, 40092, 40093, 40094, 40095, 40965, 700)
        val typeSizes = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8, 16 to 8, 17 to 8, 18 to 8)
        val output = input.copyOf()
        val queue = mutableListOf(first to false)
        val seen = mutableSetOf<Pair<Long, Boolean>>()
        val actions = mutableListOf<String>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 4096) {
            val (ifdOffset, subIfd) = queue.removeAt(queue.lastIndex)
            if (!seen.add(ifdOffset to subIfd)) continue
            if (ifdOffset > Int.MAX_VALUE || ifdOffset < 0 || ifdOffset > input.size - countBytes) continue
            val offset = ifdOffset.toInt()
            val count = if (bigTiff) readEndian64(input, offset, bigEndian).coerceAtMost(4096L).toInt() else readEndian16(input, offset, bigEndian)
            val entriesEnd = offset.toLong() + countBytes.toLong() + count.toLong() * entryBytes
            if (entriesEnd + offsetBytes > input.size) continue
            for (index in 0 until count) {
                val entry = offset + countBytes + index * entryBytes
                val tag = readEndian16(input, entry, bigEndian)
                val type = readEndian16(input, entry + 2, bigEndian)
                val valueCount = if (bigTiff) readEndian64(input, entry + 4, bigEndian) else readEndian32(input, entry + 4, bigEndian).toLong()
                val valueField = entry + (if (bigTiff) 12 else 8)
                val valueCapacity = if (bigTiff) 8 else 4
                val byteCount = valueCount.coerceAtMost(Int.MAX_VALUE.toLong()) * (typeSizes[type] ?: 1)
                val valueOffset = if (byteCount > valueCapacity) {
                    if (bigTiff) readEndian64(input, valueField, bigEndian) else readEndian32(input, valueField, bigEndian).toLong()
                } else -1L
                val payload = if (valueOffset >= 0 && valueOffset + byteCount <= input.size) input.copyOfRange(valueOffset.toInt(), (valueOffset + byteCount).toInt()) else input.copyOfRange(valueField, valueField + minOf(valueCapacity, input.size - valueField))
                if (tag in setOf(34665, 34853, 40965) && payload.size >= offsetBytes) {
                    val child = if (bigTiff) readEndian64(payload, 0, bigEndian) else readEndian32(payload, 0, bigEndian).toLong()
                    if (child > 0) queue += child to true
                }
                val marker = containsProvenanceMarker(String(payload, Charsets.ISO_8859_1).lowercase(Locale.US))
                val drop = marker || (options.stripAllMetadata && (subIfd || tag in metadataTags))
                if (!drop) continue
                output.fill(0, entry, minOf(entry + entryBytes, output.size))
                if (valueOffset >= 0 && valueOffset + byteCount <= output.size) output.fill(0, valueOffset.toInt(), (valueOffset + byteCount).toInt())
                actions += "Retrait de la balise TIFF $tag"
            }
            val nextPos = entriesEnd.toInt()
            if (nextPos + offsetBytes <= input.size) {
                val next = if (bigTiff) readEndian64(input, nextPos, bigEndian) else readEndian32(input, nextPos, bigEndian).toLong()
                if (next > 0) queue += next to subIfd
            }
        }
        return outputFor(CleanFileKind.TIFF, input, output, actions.distinct())
    }

    /** Best-effort in-place PDF info/XMP scrub; xref offsets remain unchanged. */
    private fun cleanPdf(input: ByteArray, options: NativeCleanOptions): NativeCleanOutput {
        if (!startsWith(input, "%PDF-".toByteArray(Charsets.ISO_8859_1))) {
            return NativeCleanOutput(
                input,
                NativeCleanReport(
                    CleanFileKind.PDF,
                    input.size,
                    input.size,
                    false,
                    emptyList(),
                    warnings = listOf("PDF invalide."),
                ),
            )
        }
        val output = input.copyOf()
        val actions = mutableListOf<String>()
        val keys = listOf(
            "/Title", "/Author", "/Subject", "/Keywords", "/Creator", "/Producer", "/CreationDate", "/ModDate",
        )
        for (key in keys) {
            var at = 0
            val needle = key.toByteArray(Charsets.ISO_8859_1)
            while (true) {
                val found = indexOf(input, needle, at)
                if (found < 0) break
                var value = found + needle.size
                while (value < input.size && (input[value].toInt() and 0xFF) <= 0x20) value++
                if (value >= input.size) break
                var valueEnd = value + 1
                if (input[value] == '('.code.toByte()) {
                    var cursor = value + 1
                    var depth = 1
                    while (cursor < input.size && depth > 0) {
                        if (input[cursor] == '\\'.code.toByte()) {
                            cursor = minOf(input.size, cursor + 2)
                        } else {
                            if (input[cursor] == '('.code.toByte()) depth++
                            if (input[cursor] == ')'.code.toByte()) depth--
                            cursor++
                        }
                    }
                    if (depth == 0) valueEnd = cursor
                } else if (input[value] == '<'.code.toByte() && value + 1 < input.size && input[value + 1] != '<'.code.toByte()) {
                    val end = indexOf(input, byteArrayOf('>'.code.toByte()), value + 1)
                    if (end > value) valueEnd = end + 1
                } else {
                    val whitespace = byteArrayOf(0x20, 0x09, 0x0A, 0x0D)
                    while (valueEnd < input.size && input[valueEnd] !in whitespace) valueEnd++
                }
                val valueText = String(input.copyOfRange(value, minOf(valueEnd, input.size)), Charsets.ISO_8859_1).lowercase(Locale.US)
                val drop = options.stripAllMetadata || containsProvenanceMarker(valueText)
                if (drop && valueEnd > value) {
                    val fill = if (input[value] == '<'.code.toByte()) '0'.code.toByte() else ' '.code.toByte()
                    output.fill(fill, value.coerceAtMost(output.size), valueEnd.coerceAtMost(output.size))
                    actions += if (containsProvenanceMarker(valueText)) {
                        "Nettoyage du champ PDF $key (provenance)"
                    } else {
                        "Nettoyage du champ PDF $key"
                    }
                }
                at = maxOf(found + needle.size, valueEnd)
            }
        }

        fun neutralizeXmp(start: Int, endExclusive: Int) {
            if (endExclusive <= start) return
            output.fill(' '.code.toByte(), start.coerceAtLeast(0), endExclusive.coerceAtMost(output.size))
            actions += "Retrait du paquet XMP PDF"
        }

        var xmp = indexOf(input, "<?xpacket".toByteArray(Charsets.ISO_8859_1), 0)
        while (xmp >= 0) {
            val endMarker = indexOf(input, "<?xpacket end".toByteArray(Charsets.ISO_8859_1), xmp + 9)
            if (endMarker < 0) break
            val end = indexOf(input, byteArrayOf('>'.code.toByte()), endMarker)
            if (end < 0) break
            val packetEnd = end + 1
            val packet = String(input, xmp, packetEnd - xmp, Charsets.ISO_8859_1).lowercase(Locale.US)
            if (options.stripAllMetadata || containsProvenanceMarker(packet)) neutralizeXmp(xmp, packetEnd)
            xmp = indexOf(input, "<?xpacket".toByteArray(Charsets.ISO_8859_1), packetEnd)
        }

        var xml = indexOf(input, "<x:xmpmeta".toByteArray(Charsets.ISO_8859_1), 0)
        while (xml >= 0) {
            val close = indexOf(input, "</x:xmpmeta>".toByteArray(Charsets.ISO_8859_1), xml + 10)
            if (close < 0) break
            val end = close + "</x:xmpmeta>".length
            val packet = String(input, xml, end - xml, Charsets.ISO_8859_1).lowercase(Locale.US)
            if (options.stripAllMetadata || containsProvenanceMarker(packet)) neutralizeXmp(xml, end)
            xml = indexOf(input, "<x:xmpmeta".toByteArray(Charsets.ISO_8859_1), end)
        }

        val warning = "PDF nettoyé sans réécriture qpdf : les pièces jointes et métadonnées d'images peuvent nécessiter le backend desktop."
        val residuals = findResidualMarkers(output)
        val residualWarning = if (residuals.isEmpty()) emptyList() else listOf(
            "Marqueurs encore présents après le nettoyage : ${residuals.joinToString(", ")}",
        )
        return NativeCleanOutput(
            output,
            NativeCleanReport(
                CleanFileKind.PDF,
                input.size,
                output.size,
                !input.contentEquals(output),
                actions.distinct(),
                warnings = listOf(warning) + residualWarning,
                residualMarkers = residuals,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Audio/video containers
    // ---------------------------------------------------------------------------------------------

    private fun cleanWav(input: ByteArray, options: NativeCleanOptions = NativeCleanOptions()): NativeCleanOutput {
        if (input.size < 12 || !startsWith(input, RIFF_SIGNATURE) || !input.copyOfRange(8, 12).contentEquals(WAVE_SIGNATURE)) {
            return unsupportedImage(input, CleanFileKind.WAV, "WAV invalide")
        }
        val out = ByteArrayOutputStream(input.size)
        out.write(input, 0, 12)
        val actions = mutableListOf<String>()
        var pos = 12
        while (pos + 8 <= input.size) {
            val id = input.copyOfRange(pos, pos + 4)
            val size = readLe32(input, pos + 4)
            val end = pos.toLong() + 8L + size + (size and 1)
            if (size < 0 || end > input.size) {
                out.write(input, pos, input.size - pos)
                break
            }
            val name = String(id, Charsets.ISO_8859_1)
            val payload = input.copyOfRange(pos + 8, pos + 8 + size)
            val upper = name.uppercase(Locale.US)
            val isInfo = name == "LIST" && payload.size >= 4 && String(payload, 0, 4, Charsets.ISO_8859_1) == "INFO"
            val metadataChunk = upper in setOf("ID3 ", "BEXT", "IXML", "AXML", "JUNK", "CUE ", "PLST", "ADTL", "SMPL", "INST", "LIST")
            val markerHit = containsProvenanceMarker(String(payload, Charsets.ISO_8859_1).lowercase(Locale.US))
            val drop = name == "C2PA" || (isInfo && options.stripAllMetadata) ||
                (metadataChunk && (options.stripAllMetadata || markerHit))
            if (drop) actions += "Retrait du bloc WAV $name" else out.write(input, pos, (end - pos).toInt())
            pos = end.toInt()
        }
        val result = out.toByteArray()
        if (result.size >= 8) copyInto(result, 4, le32(result.size - 8))
        return outputFor(CleanFileKind.WAV, input, result, actions)
    }

    private fun cleanMp3(input: ByteArray, options: NativeCleanOptions): NativeCleanOutput {
        var audioStart = 0
        val actions = mutableListOf<String>()
        if (startsWith(input, ID3_SIGNATURE) && input.size >= 10) {
            val major = input[3].toInt() and 0xFF
            val tagSize = synchsafeInt(input, 6)
            val tagEnd = 10L + tagSize
            val footer = if (major == 4 && (input[5].toInt() and 0x10) != 0) 10 else 0
            val total = tagEnd + footer
            if (total > input.size) {
                return NativeCleanOutput(
                    input,
                    NativeCleanReport(
                        CleanFileKind.MP3,
                        input.size,
                        input.size,
                        false,
                        emptyList(),
                        warnings = listOf("Tag ID3 tronqué : fichier conservé pour éviter de couper l'audio."),
                    ),
                )
            }
            val tag = input.copyOfRange(0, total.toInt())
            val markerHit = containsProvenanceMarker(String(tag, Charsets.ISO_8859_1).lowercase(Locale.US))
            if (options.stripAllMetadata) {
                audioStart = total.toInt()
                actions += "Retrait du tag ID3v2.$major"
            } else if (major >= 3 && (input[5].toInt() and 0x40) == 0) {
                val kept = ByteArrayOutputStream(tagSize)
                var pos = 10
                val frameEnd = tagEnd.toInt()
                var valid = true
                while (pos + 10 <= frameEnd) {
                    val id = input.copyOfRange(pos, pos + 4)
                    if (id.all { it.toInt() == 0 }) break
                    val declared = if (major == 4) synchsafeInt(input, pos + 4) else readBe32(input, pos + 4)
                    if (declared < 0 || pos + 10L + declared > frameEnd) {
                        valid = false
                        break
                    }
                    val payloadStart = pos + 10
                    val payload = input.copyOfRange(payloadStart, payloadStart + declared)
                    val frameName = String(id, Charsets.ISO_8859_1)
                    if (containsProvenanceMarker(String(payload, Charsets.ISO_8859_1).lowercase(Locale.US))) {
                        actions += "Retrait de la trame ID3 $frameName"
                    } else {
                        kept.write(input, pos, 10 + declared)
                    }
                    pos += 10 + declared
                }
                if (valid && actions.isNotEmpty()) {
                    val header = input.copyOfRange(0, 10)
                    writeSynchsafe(header, 6, kept.size())
                    val rebuilt = ByteArrayOutputStream(total.toInt())
                    rebuilt.write(header)
                    rebuilt.write(kept.toByteArray())
                    if (footer != 0) {
                        val footerBytes = input.copyOfRange(tagEnd.toInt(), total.toInt())
                        writeSynchsafe(footerBytes, 6, kept.size())
                        rebuilt.write(footerBytes)
                    }
                    val newTag = rebuilt.toByteArray()
                    val suffix = input.copyOfRange(total.toInt(), input.size)
                    val selective = cleanMp3Tail(newTag + suffix, 0, options, actions)
                    return selective.copy(
                        report = selective.report.copy(
                            bytesIn = input.size,
                            changed = !input.contentEquals(selective.bytes),
                        ),
                    )
                }
                if (!valid && markerHit) {
                    // A malformed selective tag cannot be rewritten safely;
                    // whole-tag removal is still deterministic when provenance
                    // evidence is explicit.
                    audioStart = total.toInt()
                    actions.clear()
                    actions += "Retrait du tag ID3v2.$major (provenance)"
                }
            } else if (markerHit) {
                audioStart = total.toInt()
                actions += "Retrait du tag ID3v2.$major (provenance)"
            }
        }
        return cleanMp3Tail(input, audioStart, options, actions)
    }

    private fun cleanMp3Tail(
        data: ByteArray,
        audioStart: Int,
        options: NativeCleanOptions,
        actions: MutableList<String>,
    ): NativeCleanOutput {
        var audioEnd = data.size
        if (data.size - audioStart >= 128 && String(data, data.size - 128, 3, Charsets.ISO_8859_1) == "TAG") {
            val tag = String(data, data.size - 128, 128, Charsets.ISO_8859_1).lowercase(Locale.US)
            if (options.stripAllMetadata || containsProvenanceMarker(tag)) {
                audioEnd -= 128
                actions += "Retrait du tag ID3v1"
            }
        }
        if (audioStart == 0 && audioEnd == data.size && actions.isEmpty()) {
            return outputFor(CleanFileKind.MP3, data, data, emptyList())
        }
        val output = if (audioStart == 0 && audioEnd == data.size) data else data.copyOfRange(audioStart, audioEnd)
        return outputFor(CleanFileKind.MP3, data, output, actions)
    }

    private fun synchsafeInt(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) return -1
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    private fun writeSynchsafe(data: ByteArray, offset: Int, value: Int) {
        if (offset < 0 || offset + 4 > data.size) return
        data[offset] = ((value ushr 21) and 0x7F).toByte()
        data[offset + 1] = ((value ushr 14) and 0x7F).toByte()
        data[offset + 2] = ((value ushr 7) and 0x7F).toByte()
        data[offset + 3] = (value and 0x7F).toByte()
    }

    private fun cleanFlac(input: ByteArray, options: NativeCleanOptions): NativeCleanOutput {
        var flacStart = 0
        var leadingId3End = 0
        if (!startsWith(input, FLAC_SIGNATURE) && startsWith(input, ID3_SIGNATURE) && input.size >= 10) {
            val tagSize = ((input[6].toInt() and 0x7F) shl 21) or
                ((input[7].toInt() and 0x7F) shl 14) or
                ((input[8].toInt() and 0x7F) shl 7) or
                (input[9].toInt() and 0x7F)
            val footer = if ((input[5].toInt() and 0x10) != 0) 10 else 0
            val candidate = 10L + tagSize + footer
            if (candidate <= input.size && startsWith(input.copyOfRange(candidate.toInt(), input.size), FLAC_SIGNATURE)) {
                flacStart = candidate.toInt()
                leadingId3End = flacStart
            }
        }
        if (flacStart == 0 && !startsWith(input, FLAC_SIGNATURE)) {
            return outputFor(CleanFileKind.FLAC, input, input, emptyList())
        }
        val leadingTag = if (flacStart > 0) input.copyOfRange(0, flacStart) else byteArrayOf()
        val leadingMarker = leadingTag.isNotEmpty() && containsProvenanceMarker(String(leadingTag, Charsets.ISO_8859_1).lowercase(Locale.US))
        val prefix = if (flacStart > 0 && !options.stripAllMetadata && !leadingMarker) leadingTag else byteArrayOf()
        val start = flacStart
        var pos = start + 4
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        var sawLast = false
        while (pos + 4 <= input.size) {
            val header = input[pos].toInt() and 0xFF
            val type = header and 0x7F
            val size = ((input[pos + 1].toInt() and 0xFF) shl 16) or
                ((input[pos + 2].toInt() and 0xFF) shl 8) or (input[pos + 3].toInt() and 0xFF)
            val end = pos.toLong() + 4L + size
            if (end > input.size) {
                return NativeCleanOutput(input, NativeCleanReport(CleanFileKind.FLAC, input.size, input.size, false, emptyList(), warnings = listOf("Métadonnées FLAC tronquées : fichier conservé.")))
            }
            val payload = input.copyOfRange(pos + 4, end.toInt())
            val markerHit = containsProvenanceMarker(String(payload, Charsets.ISO_8859_1).lowercase(Locale.US))
            val drop = when {
                type == 0 -> false // STREAMINFO is required to decode the audio.
                options.stripAllMetadata -> true
                type == 2 && markerHit -> true // APPLICATION block, including C2PA carriers.
                markerHit -> true
                else -> false
            }
            if (!drop) blocks += type to payload
            pos = end.toInt()
            if ((header and 0x80) != 0) {
                sawLast = true
                break
            }
        }
        if (!sawLast || blocks.none { it.first == 0 }) {
            return NativeCleanOutput(input, NativeCleanReport(CleanFileKind.FLAC, input.size, input.size, false, emptyList(), warnings = listOf("En-tête FLAC incomplet : fichier conservé.")))
        }
        val out = ByteArrayOutputStream(input.size)
        out.write(prefix)
        out.write(FLAC_SIGNATURE)
        blocks.forEachIndexed { index, (type, payload) ->
            val last = if (index == blocks.lastIndex) 0x80 else 0
            out.write(last or (type and 0x7F))
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(payload)
        }
        out.write(input, pos, input.size - pos)
        val actions = mutableListOf<String>()
        if (prefix.isEmpty() && leadingId3End > 0) actions += "Retrait du tag ID3 avant FLAC"
        if (blocks.size != 1 || (pos - start) > 4 + 4 + blocks.first().second.size) actions += "Retrait des blocs de métadonnées FLAC"
        return outputFor(CleanFileKind.FLAC, input, out.toByteArray(), actions)
    }

    /**
     * Walks metadata-bearing MP4/MOV/AVIF container trees without changing the
     * size of a box. Removed children become `free` boxes, so sample offsets
     * and the media payload stay byte-for-byte stable.
     */
    private fun cleanIsoContainerPayload(
        payload: ByteArray,
        options: NativeCleanOptions,
        actions: MutableList<String>,
        fullBox: Boolean = false,
    ): ByteArray? {
        val prefix = if (fullBox) 4 else 0
        if (payload.size < prefix) return null
        val out = ByteArrayOutputStream(payload.size)
        if (prefix != 0) out.write(payload, 0, prefix)
        var pos = prefix
        var parsed = false
        val containerNames = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf", "mfra")
        while (pos + 8 <= payload.size) {
            val size32 = readBe32(payload, pos)
            val type = payload.copyOfRange(pos + 4, pos + 8)
            val name = String(type, Charsets.ISO_8859_1)
            var header = 8
            var size = size32.toLong()
            if (size32 == 1 && pos + 16 <= payload.size) {
                size = readBe64(payload, pos + 8)
                header = 16
            } else if (size32 == 0) {
                size = (payload.size - pos).toLong()
            }
            if (size < header || size > payload.size - pos) return null
            parsed = true
            val child = payload.copyOfRange(pos + header, pos + size.toInt())
            val lowerName = name.lowercase(Locale.US)
            val scan = String(child.copyOfRange(0, minOf(child.size, 1 shl 20)), Charsets.ISO_8859_1).lowercase(Locale.US)
            val isUuid = lowerName == "uuid"
            val uuidIsXmp = isUuid && startsWith(child, XMP_UUID)
            val uuidIsC2pa = isUuid && (startsWith(child, C2PA_BMFF_UUID) || (child.size >= 20 && child.copyOfRange(4, 20).contentEquals(C2PA_BMFF_UUID)))
            val drop = lowerName in setOf("c2pa", "jumb") || lowerName.startsWith("c2") ||
                (lowerName == "udta" && (options.stripAllMetadata || containsProvenanceMarker(scan))) ||
                (lowerName == "xml " && (options.stripAllMetadata || containsProvenanceMarker(scan))) ||
                (lowerName == "ilst" && options.stripAllMetadata) ||
                (isUuid && (options.stripAllMetadata || uuidIsXmp || uuidIsC2pa || containsProvenanceMarker(scan)))
            if (drop) {
                actions += "Neutralisation de la boîte MP4 $name"
                out.write(freeBox(size, header))
            } else if (lowerName == "meta") {
                val cleaned = cleanIsoMetaPayload(child, actions, options)
                if (cleaned == null || cleaned.size != child.size) {
                    out.write(payload, pos, size.toInt())
                } else {
                    out.write(buildIsoBox(type, cleaned, header))
                }
            } else if (lowerName in containerNames) {
                val cleaned = cleanIsoContainerPayload(child, options, actions)
                if (cleaned == null || cleaned.size != child.size) {
                    out.write(payload, pos, size.toInt())
                } else {
                    out.write(buildIsoBox(type, cleaned, header))
                }
            } else {
                out.write(payload, pos, size.toInt())
            }
            pos += size.toInt()
        }
        if (pos < payload.size) out.write(payload, pos, payload.size - pos)
        return if (parsed) out.toByteArray() else null
    }

    private fun cleanIsoBmff(input: ByteArray, kind: CleanFileKind, options: NativeCleanOptions): NativeCleanOutput {
        if (input.size < 12 || !input.copyOfRange(4, 8).contentEquals(FTYP_SIGNATURE)) {
            return NativeCleanOutput(input, NativeCleanReport(kind, input.size, input.size, false, emptyList(), warnings = listOf("Conteneur ISO-BMFF invalide.")))
        }
        val out = ByteArrayOutputStream(input.size)
        val actions = mutableListOf<String>()
        var pos = 0
        while (pos + 8 <= input.size) {
            val start = pos
            val size32 = readBe32(input, pos)
            val type = input.copyOfRange(pos + 4, pos + 8)
            val name = String(type, Charsets.ISO_8859_1)
            var header = 8
            var size = size32.toLong()
            if (size32 == 1 && pos + 16 <= input.size) {
                size = readBe64(input, pos + 8)
                header = 16
            } else if (size32 == 0) {
                size = (input.size - pos).toLong()
            }
            if (size < header || size > input.size - pos) {
                out.write(input, pos, input.size - pos)
                pos = input.size
                break
            }
            val payloadStart = pos + header
            val payloadEnd = pos + size.toInt()
            val payload = input.copyOfRange(payloadStart, payloadEnd)
            val lowerPayload = String(payload.copyOfRange(0, minOf(payload.size, 1 shl 20)), Charsets.ISO_8859_1).lowercase(Locale.US)
            val isUuid = name.equals("uuid", ignoreCase = true)
            val uuidIsXmp = isUuid && startsWith(payload, XMP_UUID)
            val uuidIsC2pa = isUuid && (startsWith(payload, C2PA_BMFF_UUID) || (payload.size >= 20 && payload.copyOfRange(4, 20).contentEquals(C2PA_BMFF_UUID)))
            val lowerName = name.lowercase(Locale.US)
            val dropWhole = lowerName in setOf("c2pa", "jumb") || lowerName.startsWith("c2") ||
                (isUuid && (options.stripAllMetadata || uuidIsXmp || uuidIsC2pa)) ||
                (name.equals("udta", ignoreCase = true) && (options.stripAllMetadata || containsProvenanceMarker(lowerPayload))) ||
                (name.equals("xml ", ignoreCase = true) && (options.stripAllMetadata || containsProvenanceMarker(lowerPayload)))
            if (name.equals("meta", ignoreCase = true) && payload.size >= 4) {
                val cleanedMeta = cleanIsoMetaPayload(payload, actions, options)
                if (cleanedMeta != null) {
                    if (!cleanedMeta.contentEquals(payload)) actions += "Nettoyage des sous-boîtes meta ISO-BMFF"
                    out.write(buildIsoBox(type, cleanedMeta, header))
                } else if (containsProvenanceMarker(lowerPayload)) {
                    actions += "Neutralisation de la boîte MP4 $name"
                    out.write(freeBox(size, header))
                } else {
                    out.write(input, start, size.toInt())
                }
            } else if (lowerName in setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf", "mfra")) {
                val cleanedContainer = cleanIsoContainerPayload(payload, options, actions)
                if (cleanedContainer != null && cleanedContainer.size == payload.size) {
                    out.write(buildIsoBox(type, cleanedContainer, header))
                } else {
                    out.write(input, start, size.toInt())
                }
            } else if (dropWhole && size >= header) {
                val reason = when {
                    uuidIsC2pa -> "UUID C2PA"
                    uuidIsXmp -> "UUID XMP"
                    isUuid -> "UUID metadata"
                    else -> name
                }
                actions += "Neutralisation de la boîte MP4 $reason"
                out.write(freeBox(size, header))
            } else {
                out.write(input, start, size.toInt())
            }
            pos = payloadEnd
        }
        if (pos < input.size) out.write(input, pos, input.size - pos)
        return outputFor(kind, input, out.toByteArray(), actions)
    }

    /** Rewrites only the child boxes of a FullBox meta payload, preserving sizes and offsets. */
    private fun cleanIsoMetaPayload(
        payload: ByteArray,
        actions: MutableList<String>,
        options: NativeCleanOptions,
    ): ByteArray? {
        if (payload.size < 4) return null
        val out = ByteArrayOutputStream(payload.size)
        out.write(payload, 0, 4) // version and flags
        var pos = 4
        var parsed = false
        while (pos + 8 <= payload.size) {
            val size32 = readBe32(payload, pos)
            val type = payload.copyOfRange(pos + 4, pos + 8)
            val name = String(type, Charsets.ISO_8859_1)
            var header = 8
            var size = size32.toLong()
            if (size32 == 1 && pos + 16 <= payload.size) {
                size = readBe64(payload, pos + 8)
                header = 16
            } else if (size32 == 0) {
                size = (payload.size - pos).toLong()
            }
            if (size < header || size > payload.size - pos) return null
            parsed = true
            val child = payload.copyOfRange(pos + header, pos + size.toInt())
            val lower = String(child.copyOfRange(0, minOf(child.size, 1 shl 20)), Charsets.ISO_8859_1).lowercase(Locale.US)
            val lowerName = name.lowercase(Locale.US)
            val uuid = name.equals("uuid", ignoreCase = true)
            val uuidIsXmp = uuid && startsWith(child, XMP_UUID)
            val uuidIsC2pa = uuid && (startsWith(child, C2PA_BMFF_UUID) || (child.size >= 20 && child.copyOfRange(4, 20).contentEquals(C2PA_BMFF_UUID)))
            val drop = lowerName in setOf("jumb", "c2pa", "xml ", "bxml") || lowerName.startsWith("c2") ||
                (lowerName == "ilst" && options.stripAllMetadata) ||
                (uuid && (options.stripAllMetadata || uuidIsXmp || uuidIsC2pa)) || containsProvenanceMarker(lower)
            if (drop) {
                actions += "Neutralisation de la sous-boîte meta $name"
                out.write(freeBox(size, header))
            } else {
                out.write(payload, pos, size.toInt())
            }
            pos += size.toInt()
        }
        if (pos < payload.size) out.write(payload, pos, payload.size - pos)
        return if (parsed) out.toByteArray() else null
    }

    // ---------------------------------------------------------------------------------------------
    // Binary helpers
    // ---------------------------------------------------------------------------------------------

    private fun unsupportedImage(input: ByteArray, kind: CleanFileKind, warning: String): NativeCleanOutput =
        NativeCleanOutput(input, NativeCleanReport(kind, input.size, input.size, false, emptyList(), warnings = listOf(warning)))

    private fun findResidualMarkers(data: ByteArray): List<String> {
        if (data.isEmpty()) return emptyList()
        val text = String(data, Charsets.ISO_8859_1).lowercase(Locale.US)
        return listOf(
            "c2pa", "c2ma", "jumb", "contentcredentials", "content credential", "content-credentials", "cai:",
            "aigc", "synthid", "provenance", "digitalsourcetype", "digital_source_type", "digital source type", "trainedalgorithmicmedia", "trained algorithmic media",
            "softwareagent", "algorithmicmedia", "algorithmic media", "dcterms:provenance", "ai-generated", "ai generated", "aigenerated", "generated by ai",
        ).filter(text::contains).distinct()
    }

    private fun outputFor(kind: CleanFileKind, input: ByteArray, output: ByteArray, actions: List<String>): NativeCleanOutput {
        val residuals = findResidualMarkers(output)
        val warnings = buildList {
            if (residuals.isNotEmpty()) add(
                "Marqueurs encore présents après le nettoyage : ${residuals.joinToString(", ")}",
            )
            when (kind) {
                CleanFileKind.TEXT -> add(
                    "Seul le nettoyage déterministe Unicode et des métadonnées est exécuté : la réécriture IA des filigranes statistiques/token sampling n'est pas embarquée.",
                )
                CleanFileKind.PNG, CleanFileKind.JPEG, CleanFileKind.WEBP, CleanFileKind.GIF,
                CleanFileKind.BMP, CleanFileKind.TIFF, CleanFileKind.AVIF, CleanFileKind.HEIC -> add(
                    "Les pixels ne sont pas modifiés : le retrait de filigranes visuels nécessite un traitement image séparé.",
                )
                CleanFileKind.WAV, CleanFileKind.MP3, CleanFileKind.FLAC -> add(
                    "Seules les métadonnées audio sont nettoyées : le traitement destructif ffmpeg des filigranes audio n'est pas embarqué.",
                )
                CleanFileKind.MP4 -> add(
                    "Seules les métadonnées du conteneur média sont nettoyées : le retrait visuel reste disponible dans l'éditeur vidéo.",
                )
                CleanFileKind.ZIP_CONTAINER -> add(
                    "Les membres pris en charge sont traités nativement ; les filigranes statistiques, pixels et codecs propriétaires restent hors ligne non disponibles.",
                )
                else -> Unit
            }
        }
        return NativeCleanOutput(
            output,
            NativeCleanReport(
                kind,
                input.size,
                output.size,
                !input.contentEquals(output),
                actions.distinct(),
                warnings = warnings,
                residualMarkers = residuals,
            ),
        )
    }

    private fun decodeText(input: ByteArray): Triple<String, Charset, ByteArray> = when {
        input.size >= 2 && input[0] == 0xFF.toByte() && input[1] == 0xFE.toByte() -> Triple(String(input, 2, input.size - 2, Charsets.UTF_16LE), Charsets.UTF_16LE, input.copyOfRange(0, 2))
        input.size >= 2 && input[0] == 0xFE.toByte() && input[1] == 0xFF.toByte() -> Triple(String(input, 2, input.size - 2, Charsets.UTF_16BE), Charsets.UTF_16BE, input.copyOfRange(0, 2))
        input.size >= 3 && input[0] == 0xEF.toByte() && input[1] == 0xBB.toByte() && input[2] == 0xBF.toByte() -> Triple(String(input, 3, input.size - 3, Charsets.UTF_8), Charsets.UTF_8, input.copyOfRange(0, 3))
        else -> Triple(String(input, Charsets.UTF_8), Charsets.UTF_8, byteArrayOf())
    }

    private fun looksLikeUtf8Text(input: ByteArray): Boolean {
        if (input.isEmpty() || input.take(4096).any { it == 0.toByte() }) return false
        return runCatching {
            val text = String(input, Charsets.UTF_8)
            text.isNotEmpty() && text.count { it == '\uFFFD' } <= text.length / 100
        }.getOrDefault(false)
    }

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    private fun crc32(data: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value
    }

    private fun readBe16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readBe32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun readLe16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readEndian16(data: ByteArray, offset: Int, bigEndian: Boolean): Int =
        if (bigEndian) readBe16(data, offset) else readLe16(data, offset)

    private fun readEndian32(data: ByteArray, offset: Int, bigEndian: Boolean): Long =
        if (bigEndian) readBe32(data, offset).toLong() and 0xFFFF_FFFFL
        else readLe32(data, offset).toLong() and 0xFFFF_FFFFL

    private fun readBe64(data: ByteArray, offset: Int): Long {
        var result = 0L
        repeat(8) { result = (result shl 8) or (data[offset + it].toLong() and 0xFF) }
        return result
    }

    private fun readLe32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun readEndian64(data: ByteArray, offset: Int, bigEndian: Boolean): Long {
        var result = 0L
        if (bigEndian) {
            repeat(8) { result = (result shl 8) or (data[offset + it].toLong() and 0xFF) }
        } else {
            for (i in 0 until 8) result = result or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return result
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, fromIndex: Int = 0): Int {
        if (needle.isEmpty()) return fromIndex.coerceIn(0, data.size)
        val start = fromIndex.coerceAtLeast(0)
        if (needle.size > data.size - start) return -1
        for (i in start..(data.size - needle.size)) {
            var matches = true
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) {
                    matches = false
                    break
                }
            }
            if (matches) return i
        }
        return -1
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
    )

    private fun copyInto(target: ByteArray, offset: Int, value: ByteArray) {
        value.copyInto(target, offset)
    }

    private fun buildIsoBox(type: ByteArray, payload: ByteArray, header: Int): ByteArray {
        val size = payload.size.toLong() + header
        if (size > Int.MAX_VALUE) return ByteArray(0)
        val result = ByteArray(size.toInt())
        if (header == 16) {
            result[0] = 0
            result[1] = 0
            result[2] = 0
            result[3] = 1
            type.copyInto(result, 4)
            var value = size
            for (i in 0 until 8) {
                result[15 - i] = (value and 0xFF).toByte()
                value = value ushr 8
            }
            payload.copyInto(result, 16)
        } else {
            leOrBeSize(result, size)
            type.copyInto(result, 4)
            payload.copyInto(result, 8)
        }
        return result
    }

    private fun freeBox(size: Long, header: Int): ByteArray {
        if (size < header || size > Int.MAX_VALUE) return ByteArray(size.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val result = ByteArray(size.toInt())
        if (header == 16) {
            result[0] = 0
            result[1] = 0
            result[2] = 0
            result[3] = 1
            "free".toByteArray(Charsets.ISO_8859_1).copyInto(result, 4)
            var value = size
            for (i in 0 until 8) {
                result[15 - i] = (value and 0xFF).toByte()
                value = value ushr 8
            }
        } else {
            leOrBeSize(result, size)
            "free".toByteArray(Charsets.ISO_8859_1).copyInto(result, 4)
        }
        return result
    }

    private fun leOrBeSize(target: ByteArray, value: Long) {
        for (i in 0 until 4) target[3 - i] = (value ushr (8 * i)).toByte()
    }

    private fun pngTextLooksProvenance(name: String, payload: ByteArray): Boolean {
        val text = String(payload, Charsets.ISO_8859_1)
        if (containsProvenanceMarker(text.lowercase(Locale.US))) return true
        if (name != "tEXt" && name != "zTXt" && name != "iTXt") return false
        val key = text.substringBefore('\u0000').trim().lowercase(Locale.US)
        val generatorKey = key in setOf("software", "creator", "parameters", "generator", "created with", "created_with")
        if (!generatorKey) return false
        return listOf(
            "chatgpt", "dall-e", "midjourney", "stable diffusion", "sdxl", "flux", "dreamstudio",
            "leonardo", "craiyon", "novelai", "ideogram", "tensorart", "recraft", "clipdrop",
            "deepai", "nightcafe", "image creator", "firefly", "gemini", "imagen", "grok", "sora",
            "veo", "kling", "runway", "luma", "qwen", "gpt-4", "gpt-5",
        ).any(text.lowercase(Locale.US)::contains)
    }

    private fun containsAiVendorMarker(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return listOf(
            "claude", "anthropic", "openai", "chatgpt", "gemini", "synthid", "copilot",
            "midjourney", "dall-e", "stable diffusion", "sdxl", "flux", "firefly", "imagen",
            "leonardo ai", "ideogram", "grok", "sora", "veo", "kling", "runway", "luma", "qwen",
        ).any(lower::contains)
    }

    private fun containsProvenanceMarker(text: String): Boolean =
        listOf(
            "c2pa", "c2ma", "jumb", "contentcredentials", "content credentials", "content-credentials", "contentauth", "cai:",
            "aigc", "synthid", "provenance", "digitalsourcetype", "digital_source_type", "digital source type", "trainedalgorithmicmedia", "trained algorithmic media",
            "softwareagent", "algorithmicmedia", "algorithmic media", "dcterms:provenance", "ai-generated", "ai generated", "aigenerated", "generated by ai",
        ).any(text::contains)

    private fun gifExtensionEnd(data: ByteArray, start: Int): Int? {
        if (start + 2 > data.size) return null
        var pos = start + 2
        while (pos < data.size) {
            val count = data[pos].toInt() and 0xFF
            pos++
            if (count == 0) return pos
            if (pos + count > data.size) return null
            pos += count
        }
        return null
    }

    private fun gifImageEnd(data: ByteArray, start: Int): Int? {
        if (start + 10 > data.size) return null
        var pos = start + 10
        val packed = data[start + 9].toInt() and 0xFF
        if ((packed and 0x80) != 0) pos += 3 * (1 shl ((packed and 0x07) + 1))
        if (pos >= data.size) return null
        pos++ // LZW minimum code size
        while (pos < data.size) {
            val count = data[pos].toInt() and 0xFF
            pos++
            if (count == 0) return pos
            if (pos + count > data.size) return null
            pos += count
        }
        return null
    }

    private fun percentDecode(value: String): ByteArray? {
        val output = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                if (index + 2 >= value.length) return null
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high < 0 || low < 0) return null
                output.write((high shl 4) or low)
                index += 3
            } else {
                // The unescaped portion of an image data URI is ASCII by
                // definition; keeping the low byte also handles malformed
                // providers without throwing on the UI thread.
                output.write(value[index].code and 0xFF)
                index++
            }
        }
        return output.toByteArray()
    }

    private fun percentEncode(value: ByteArray): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val result = StringBuilder(value.size)
        value.forEach { byte ->
            val number = byte.toInt() and 0xFF
            val char = number.toChar()
            if (char in safe) result.append(char)
            else result.append('%').append("%02X".format(Locale.US, number))
        }
        return result.toString()
    }

    private fun decodeBase64(value: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = ByteArrayOutputStream(value.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var padding = false
        for (char in value) {
            if (char.isWhitespace()) continue
            if (char == '=') {
                padding = true
                continue
            }
            if (padding) return null
            val digit = alphabet.indexOf(char)
            if (digit < 0) return null
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer ushr bits) and 0xFF)
                if (bits == 0) buffer = 0 else buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return output.toByteArray()
    }

    private fun encodeBase64(value: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val result = StringBuilder((value.size + 2) / 3 * 4)
        var index = 0
        while (index < value.size) {
            val first = value[index].toInt() and 0xFF
            val hasSecond = index + 1 < value.size
            val hasThird = index + 2 < value.size
            val second = if (hasSecond) value[index + 1].toInt() and 0xFF else 0
            val third = if (hasThird) value[index + 2].toInt() and 0xFF else 0
            result.append(alphabet[first ushr 2])
            result.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            result.append(if (hasSecond) alphabet[((second and 0x0F) shl 2) or (third ushr 6)] else '=')
            result.append(if (hasThird) alphabet[third and 0x3F] else '=')
            index += 3
        }
        return result.toString()
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean = data.size >= prefix.size && data.copyOfRange(0, prefix.size).contentEquals(prefix)

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val BMP_SIGNATURE = byteArrayOf('B'.code.toByte(), 'M'.code.toByte())
    private val TIFF_LE_SIGNATURE = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)
    private val TIFF_BE_SIGNATURE = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42)
    private val TIFF_LE_BIG_SIGNATURE = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 43, 0)
    private val TIFF_BE_BIG_SIGNATURE = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 43)
    private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.ISO_8859_1)
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val GIF87_SIGNATURE = "GIF87a".toByteArray(Charsets.ISO_8859_1)
    private val GIF89_SIGNATURE = "GIF89a".toByteArray(Charsets.ISO_8859_1)
    private val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.ISO_8859_1)
    private val WAVE_SIGNATURE = "WAVE".toByteArray(Charsets.ISO_8859_1)
    private val WEBP_SIGNATURE = "WEBP".toByteArray(Charsets.ISO_8859_1)
    private val ID3_SIGNATURE = "ID3".toByteArray(Charsets.ISO_8859_1)
    private val FLAC_SIGNATURE = "fLaC".toByteArray(Charsets.ISO_8859_1)
    private val XMP_UUID = byteArrayOf(0xBE.toByte(), 0x7A.toByte(), 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42.toByte(), 0xE8.toByte(), 0x9C.toByte(), 0x71.toByte(), 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte())
    private val C2PA_BMFF_UUID = byteArrayOf(0xD8.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0xD6.toByte(), 0x1B.toByte(), 0x0E.toByte(), 0x48.toByte(), 0x3C.toByte(), 0x92.toByte(), 0x97.toByte(), 0x58.toByte(), 0x28.toByte(), 0x87.toByte(), 0x7E.toByte(), 0xC4.toByte(), 0x81.toByte())
    private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val ZIP_CENTRAL_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x07, 0x08)
    private val FTYP_SIGNATURE = "ftyp".toByteArray(Charsets.ISO_8859_1)
}

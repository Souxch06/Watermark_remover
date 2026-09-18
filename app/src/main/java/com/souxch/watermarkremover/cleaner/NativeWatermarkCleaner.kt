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
) {
    val summary: String
        get() = when {
            !changed && warnings.isEmpty() -> "Aucun marqueur déterministe trouvé"
            changed -> actions.firstOrNull() ?: "Métadonnées retirées"
            else -> warnings.firstOrNull() ?: "Aucune modification"
        }
}

data class NativeCleanOutput(
    val bytes: ByteArray,
    val report: NativeCleanReport,
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
        "txt", "text", "md", "markdown", "mdx", "html", "htm", "css", "js", "jsx", "mjs", "cjs",
        "ts", "tsx", "py", "rs", "go", "java", "kt", "kts", "swift", "dart", "json", "yaml", "yml",
        "toml", "csv", "tsv", "xml", "svg", "tex", "ltx", "rst", "adoc", "asciidoc", "org", "po", "pot",
        "strings", "arb", "properties", "ini", "cfg", "conf", "sh", "bash", "zsh", "ps1", "sql", "lua",
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
            "docx", "xlsx", "pptx", "odt", "epub" -> CleanFileKind.ZIP_CONTAINER
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
        if (mime.startsWith("video/")) return CleanFileKind.MP4

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
                startsWith(data, ZIP_SIGNATURE) -> return CleanFileKind.ZIP_CONTAINER
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
    ): NativeCleanOutput {
        val kind = classify(displayName, mimeType, input)
        return when (kind) {
            CleanFileKind.TEXT -> cleanText(input, xmlLike = extensionOf(displayName) in setOf("xml", "svg", "html", "htm"), aggressive = aggressiveText)
            CleanFileKind.PNG -> cleanPng(input)
            CleanFileKind.JPEG -> cleanJpeg(input)
            CleanFileKind.WEBP -> cleanWebp(input)
            CleanFileKind.GIF -> cleanGif(input)
            CleanFileKind.BMP -> cleanBmp(input)
            CleanFileKind.WAV -> cleanWav(input)
            CleanFileKind.MP3, CleanFileKind.FLAC -> cleanLeadingId3(input, kind)
            CleanFileKind.MP4, CleanFileKind.AVIF, CleanFileKind.HEIC -> cleanIsoBmff(input, kind)
            CleanFileKind.ZIP_CONTAINER -> cleanZip(input, displayName)
            CleanFileKind.TIFF -> cleanTiff(input)
            CleanFileKind.PDF -> cleanPdf(input)
            CleanFileKind.UNSUPPORTED -> NativeCleanOutput(
                input,
                NativeCleanReport(kind, input.size, input.size, false, emptyList(), warnings = listOf("Format non pris en charge sur Android.")),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Text and ZIP containers
    // ---------------------------------------------------------------------------------------------

    private fun cleanText(input: ByteArray, xmlLike: Boolean, aggressive: Boolean): NativeCleanOutput {
        val (text, charset, bom) = decodeText(input)
        val cleaned = TextUnicodeCleaner.clean(text, aggressiveConfusables = aggressive)
        var resultText = cleaned.text
        val actions = mutableListOf<String>()
        if (xmlLike) {
            val xmlCleaned = stripXmlMetadata(resultText)
            if (xmlCleaned != resultText) {
                resultText = xmlCleaned
                actions += "Retrait des champs XML de provenance"
            }
        }
        if (cleaned.removedCount > 0) actions += "Retrait de ${cleaned.removedCount} caractère(s) Unicode invisible(s)"
        if (cleaned.replacedCount > 0) actions += "Normalisation de ${cleaned.replacedCount} espace(s) ou caractère(s)"
        val output = bom + resultText.toByteArray(charset)
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
            ),
        )
    }

    private fun cleanZip(input: ByteArray, displayName: String): NativeCleanOutput {
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
                            cleaned = when {
                                memberKind == CleanFileKind.TEXT -> {
                                    val (memberText, charset, bom) = decodeText(content)
                                    val unicode = TextUnicodeCleaner.clean(memberText)
                                    var value = unicode.text
                                    if (lower.endsWith(".xml") || lower.endsWith(".opf") || lower.endsWith(".html") || lower.endsWith(".xhtml")) {
                                        value = stripXmlMetadata(value)
                                    }
                                    if (value != memberText) {
                                        actions += "Nettoyage de $name"
                                    }
                                    bom + value.toByteArray(charset)
                                }
                                memberKind == CleanFileKind.PNG || memberKind == CleanFileKind.JPEG ||
                                    memberKind == CleanFileKind.WEBP || memberKind == CleanFileKind.GIF -> {
                                    val nested = clean(name, null, content)
                                    if (nested.report.changed) {
                                        actions += "Nettoyage de $name"
                                    }
                                    nested.bytes
                                }
                                else -> content
                            }
                        }
                        val outEntry = ZipEntry(name)
                        if (lower == "mimetype") {
                            // EPUB requires this first member to be STORED.
                            outEntry.method = ZipEntry.STORED
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
        val bytes = output.toByteArray()
        val changed = actions.isNotEmpty() && !input.contentEquals(bytes)
        val kind = if (displayName.lowercase(Locale.US).endsWith(".epub")) CleanFileKind.ZIP_CONTAINER else CleanFileKind.ZIP_CONTAINER
        return NativeCleanOutput(
            bytes,
            NativeCleanReport(kind, input.size, bytes.size, changed, actions.distinct()),
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

    private fun stripXmlMetadata(text: String): String {
        var result = text
        val elementNames = listOf(
            "dc:creator", "dc:title", "dc:description", "dc:publisher", "dc:subject", "dc:rights",
            "cp:keywords", "cp:lastModifiedBy", "dcterms:created", "dcterms:modified",
            "meta:generator", "meta:creation-date", "meta:editing-duration", "xmp:CreatorTool",
            "xmp:MetadataDate", "xmpMM:InstanceID", "photoshop:Credit", "rdf:Description",
        )
        for (name in elementNames) {
            result = Regex("(?is)<$name\\b[^>]*>.*?</$name\\s*>").replace(result, "")
            result = Regex("(?is)<$name\\b[^>]*/\\s*>").replace(result, "")
        }
        // SVG metadata is a dedicated element; removing it does not touch the
        // visible vector paths.
        result = Regex("(?is)<metadata\\b[^>]*>.*?</metadata\\s*>").replace(result, "")
        // HTML meta tags are only removed when their attributes clearly identify
        // provenance or generator information; ordinary viewport/charset tags stay.
        result = Regex("(?is)<meta\\b[^>]*(?:generator|author|creator|provenance|c2pa|ai-generated)[^>]*/?\\s*>").replace(result, "")
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // PNG / JPEG / WebP / GIF
    // ---------------------------------------------------------------------------------------------

    private fun cleanPng(input: ByteArray): NativeCleanOutput {
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
            val drop = name in setOf("tEXt", "zTXt", "iTXt", "eXIf", "caBX") || lower.startsWith("c2") || lower == "jumb"
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

    private fun cleanJpeg(input: ByteArray): NativeCleanOutput {
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
            val drop = marker == 0xFE || (marker in 0xE0..0xEF && marker != 0xE0)
            if (drop) actions += "Retrait du segment JPEG ${if (marker == 0xFE) "COM" else "APP${marker - 0xE0}"}"
            else out.write(input, markerStart, 2 + segmentLength)
            pos += segmentLength
        }
        return outputFor(CleanFileKind.JPEG, input, out.toByteArray(), actions)
    }

    private fun cleanWebp(input: ByteArray): NativeCleanOutput {
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
            if (scanName in metadataFlags || scanName.uppercase(Locale.US) == "C2PA" || scanName.uppercase(Locale.US) == "JUMB") {
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
            val drop = name in setOf("EXIF", "XMP ", "ICCP", "C2PA", "JUMB") || upperName == "C2PA" || upperName == "JUMB"
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

    private fun cleanGif(input: ByteArray): NativeCleanOutput {
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
                            label == 0xFE -> true
                            label != 0xFF -> false
                            appId == "XMP DataXMP" -> true
                            appId == "NETSCAPE2.0" || appId == "ICCRGBG1012" -> markerHit
                            else -> true
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
    private fun cleanBmp(input: ByteArray): NativeCleanOutput {
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
        val output = input.copyOf(payloadEnd.toInt())
        copyInto(output, 2, le32(output.size))
        return outputFor(CleanFileKind.BMP, input, output, listOf("Retrait des ${input.size - output.size} octets BMP après l'image"))
    }

    /**
     * Removes descriptive TIFF IFD entries in place. No strip/tile payload is
     * moved, so offsets used by decoders remain valid for classic TIFF and
     * BigTIFF. Exif/GPS sub-IFDs are recursively treated as metadata.
     */
    private fun cleanTiff(input: ByteArray): NativeCleanOutput {
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
                val drop = subIfd || tag in metadataTags || marker
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
    private fun cleanPdf(input: ByteArray): NativeCleanOutput {
        if (!startsWith(input, "%PDF-".toByteArray(Charsets.ISO_8859_1))) {
            return NativeCleanOutput(input, NativeCleanReport(CleanFileKind.PDF, input.size, input.size, false, emptyList(), warnings = listOf("PDF invalide.")))
        }
        val output = input.copyOf()
        val actions = mutableListOf<String>()
        val keys = listOf("/Title", "/Author", "/Subject", "/Keywords", "/Creator", "/Producer", "/CreationDate", "/ModDate")
        for (key in keys) {
            var at = 0
            val needle = key.toByteArray(Charsets.ISO_8859_1)
            while (true) {
                val found = indexOf(input, needle, at)
                if (found < 0) break
                var value = found + needle.size
                while (value < input.size && input[value].toInt() and 0xFF <= 0x20) value++
                if (value >= input.size) break
                if (input[value] == '('.code.toByte()) {
                    var cursor = value + 1
                    var depth = 1
                    while (cursor < input.size && depth > 0) {
                        if (input[cursor] == '\\'.code.toByte()) cursor += 2
                        else {
                            if (input[cursor] == '('.code.toByte()) depth++
                            if (input[cursor] == ')'.code.toByte()) depth--
                            cursor++
                        }
                    }
                    if (depth == 0) {
                        output.fill(' '.code.toByte(), value + 1, cursor - 1)
                        actions += "Nettoyage du champ PDF $key"
                    }
                } else if (input[value] == '<'.code.toByte() && value + 1 < input.size && input[value + 1] != '<'.code.toByte()) {
                    val end = indexOf(input, byteArrayOf('>'.code.toByte()), value + 1)
                    if (end > value) {
                        output.fill('0'.code.toByte(), value + 1, end)
                        actions += "Nettoyage du champ PDF $key"
                    }
                }
                at = maxOf(found + needle.size, value + 1)
            }
        }
        var xmp = indexOf(input, "<?xpacket".toByteArray(Charsets.ISO_8859_1), 0)
        while (xmp >= 0) {
            val endMarker = indexOf(input, "<?xpacket end".toByteArray(Charsets.ISO_8859_1), xmp + 9)
            if (endMarker < 0) break
            val end = indexOf(input, byteArrayOf('>'.code.toByte()), endMarker)
            if (end < 0) break
            output.fill(' '.code.toByte(), xmp, end + 1)
            actions += "Retrait du paquet XMP PDF"
            xmp = indexOf(input, "<?xpacket".toByteArray(Charsets.ISO_8859_1), end + 1)
        }
        val warning = "PDF nettoyé sans réécriture qpdf : les pièces jointes et métadonnées d'images peuvent nécessiter le backend desktop."
        return NativeCleanOutput(output, NativeCleanReport(CleanFileKind.PDF, input.size, output.size, !input.contentEquals(output), actions.distinct(), warnings = listOf(warning)))
    }

    // ---------------------------------------------------------------------------------------------
    // Audio/video containers
    // ---------------------------------------------------------------------------------------------

    private fun cleanWav(input: ByteArray): NativeCleanOutput {
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
            val drop = name == "C2PA" || name == "id3 " || name == "ID3 " || (name == "LIST" && payload.size >= 4 && String(payload, 0, 4, Charsets.ISO_8859_1) == "INFO")
            if (drop) actions += "Retrait du bloc WAV $name" else out.write(input, pos, (end - pos).toInt())
            pos = end.toInt()
        }
        val result = out.toByteArray()
        if (result.size >= 8) copyInto(result, 4, le32(result.size - 8))
        return outputFor(CleanFileKind.WAV, input, result, actions)
    }

    private fun cleanLeadingId3(input: ByteArray, kind: CleanFileKind): NativeCleanOutput {
        if (!startsWith(input, ID3_SIGNATURE) || input.size < 10) {
            return outputFor(kind, input, input, emptyList())
        }
        val tagSize = ((input[6].toInt() and 0x7F) shl 21) or
            ((input[7].toInt() and 0x7F) shl 14) or
            ((input[8].toInt() and 0x7F) shl 7) or
            (input[9].toInt() and 0x7F)
        val footer = if ((input[5].toInt() and 0x10) != 0) 10 else 0
        val total = 10L + tagSize + footer
        if (total > input.size) {
            return NativeCleanOutput(input, NativeCleanReport(kind, input.size, input.size, false, emptyList(), warnings = listOf("Tag ID3 tronqué : fichier conservé.")))
        }
        val output = input.copyOfRange(total.toInt(), input.size)
        return outputFor(kind, input, output, listOf("Retrait du tag ID3v2"))
    }

    private fun cleanIsoBmff(input: ByteArray, kind: CleanFileKind): NativeCleanOutput {
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
            val dropWhole = name.lowercase(Locale.US) in setOf("c2pa", "jumb") || name.lowercase(Locale.US).startsWith("c2") ||
                isUuid ||
                (name.equals("udta", ignoreCase = true) && containsProvenanceMarker(lowerPayload)) ||
                (name.equals("xml ", ignoreCase = true) && containsProvenanceMarker(lowerPayload))
            if (name.equals("meta", ignoreCase = true) && payload.size >= 4) {
                val cleanedMeta = cleanIsoMetaPayload(payload, actions)
                if (cleanedMeta != null) {
                    if (!cleanedMeta.contentEquals(payload)) actions += "Nettoyage des sous-boîtes meta ISO-BMFF"
                    out.write(buildIsoBox(type, cleanedMeta, header))
                } else if (containsProvenanceMarker(lowerPayload)) {
                    actions += "Neutralisation de la boîte MP4 $name"
                    out.write(freeBox(size, header))
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
    private fun cleanIsoMetaPayload(payload: ByteArray, actions: MutableList<String>): ByteArray? {
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
            val uuid = name.equals("uuid", ignoreCase = true)
            val drop = name.lowercase(Locale.US) in setOf("jumb", "c2pa", "xml ", "bxml") || name.lowercase(Locale.US).startsWith("c2") ||
                uuid || containsProvenanceMarker(lower)
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

    private fun outputFor(kind: CleanFileKind, input: ByteArray, output: ByteArray, actions: List<String>): NativeCleanOutput =
        NativeCleanOutput(output, NativeCleanReport(kind, input.size, output.size, !input.contentEquals(output), actions.distinct()))

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

    private fun containsProvenanceMarker(text: String): Boolean =
        listOf(
            "c2pa", "c2ma", "jumb", "contentcredentials", "contentauth", "cai:",
            "aigc", "synthid", "provenance", "digitalsourcetype", "trainedalgorithmicmedia",
            "algorithmicmedia", "dcterms:provenance",
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
    private val FTYP_SIGNATURE = "ftyp".toByteArray(Charsets.ISO_8859_1)
}

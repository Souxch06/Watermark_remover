package com.souxch.watermarkremover.cleaner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NativeWatermarkCleanerTest {
    @Test
    fun removesPngTextChunkWithoutTouchingPixels() {
        val png = pngWithTextChunk("c2pa", "provenance")
        val cleaned = NativeWatermarkCleaner.clean("photo.png", "image/png", png)

        assertTrue(cleaned.report.changed)
        assertFalse(cleaned.bytes.toString(Charsets.ISO_8859_1).contains("provenance"))
        assertTrue(cleaned.bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE))
    }

    @Test
    fun removesJpegApplicationMetadataAndKeepsSosBytes() {
        val input = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x0B,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
            'C'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), '0'.code.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0x11, 0x22, 0xFF.toByte(), 0xD9.toByte(),
        )
        val cleaned = NativeWatermarkCleaner.clean("photo.jpg", "image/jpeg", input)

        assertTrue(cleaned.report.changed)
        assertTrue(cleaned.bytes.contentEquals(byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0x11, 0x22, 0xFF.toByte(), 0xD9.toByte(),
        )))
    }

    @Test
    fun removesLeadingId3FromMp3() {
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4)
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        val cleaned = NativeWatermarkCleaner.clean("sound.mp3", "audio/mpeg", tag + audio)

        assertTrue(cleaned.report.changed)
        assertTrue(cleaned.bytes.contentEquals(audio))
    }

    @Test
    fun unknownBinaryIsNotTreatedAsText() {
        val input = byteArrayOf(0, 1, 2, 3, 4)
        val cleaned = NativeWatermarkCleaner.clean("data.bin", "application/octet-stream", input)

        assertFalse(cleaned.report.changed)
        assertTrue(cleaned.report.warnings.isNotEmpty())
    }

    @Test
    fun removesBmpTrailingMetadataAndKeepsPixelPayload() {
        val bmp = ByteArray(54 + 4 + 5).also { data ->
            data[0] = 'B'.code.toByte(); data[1] = 'M'.code.toByte()
            putLe32(data, 2, data.size)
            putLe32(data, 10, 54)
            putLe32(data, 14, 40)
            putLe32(data, 18, 1)
            putLe32(data, 22, 1)
            putLe16(data, 26, 1)
            putLe16(data, 28, 24)
            putLe32(data, 34, 4)
            data[54] = 1; data[55] = 2; data[56] = 3; data[57] = 0
            "c2pa".toByteArray().copyInto(data, 58)
        }
        val cleaned = NativeWatermarkCleaner.clean("photo.bmp", "image/bmp", bmp)

        assertTrue(cleaned.report.changed)
        assertTrue(cleaned.bytes.size == 58)
        assertTrue(cleaned.bytes.copyOfRange(54, 58).contentEquals(byteArrayOf(1, 2, 3, 0)))
        assertTrue(readLe32(cleaned.bytes, 2) == 58)
    }

    @Test
    fun removesTiffMetadataWithoutMovingTheIfd() {
        val tiff = ByteArray(31).also { data ->
            data[0] = 'I'.code.toByte(); data[1] = 'I'.code.toByte(); data[2] = 42; data[3] = 0
            putLe32(data, 4, 8)
            putLe16(data, 8, 1)
            putLe16(data, 10, 270) // ImageDescription
            putLe16(data, 12, 2) // ASCII
            putLe32(data, 14, 5)
            putLe32(data, 18, 26)
            putLe32(data, 22, 0)
            "c2pa".toByteArray().copyInto(data, 26)
            data[30] = 0
        }
        val cleaned = NativeWatermarkCleaner.clean("photo.tiff", "image/tiff", tiff)

        assertTrue(cleaned.report.changed)
        assertFalse(cleaned.bytes.toString(Charsets.ISO_8859_1).contains("c2pa"))
        assertTrue(cleaned.bytes[8].toInt() == 1)
    }

    @Test
    fun scrubsPdfInfoInPlaceAndReportsTheBackendLimit() {
        val pdf = "%PDF-1.7\n1 0 obj\n<< /Title (c2pa provenance) /Author <414243> >>\nendobj\n%%EOF".toByteArray()
        val cleaned = NativeWatermarkCleaner.clean("document.pdf", "application/pdf", pdf)

        assertTrue(cleaned.report.changed)
        assertFalse(cleaned.bytes.toString(Charsets.ISO_8859_1).contains("c2pa"))
        assertTrue(cleaned.report.warnings.any { it.contains("qpdf") })
    }

    @Test
    fun recognizesC2paBmffUuidWithoutAnAsciiMarker() {
        val uuid = byteArrayOf(
            0xD8.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0xD6.toByte(), 0x1B, 0x0E, 0x48, 0x3C,
            0x92.toByte(), 0x97.toByte(), 0x58, 0x28, 0x87.toByte(), 0x7E, 0xC4.toByte(), 0x81.toByte(),
        ) + byteArrayOf(1, 2, 3)
        val input = box("ftyp", "avif".toByteArray()) + box("uuid", uuid)
        val cleaned = NativeWatermarkCleaner.clean("image.avif", "image/avif", input)

        assertTrue(cleaned.report.changed)
        val uuidBox = 12 + 4
        assertTrue(cleaned.bytes.copyOfRange(uuidBox, uuidBox + 4).contentEquals("free".toByteArray()))
        assertTrue(readBe32(cleaned.bytes, 12) == readBe32(input, 12))
    }

    @Test
    fun keepsGifLoopControlExtension() {
        val gif = "GIF89a".toByteArray() + byteArrayOf(
            1, 0, 1, 0, 0, 0, 0, 0, 0, // logical screen descriptor
            0x21, 0xFF.toByte(), 0x0B,
        ) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0, 0x3B)
        val cleaned = NativeWatermarkCleaner.clean("animation.gif", "image/gif", gif)

        assertFalse(cleaned.report.changed)
        assertTrue(cleaned.bytes.contentEquals(gif))
    }

    @Test
    fun cleansHtmlMarkdownLatexSvgXmlAndJsonDocuments() {
        val html = "<meta charset=\"utf-8\"><meta name=\"generator\" content=\"ChatGPT\"><body data-ai-model=\"gpt\">Hello</body>"
        val htmlOut = NativeWatermarkCleaner.clean("page.html", "text/html", html.toByteArray())
        assertTrue(htmlOut.report.changed)
        assertFalse(String(htmlOut.bytes).contains("ChatGPT"))
        assertFalse(String(htmlOut.bytes).contains("data-ai"))

        val markdown = "---\ntitle: Demo\ngenerator: Claude\nauthor: you\n---\n\nBody\n"
        val markdownOut = NativeWatermarkCleaner.clean("note.md", "text/markdown", markdown.toByteArray())
        assertTrue(markdownOut.report.changed)
        assertFalse(String(markdownOut.bytes).contains("generator: Claude"))
        assertTrue(String(markdownOut.bytes).contains("Body"))

        val latex = "% !TEX program = pdflatex\n\\hypersetup{pdftitle={My Paper},pdfcreator={Claude},colorlinks=true}\nBody\n"
        val latexOut = NativeWatermarkCleaner.clean("paper.tex", "text/x-tex", latex.toByteArray())
        assertFalse(String(latexOut.bytes).contains("pdfcreator"))
        assertTrue(String(latexOut.bytes).contains("pdftitle={My Paper}"))
        assertTrue(String(latexOut.bytes).contains("colorlinks=true"))

        val svg = "<!DOCTYPE svg><svg data-ai=\"yes\"><!-- generated by AI --><metadata>c2pa</metadata><path/></svg>"
        val svgOut = NativeWatermarkCleaner.clean("image.svg", "image/svg+xml", svg.toByteArray())
        assertTrue(svgOut.report.changed)
        assertFalse(String(svgOut.bytes).contains("metadata"))
        assertFalse(String(svgOut.bytes).contains("generated by AI"))
        assertFalse(String(svgOut.bytes).contains("DOCTYPE"))

        val xml = "<root>before&#x200B;after<dc:creator>Claude</dc:creator></root>"
        val xmlOut = NativeWatermarkCleaner.clean("core.xml", "application/xml", xml.toByteArray())
        assertFalse(String(xmlOut.bytes).contains("200B"))
        assertFalse(String(xmlOut.bytes).contains("creator"))

        val json = "{\"title\":\"Keep\",\"c2pa\":{\"claim_generator\":\"x\"},\"nested\":{\"provenance\":\"x\"}}"
        val jsonOut = NativeWatermarkCleaner.clean("manifest.json", "application/json", json.toByteArray())
        assertTrue(String(jsonOut.bytes).contains("title"))
        assertFalse(String(jsonOut.bytes).contains("c2pa"))
        assertFalse(String(jsonOut.bytes).contains("provenance"))
        assertTrue(String(jsonOut.bytes).trim().endsWith("}"))
    }

    @Test
    fun selectiveModeKeepsOrdinaryMetadataButRemovesProvenance() {
        val input = pngWithTextChunks(listOf("Comment" to "a human note", "c2pa" to "manifest"))
        val cleaned = NativeWatermarkCleaner.clean(
            "photo.png",
            "image/png",
            input,
            NativeCleanOptions(stripAllMetadata = false),
        )
        assertTrue(cleaned.report.changed)
        assertTrue(String(cleaned.bytes, Charsets.ISO_8859_1).contains("a human note"))
        assertFalse(String(cleaned.bytes, Charsets.ISO_8859_1).contains("manifest"))
    }

    @Test
    fun cleansFlacMetadataBlocksAndKeepsStreamInfoAndAudio() {
        val streamInfo = ByteArray(34) { it.toByte() }
        val comment = "vendor=Claude".toByteArray(Charsets.UTF_8)
        val input = "fLaC".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0, 0, 34) + streamInfo +
            byteArrayOf(0x84.toByte(), 0, 0, comment.size.toByte()) + comment +
            byteArrayOf(0x11, 0x22, 0x33)
        val cleaned = NativeWatermarkCleaner.clean("sound.flac", "audio/flac", input)
        assertTrue(cleaned.report.changed)
        assertTrue(String(cleaned.bytes, Charsets.ISO_8859_1).startsWith("fLaC"))
        assertFalse(String(cleaned.bytes, Charsets.ISO_8859_1).contains("Claude"))
        assertTrue(cleaned.bytes.takeLast(3).toByteArray().contentEquals(byteArrayOf(0x11, 0x22, 0x33)))
    }

    @Test
    fun cleansXmlAndImagesInsideZipContainer() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("docProps/core.xml"))
            zip.write("<cp:coreProperties><dc:creator>Claude</dc:creator></cp:coreProperties>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<document>Hello\u200Bworld</document>".toByteArray())
            zip.closeEntry()
        }
        val cleaned = NativeWatermarkCleaner.clean(
            "book.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            archive.toByteArray(),
        )
        assertTrue(cleaned.report.changed)
        val members = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(cleaned.bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                members[entry.name] = zip.readBytes()
            }
        }
        assertFalse(String(members.getValue("docProps/core.xml")).contains("Claude"))
        assertFalse(String(members.getValue("word/document.xml")).contains("\u200B"))
    }

    private fun putLe16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }

    private fun putLe32(data: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) data[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun readLe32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun readBe32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)

    private fun box(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val size = payload.size + 8
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
        ) + typeBytes + payload
    }

    private fun pngWithTextChunk(key: String, value: String): ByteArray = pngWithTextChunks(listOf(key to value))

    private fun pngWithTextChunks(entries: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        out.write(chunk("IHDR", ByteArray(13)))
        entries.forEach { (key, value) ->
            out.write(chunk("tEXt", "$key\u0000$value".toByteArray(Charsets.ISO_8859_1)))
        }
        out.write(chunk("IEND", byteArrayOf()))
        return out.toByteArray()
    }

    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(typeBytes); update(payload) }.value.toInt()
        return be32(payload.size) + typeBytes + payload + be32(crc)
    }

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

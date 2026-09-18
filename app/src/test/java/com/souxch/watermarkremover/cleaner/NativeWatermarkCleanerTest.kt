package com.souxch.watermarkremover.cleaner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

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

    private fun pngWithTextChunk(key: String, value: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        out.write(chunk("IHDR", ByteArray(13)))
        out.write(chunk("tEXt", "$key\u0000$value".toByteArray(Charsets.ISO_8859_1)))
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

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

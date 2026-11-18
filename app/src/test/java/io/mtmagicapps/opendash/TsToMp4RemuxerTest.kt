package io.mtmagicapps.opendash

import io.mtmagicapps.opendash.protocol.TsToMp4Remuxer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class TsToMp4RemuxerTest {

    @Test
    fun `strips 7-byte ADTS header from single AAC frame`() {
        val frameLen = 768
        val buf = ByteBuffer.allocate(frameLen)
        // Exact ADTS header observed on dashcam: ff f1 60 40 60 00 00 (frameLen=768, no CRC)
        buf.put(0, 0xFF.toByte())
        buf.put(1, 0xF1.toByte())
        buf.put(2, 0x60.toByte())
        buf.put(3, 0x40.toByte())
        buf.put(4, 0x60.toByte())
        buf.put(5, 0x00.toByte())
        buf.put(6, 0x00.toByte())

        val frames = TsToMp4Remuxer.stripAdtsFrames(buf, frameLen)
        assertEquals(listOf(7 to 761), frames)
    }

    @Test
    fun `strips multiple concatenated ADTS frames`() {
        val len1 = 16
        val len2 = 24
        val buf = ByteBuffer.allocate(len1 + len2)
        // Frame 1 (16 bytes, no CRC)
        buf.put(0, 0xFF.toByte())
        buf.put(1, 0xF1.toByte())
        buf.put(3, 0x00.toByte())
        buf.put(4, ((len1 ushr 3) and 0xFF).toByte())
        buf.put(5, ((len1 and 0x07) shl 5).toByte())
        // Frame 2 (24 bytes, no CRC)
        buf.put(len1 + 0, 0xFF.toByte())
        buf.put(len1 + 1, 0xF1.toByte())
        buf.put(len1 + 3, 0x00.toByte())
        buf.put(len1 + 4, ((len2 ushr 3) and 0xFF).toByte())
        buf.put(len1 + 5, ((len2 and 0x07) shl 5).toByte())

        val frames = TsToMp4Remuxer.stripAdtsFrames(buf, len1 + len2)
        assertEquals(listOf(7 to 9, (len1 + 7) to 17), frames)
    }

    @Test
    fun `keeps raw AAC frame without ADTS syncword untouched`() {
        val buf = ByteBuffer.wrap(byteArrayOf(0xDE.toByte(), 0x04, 0x00, 0x4C, 0x61, 0x76, 0x63, 0x35))
        val frames = TsToMp4Remuxer.stripAdtsFrames(buf, 8)
        assertEquals(listOf(0 to 8), frames)
    }
}

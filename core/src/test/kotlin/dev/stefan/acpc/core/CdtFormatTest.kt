package dev.stefan.acpc.core

import dev.stefan.acpc.core.tape.CdtFormat
import dev.stefan.acpc.core.tape.InvalidTapeException
import dev.stefan.acpc.core.tape.Tape
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CdtFormatTest {
    private fun header() = "ZXTape!".toByteArray() + byteArrayOf(0x1A, 1, 20)
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())

    @Test
    fun `pure tone block produces the requested pulses scaled to 4 MHz`() {
        val cdt = header() + byteArrayOf(0x12) + le16(700) + le16(10)
        val img = CdtFormat.parse(cdt)
        assertEquals(10, img.edges.size)
        assertEquals(800L, img.edges[0])                     // 700 T at 3.5 MHz = 800 cycles at 4 MHz
        assertEquals(8000L, img.totalCycles)
    }

    @Test
    fun `turbo data block encodes bits as pulse pairs`() {
        // pilot 2 pulses of 1000, sync 500/500, zero 100, one 200, one data byte 0xA0 with 3 used bits (1,0,1), no pause
        val cdt = header() + byteArrayOf(0x11) + le16(1000) + le16(500) + le16(500) + le16(100) + le16(200) + le16(2) +
            byteArrayOf(3) + le16(0) + le24(1) + byteArrayOf(0xA0.toByte())
        val img = CdtFormat.parse(cdt)
        // 2 pilot + 2 sync + 3 bits * 2 pulses = 10 edges
        assertEquals(10, img.edges.size)
        val d = img.edges.toList().zipWithNext { a, b -> b - a }
        val t = { x: Int -> (x * 8L + 3) / 7 }
        assertEquals(listOf(t(1000), t(500), t(500), t(200), t(200), t(100), t(100), t(200), t(200)), d)
    }

    @Test
    fun `loops repeat their content and pauses add silence`() {
        val cdt = header() + byteArrayOf(0x24) + le16(3) + byteArrayOf(0x12) + le16(700) + le16(2) + byteArrayOf(0x25) +
            byteArrayOf(0x20) + le16(10)
        val img = CdtFormat.parse(cdt)
        assertEquals(6, img.edges.size)                      // 3 x 2 pulses; the level is already low before the pause
        assertEquals(6 * 800L + 10 * 4000L, img.totalCycles)
    }

    @Test
    fun `tape plays only while the motor runs`() {
        val cdt = header() + byteArrayOf(0x12) + le16(700) + le16(4)
        val tape = Tape(CdtFormat.parse(cdt), "t")
        assertFalse(tape.level(100_000))                     // motor off: nothing moves
        tape.setMotor(true, 100_000)
        assertFalse(tape.level(100_000 + 799))
        assertTrue(tape.level(100_000 + 800))                // first edge passed
        assertFalse(tape.level(100_000 + 1600))
        tape.setMotor(false, 100_000 + 1700)
        assertFalse(tape.level(500_000))                     // frozen
        assertEquals(1700L, tape.position)
        tape.rewind()
        assertEquals(0L, tape.position)
    }

    @Test
    fun `unsupported and malformed images are rejected`() {
        assertThrows(InvalidTapeException::class.java) { CdtFormat.parse(ByteArray(20)) }
        assertThrows(InvalidTapeException::class.java) { CdtFormat.parse(header() + byteArrayOf(0x19) + le16(5) + le16(0)) }
        assertThrows(InvalidTapeException::class.java) { CdtFormat.parse(header() + byteArrayOf(0x11) + le16(1000)) }
    }
}

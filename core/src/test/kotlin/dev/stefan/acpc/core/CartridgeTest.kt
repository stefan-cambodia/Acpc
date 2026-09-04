package dev.stefan.acpc.core

import dev.stefan.acpc.core.cartridge.Cartridge
import dev.stefan.acpc.core.cartridge.InvalidCartridgeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class CartridgeTest {

    /** Builds a RIFF AMS! container with the given (page number, fill byte) chunks. */
    private fun cpr(vararg pages: Pair<Int, Int>, chunkSize: Int = Cartridge.PAGE_SIZE): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("AMS!".toByteArray())
        for ((number, fill) in pages) {
            body.write("cb%02d".format(number).toByteArray())
            body.write(le32(chunkSize))
            body.write(ByteArray(chunkSize) { fill.toByte() })
        }
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(le32(body.size()))
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    @Test
    fun `riff pages are found by chunk name and missing pages read as FF`() {
        val cart = Cartridge.parse(cpr(0 to 0x11, 1 to 0x22, 3 to 0x44))
        assertEquals(4, cart.pageCount)
        assertEquals(0x11, cart.page(0)[0].toInt() and 0xFF)
        assertEquals(0x22, cart.page(1)[100].toInt() and 0xFF)
        assertEquals(0xFF, cart.page(2)[0].toInt() and 0xFF)
        assertEquals(0x44, cart.page(3)[Cartridge.PAGE_SIZE - 1].toInt() and 0xFF)
        // Four pages: page numbers wrap on 4 like the address decoding of a small cartridge.
        assertEquals(0x11, cart.page(4)[0].toInt() and 0xFF)
        assertEquals(0x44, cart.page(31)[0].toInt() and 0xFF)
        assertFalse(cart.isSystemCartridge)
    }

    @Test
    fun `short chunks are padded and odd chunks are word aligned`() {
        val cart = Cartridge.parse(cpr(0 to 0x55, chunkSize = 1001))
        assertEquals(0x55, cart.page(0)[1000].toInt() and 0xFF)
        assertEquals(0xFF, cart.page(0)[1001].toInt() and 0xFF)
    }

    @Test
    fun `raw dumps are accepted when they are a multiple of 16 KB`() {
        val raw = ByteArray(3 * Cartridge.PAGE_SIZE) { (it / Cartridge.PAGE_SIZE).toByte() }
        val cart = Cartridge.parse(raw, "game.bin")
        assertEquals(3, cart.pageCount)
        assertEquals(2, cart.page(2)[0].toInt())
        assertEquals(0xFF, cart.page(3)[0].toInt() and 0xFF) // wraps on 4, page 3 absent
        assertThrows(InvalidCartridgeException::class.java) { Cartridge.parse(ByteArray(1000)) }
        assertThrows(InvalidCartridgeException::class.java) { Cartridge.parse(ByteArray(0)) }
    }

    @Test
    fun `a system cartridge is recognised by its firmware banner`() {
        val banner = "Amstrad 128K Microcomputer  (v4)".toByteArray()
        val page = ByteArray(Cartridge.PAGE_SIZE)
        System.arraycopy(banner, 0, page, 0x100, banner.size)
        val cart = Cartridge.parse(page + ByteArray(Cartridge.PAGE_SIZE))
        assertTrue(cart.isSystemCartridge)
        assertTrue(Cartridge.isCpr(cpr(0 to 0)))
        assertFalse(Cartridge.isCpr(page))
    }
}

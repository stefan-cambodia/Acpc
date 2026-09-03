package dev.stefan.acpc.core

import dev.stefan.acpc.core.api.InvalidDiskImageException
import dev.stefan.acpc.core.disk.AmsdosCatalog
import dev.stefan.acpc.core.disk.DiskImage
import dev.stefan.acpc.core.disk.DskFormat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DiskImageTest {
    @Test
    fun `formatted data disc round-trips through the extended DSK format`() {
        val image = DiskImage.formattedData()
        image.track(0, 3)!!.sectors.first { it.r == 0xC5 }.write(ByteArray(512) { it.toByte() })
        val bytes = DskFormat.write(image)
        assertEquals("EXTENDED", String(bytes, 0, 8, Charsets.ISO_8859_1))
        val back = DskFormat.read(bytes)
        assertEquals(1, back.sides)
        assertEquals(40, back.trackCount)
        assertEquals(9, back.track(0, 0)!!.sectors.size)
        assertEquals(listOf(0xC1, 0xC6, 0xC2, 0xC7, 0xC3, 0xC8, 0xC4, 0xC9, 0xC5), back.track(0, 0)!!.sectors.map { it.r })
        assertArrayEquals(ByteArray(512) { it.toByte() }, back.track(0, 3)!!.sectors.first { it.r == 0xC5 }.data)
        assertEquals(AmsdosCatalog.Format.DATA, AmsdosCatalog.detectFormat(back))
    }

    @Test
    fun `standard DSK images are parsed`() {
        // Build a minimal standard (non extended) image by hand: 2 tracks, 1 side, 2 sectors of 512 bytes.
        val trackSize = 256 + 2 * 512
        val bytes = ByteArray(256 + 2 * trackSize)
        "MV - CPCEMU Disk-File\r\nDisk-Info\r\n".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0)
        bytes[0x30] = 2; bytes[0x31] = 1
        bytes[0x32] = (trackSize and 0xFF).toByte(); bytes[0x33] = (trackSize ushr 8).toByte()
        for (t in 0 until 2) {
            val o = 256 + t * trackSize
            "Track-Info\r\n".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, o)
            bytes[o + 0x10] = t.toByte(); bytes[o + 0x14] = 2; bytes[o + 0x15] = 2; bytes[o + 0x16] = 0x4E; bytes[o + 0x17] = 0xE5.toByte()
            for (s in 0 until 2) {
                val i = o + 0x18 + s * 8
                bytes[i] = t.toByte(); bytes[i + 2] = (0xC1 + s).toByte(); bytes[i + 3] = 2
                bytes[o + 256 + s * 512] = (0x10 * t + s).toByte()
            }
        }
        val image = DskFormat.read(bytes)
        assertEquals(2, image.trackCount)
        assertEquals(0x11, image.track(0, 1)!!.sectors[1].data[0].toInt())
        assertEquals(512, image.track(0, 1)!!.sectors[1].data.size)
    }

    @Test
    fun `weak sectors cycle through their copies`() {
        val sector = DiskImage.Sector(0, 0, 0xC1, 2, 0x20, 0x20, ByteArray(1024) { if (it < 512) 1 else 2 })
        assertEquals(2, sector.copies)
        assertEquals(1, sector.readCopy()[0].toInt())
        assertEquals(2, sector.readCopy()[0].toInt())
        assertEquals(1, sector.readCopy()[0].toInt())
    }

    @Test
    fun `invalid images are rejected with a clear error`() {
        assertThrows<InvalidDiskImageException> { DskFormat.read(ByteArray(10)) }
        assertThrows<InvalidDiskImageException> { DskFormat.read(ByteArray(1000)) }
        val garbage = ByteArray(2000) { 0x41 }
        "MV - CPC".toByteArray().copyInto(garbage, 0)
        garbage[0x30] = 1; garbage[0x31] = 1
        assertThrows<InvalidDiskImageException> { DskFormat.read(garbage) }
    }

    @Test
    fun `truncated images keep the readable tracks`() {
        val full = DskFormat.write(DiskImage.formattedData())
        val truncated = full.copyOf(full.size / 2)
        val image = DskFormat.read(truncated)
        assertNotNull(image.track(0, 0))
        assertNull(image.track(0, 39))
    }

    @Test
    fun `auto start picks the most plausible file`() {
        val image = DiskImage.formattedData()
        // Write a directory with a few entries.
        val dir = ByteArray(2048) { 0xE5.toByte() }
        fun entry(index: Int, name: String, ext: String) {
            val o = index * 32
            for (i in 0 until 32) dir[o + i] = 0
            for (i in 0 until 8) dir[o + 1 + i] = (name.padEnd(8)[i]).code.toByte()
            for (i in 0 until 3) dir[o + 9 + i] = (ext.padEnd(3)[i]).code.toByte()
            dir[o + 15] = 8
        }
        entry(0, "GAME", "BIN")
        entry(1, "DISC", "BAS")
        entry(2, "LEVEL1", "DAT")
        val track0 = image.track(0, 0)!!
        for ((i, r) in listOf(0xC1, 0xC2, 0xC3, 0xC4).withIndex()) {
            track0.sectors.first { it.r == r }.write(dir.copyOfRange(i * 512, i * 512 + 512))
        }
        val files = AmsdosCatalog.list(image)
        assertEquals(listOf("GAME.BIN", "DISC.BAS", "LEVEL1.DAT"), files.map { it.fileName })
        assertEquals("RUN\"DISC.BAS\n", AmsdosCatalog.autoStartCommand(image))
        assertTrue(files.all { it.sizeKb == 1 })
    }
}

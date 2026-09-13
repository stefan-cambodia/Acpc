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

    /** Writes AMSDOS directory entries and file headers on a DATA disc. */
    private class CatalogBuilder(val image: DiskImage = DiskImage.formattedData()) {
        private val dir = ByteArray(2048) { 0xE5.toByte() }
        private var index = 0
        private var nextBlock = 2

        /** [type] null writes no header (raw data in the first block). */
        fun file(name: String, ext: String, type: Int?, exec: Int = 0, load: Int = 0x4000, firstBytes: ByteArray? = null) {
            val o = index++ * 32
            for (i in 0 until 32) dir[o + i] = 0
            for (i in 0 until 8) dir[o + 1 + i] = (name.padEnd(8)[i]).code.toByte()
            for (i in 0 until 3) dir[o + 9 + i] = (ext.padEnd(3)[i]).code.toByte()
            dir[o + 15] = 8
            val block = nextBlock++
            dir[o + 16] = block.toByte()
            val record = firstBytes?.copyOf(512) ?: ByteArray(512)
            if (type != null) {
                for (i in 0 until 8) record[1 + i] = (name.padEnd(8)[i]).code.toByte()
                record[18] = type.toByte()
                record[21] = load.toByte(); record[22] = (load ushr 8).toByte()
                record[26] = exec.toByte(); record[27] = (exec ushr 8).toByte()
                record[64] = 0x00; record[65] = 0x10
                val sum = (0 until 67).sumOf { record[it].toInt() and 0xFF }
                record[67] = sum.toByte(); record[68] = (sum ushr 8).toByte()
            }
            sector(block * 2).write(record)
        }

        private fun sector(index: Int) = image.track(0, index / 9)!!.sectors.first { it.r == 0xC1 + index % 9 }

        fun build(name: String = "disc.dsk"): DiskImage {
            for (i in 0 until 4) sector(i).write(dir.copyOfRange(i * 512, i * 512 + 512))
            image.name = name
            return image
        }
    }

    @Test
    fun `auto start reads the file headers`() {
        val image = CatalogBuilder().apply {
            file("LOADER", "BIN", 2, exec = 0)          // second stage: its entry would reset the machine
            file("CODE", "N01", 2, exec = 0x8000)       // later stage with an unusual extension
            file("DISC", "BIN", 1, load = 0x170)        // protected BASIC despite the extension
            file("NOTES", "", null, firstBytes = "Hello".toByteArray())
        }.build()
        val files = AmsdosCatalog.list(image).associateBy { it.fileName }
        assertEquals(false, files.getValue("LOADER.BIN").runnable)
        assertEquals(true, files.getValue("DISC.BIN").runnable)
        assertEquals(true, files.getValue("DISC.BIN").header!!.isBasic)
        assertEquals(false, files.getValue("NOTES").runnable)
        assertEquals("RUN\"DISC.BIN\n", AmsdosCatalog.autoStartCommand(image))
    }

    @Test
    fun `auto start prefers a runnable binary to a BASIC file with an unusual extension`() {
        val image = CatalogBuilder().apply {
            file("CAPBLOOD", "BID", 0, load = 0x170)
            file("CAPBLOOD", "", 2, exec = 0x646F)
            file("CAPBLOOD", "TMA", 2, exec = 0)
        }.build("Captain Blood.dsk")
        assertEquals("RUN\"CAPBLOOD\n", AmsdosCatalog.autoStartCommand(image))
    }

    @Test
    fun `auto start recognises a headerless BASIC listing and the loader named like the game`() {
        val image = CatalogBuilder().apply {
            file("AAAA", "BIN", 2, exec = 0x40, load = 0x40)
            file("MANSELL", "BIN", 2, exec = 0x3EDA)
            file("README", "BAS", null, firstBytes = "10 PRINT \"HELLO\"\r\n".toByteArray())
        }.build("Nigel Mansell's Grand Prix (1988)(Martech Games).dsk")
        val files = AmsdosCatalog.list(image).associateBy { it.fileName }
        assertEquals(true, files.getValue("README.BAS").asciiBasic)
        assertEquals("RUN\"MANSELL.BIN\n", AmsdosCatalog.autoStartCommand(image))
    }

    @Test
    fun `a CP-M disc without an AMSDOS directory boots with CPM`() {
        val image = DiskImage.blank(1, 40, "cpm.dsk")
        // Boot track in SYSTEM format, then a custom format of five 1024-byte sectors.
        image.tracks[0][0] = DiskImage.Track(0, 0, 2, 0x4E, 0xE5, MutableList(9) { DiskImage.Sector(0, 0, 0x41 + it, 2, 0, 0, ByteArray(512)) })
        for (t in 1 until 40) {
            image.tracks[0][t] = DiskImage.Track(t, 0, 3, 0x4E, 0xE5, MutableList(5) { DiskImage.Sector(t, 0, it, 3, 0, 0, ByteArray(1024) { 0x3C }) })
        }
        assertTrue(AmsdosCatalog.list(image).isEmpty())
        assertEquals("|CPM\n", AmsdosCatalog.autoStartCommand(image))
    }
}

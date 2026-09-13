package dev.stefan.acpc.core

import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.memory.CpcMemory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CpcMemoryTest {
    @Test
    fun `reset maps lower ROM, RAM and BASIC ROM`() {
        val mem = CpcMemory(CpcModel.CPC6128, TestRoms.synthetic())
        assertEquals(0x11, mem.read(0x0000))
        assertEquals(0x11, mem.read(0x3FFF))
        assertEquals(0x00, mem.read(0x4000))
        assertEquals(0x00, mem.read(0xBFFF))
        assertEquals(0x22, mem.read(0xC000))
        assertEquals(0x22, mem.read(0xFFFF))
    }

    @Test
    fun `writes always reach RAM even under a ROM`() {
        val mem = CpcMemory(CpcModel.CPC464, TestRoms.synthetic())
        mem.write(0x0010, 0xAB)
        mem.write(0xC010, 0xCD)
        assertEquals(0x11, mem.read(0x0010))
        assertEquals(0x22, mem.read(0xC010))
        mem.setRomEnables(lowerEnabled = false, upperEnabled = false)
        assertEquals(0xAB, mem.read(0x0010))
        assertEquals(0xCD, mem.read(0xC010))
        assertEquals(0xAB, mem.videoRead(0x0010))
    }

    @Test
    fun `upper ROM selection falls back to BASIC for missing ROMs`() {
        val mem = CpcMemory(CpcModel.CPC6128, TestRoms.synthetic())
        mem.selectUpperRom(7)
        assertEquals(0x77, mem.read(0xC000))
        mem.selectUpperRom(3)
        assertEquals(0x22, mem.read(0xC000))
        mem.selectUpperRom(0x87) // only low byte matters on the port, but 0x87 is a distinct number
        assertEquals(0x22, mem.read(0xC000))
    }

    @Test
    fun `6128 RAM configurations`() {
        val mem = CpcMemory(CpcModel.CPC6128, TestRoms.synthetic())
        mem.setRomEnables(lowerEnabled = false, upperEnabled = false)
        // Tag each physical page with its number.
        for (page in 0 until 8) mem.ram[page * 0x4000] = page.toByte()
        val expected = mapOf(
            0 to intArrayOf(0, 1, 2, 3),
            1 to intArrayOf(0, 1, 2, 7),
            2 to intArrayOf(4, 5, 6, 7),
            3 to intArrayOf(0, 3, 2, 7),
            4 to intArrayOf(0, 4, 2, 3),
            5 to intArrayOf(0, 5, 2, 3),
            6 to intArrayOf(0, 6, 2, 3),
            7 to intArrayOf(0, 7, 2, 3),
        )
        for ((config, pages) in expected) {
            mem.setRamConfig(0xC0 or config)
            for (block in 0 until 4) {
                assertEquals(pages[block], mem.read(block * 0x4000), "config $config block $block")
            }
        }
        // Writes go to the mapped page.
        mem.setRamConfig(0xC4)
        mem.write(0x4000 + 5, 0x5A)
        mem.setRamConfig(0xC0)
        assertEquals(0, mem.read(0x4000 + 5))
        mem.setRamConfig(0xC2)
        assertEquals(0x5A, mem.read(5))
        // The video hardware keeps reading the base 64 KB.
        assertEquals(0, mem.videoRead(0x4000 + 5))
    }

    @Test
    fun `6128 ignores the expansion bank bits`() {
        // International 3D Tennis writes its configurations with bits 3-5 set.
        val mem = CpcMemory(CpcModel.CPC6128, TestRoms.synthetic())
        mem.setRomEnables(lowerEnabled = false, upperEnabled = false)
        for (page in 0 until 8) mem.ram[page * 0x4000] = page.toByte()
        mem.setRamConfig(0xFF)
        assertEquals(7, mem.read(0x4000))
        mem.setRamConfig(0xF2)
        assertEquals(4, mem.read(0x0000))
        mem.setRamConfig(0xF8)
        assertEquals(1, mem.read(0x4000))
    }

    @Test
    fun `a Plus shows RAM at C000 until a ROM is selected`() {
        val pages = ByteArray(4 * dev.stefan.acpc.core.cartridge.Cartridge.PAGE_SIZE) { (it / dev.stefan.acpc.core.cartridge.Cartridge.PAGE_SIZE + 0x10).toByte() }
        val cart = dev.stefan.acpc.core.cartridge.Cartridge.parse(pages, "game.bin")
        val mem = CpcMemory(CpcModel.GX4000, null, cart)
        mem.write(0xFFFE, 0x3D)
        assertEquals(0x3D, mem.read(0xFFFE))       // No Exit's return address, stack still at &FFFF
        assertEquals(0x10, mem.read(0x0000))       // page 0 as the lower ROM
        mem.selectUpperRom(0)
        assertEquals(0x11, mem.read(0xFFFE))       // ROM 0 is cartridge page 1
    }

    @Test
    fun `464 ignores RAM configuration`() {
        val mem = CpcMemory(CpcModel.CPC464, TestRoms.synthetic())
        mem.setRomEnables(lowerEnabled = false, upperEnabled = false)
        mem.ram[0x4000] = 1
        mem.setRamConfig(0xC2)
        assertEquals(1, mem.read(0x4000))
        assertEquals(0, mem.ramConfig)
    }
}

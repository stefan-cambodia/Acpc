package dev.stefan.acpc.core

import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import dev.stefan.acpc.core.cartridge.Cartridge
import dev.stefan.acpc.core.machine.CpcMachine
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.memory.CpcMemory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Plus ASIC driven through the machine's I/O ports and memory, on a
 * GX4000 booting a synthetic cartridge whose page 0 holds a tiny program.
 */
class AsicTest {

    /** Page 0 program: interrupts off (or IM 1 / IM 2 with EI), then an endless loop. */
    private fun cartridge(program: IntArray, handlerAt38: IntArray = intArrayOf()): Cartridge {
        val page0 = ByteArray(Cartridge.PAGE_SIZE)
        program.forEachIndexed { i, b -> page0[i] = b.toByte() }
        handlerAt38.forEachIndexed { i, b -> page0[0x38 + i] = b.toByte() }
        val page1 = ByteArray(Cartridge.PAGE_SIZE) { 0x11 }
        val page3 = ByteArray(Cartridge.PAGE_SIZE) { 0x33 }
        return Cartridge.parse(page0 + page1 + ByteArray(Cartridge.PAGE_SIZE) { 0x22 } + page3, "test.bin")
    }

    private val idle = intArrayOf(0xF3, 0x18, 0xFE)                    // DI ; JR $
    private val im1 = intArrayOf(0xF3, 0x31, 0x00, 0xC0, 0xED, 0x56, 0xFB, 0x18, 0xFE)   // DI; LD SP,C000; IM 1; EI; JR $
    private val im2 = intArrayOf(0xF3, 0x31, 0x00, 0xC0, 0x3E, 0x80, 0xED, 0x47, 0xED, 0x5E, 0xFB, 0x18, 0xFE) // ... LD I,80h; IM 2
    /** LD HL,(A000); INC HL; LD (A000),HL; EI; RET */
    private val countAtA000 = intArrayOf(0x2A, 0x00, 0xA0, 0x23, 0x22, 0x00, 0xA0, 0xFB, 0xC9)

    private fun boot(program: IntArray = idle, handler: IntArray = intArrayOf()): CpcEmulator =
        CpcEmulator.createMachine(CpcModel.GX4000, null, NullAudioSink(), cartridge = cartridge(program, handler))

    private fun CpcMachine.out(port: Int, value: Int) = writeIo(port, value)

    private fun CpcMachine.unlock() {
        for (b in UNLOCK) out(0xBC00, b)
    }

    private fun counter(m: CpcMachine, address: Int = 0xA000) = m.memory.videoRead(address) or (m.memory.videoRead(address + 1) shl 8)

    @Test
    fun `the unlock sequence unlocks and a wrong final byte locks`() {
        val m = boot().machine
        val asic = m.asic!!
        assertTrue(asic.locked)
        m.unlock()
        assertFalse(asic.locked)
        // Same sequence ending with &00 instead of &EE: locks again.
        for (b in UNLOCK.dropLast(1)) m.out(0xBC00, b)
        m.out(0xBC00, 0x00)
        assertTrue(asic.locked)
        // A stray byte in the middle keeps it locked.
        for (b in UNLOCK.take(6)) m.out(0xBC00, b)
        m.out(0xBC00, 0x12)
        for (b in UNLOCK.drop(6)) m.out(0xBC00, b)
        assertTrue(asic.locked)
        // The sequence is only recognised on the CRTC register select port.
        m.unlock()
        assertFalse(asic.locked)
    }

    @Test
    fun `RMR2 positions the lower ROM page and maps the I O page`() {
        val m = boot().machine
        val mem = m.memory
        assertEquals(0xF3, mem.read(0x0000))           // cartridge page 0 at &0000
        m.out(0x7F00, 0xA1 or 0x08)                    // ignored while locked
        assertEquals(0xF3, mem.read(0x0000))
        m.unlock()
        m.out(0x7F00, 0xA0 or 0x08 or 0x01)            // page 1 at &4000
        assertEquals(0x11, mem.read(0x4000))
        assertEquals(0x00, mem.read(0x0000))           // no ROM at &0000 any more: the RAM underneath
        m.out(0x7F00, 0xA0 or 0x10 or 0x03)            // page 3 at &8000
        assertEquals(0x33, mem.read(0x8000))
        m.out(0x7F00, 0xA0 or 0x18)                    // page 0 at &0000 + ASIC page at &4000
        assertTrue(mem.asicPageMapped)
        assertEquals(0xF3, mem.read(0x0000))
        m.writeMem(0x6800, 0x55)
        assertEquals(0x55, m.asic!!.pri)
        assertEquals(0x55, mem.read(0x6800))
        assertEquals(0x55, mem.read(0x6800))
        // Sprite pixel data keeps only the low nibble.
        m.writeMem(0x4000, 0xAB)
        assertEquals(0x0B, mem.read(0x4000))
        // Locking hides the page again: plain RAM underneath.
        for (b in UNLOCK.dropLast(1)) m.out(0xBC00, b)
        m.out(0xBC00, 0x00)
        assertFalse(mem.asicPageMapped)
        assertEquals(0x0B, m.asic!!.read(0x0000))
    }

    @Test
    fun `upper ROM numbers select cartridge pages`() {
        assertEquals(1, CpcMemory.cartridgePageForRom(0))
        assertEquals(3, CpcMemory.cartridgePageForRom(7))
        assertEquals(1, CpcMemory.cartridgePageForRom(5))
        assertEquals(0, CpcMemory.cartridgePageForRom(128))
        assertEquals(5, CpcMemory.cartridgePageForRom(133))
        assertEquals(31, CpcMemory.cartridgePageForRom(159))
        val m = boot().machine
        assertEquals(0x11, m.memory.read(0xC000))       // ROM 0 = page 1 (BASIC on a system cartridge)
        m.out(0xDF00, 7)
        assertEquals(0x33, m.memory.read(0xC000))
        m.out(0xDF00, 0x82)
        assertEquals(0x22, m.memory.read(0xC000))
    }

    @Test
    fun `palette entries come from the ASIC and from Gate Array pen writes`() {
        val m = boot().machine
        val asic = m.asic!!
        m.unlock()
        m.out(0x7F00, 0xB8)
        m.writeMem(0x6400, 0xF0)    // R = 15, B = 0
        m.writeMem(0x6401, 0x08)    // G = 8
        assertEquals(0xFFFF8800.toInt(), asic.paletteArgb[0])
        assertEquals(0x08, m.memory.read(0x6401))
        m.out(0x7F00, 0x00)         // select pen 0
        m.out(0x7F00, 0x40 or 0x0B) // hardware colour 11 = bright white
        assertEquals(0xFFFFFFFF.toInt(), asic.paletteArgb[0])
        assertEquals(0xFF, m.memory.read(0x6400))
        m.out(0x7F00, 0x10)         // border
        m.out(0x7F00, 0x40 or 0x14) // hardware colour 20 = black
        assertEquals(0xFF000000.toInt(), asic.paletteArgb[16])
    }

    private fun countColour(emu: CpcEmulator, colour: Int): Triple<Int, Int, Int> {
        val f = emu.runFrame()
        var n = 0; var minX = Int.MAX_VALUE; var maxX = -1
        for (y in 0 until f.lines) for (x in 0 until f.stride) {
            if (f.pixels[y * f.stride + x] == colour) { n++; if (x < minX) minX = x; if (x > maxX) maxX = x }
        }
        return Triple(n, minX, maxX)
    }

    @Test
    fun `hardware sprites are drawn with their magnification and transparency`() {
        val emu = boot()
        val m = emu.machine
        m.unlock()
        m.out(0x7F00, 0xB8)
        val red = 0xFFFF0000.toInt()
        m.writeMem(0x6422, 0xF0); m.writeMem(0x6423, 0x00)          // sprite pen 1 = red
        for (i in 0 until 256) m.writeMem(0x4000 + i, 1)             // sprite 0: all pen 1
        m.writeMem(0x4000, 0)                                        // except one transparent pixel
        m.writeMem(0x6000, 0); m.writeMem(0x6001, 0)                 // X = 0
        m.writeMem(0x6002, 20); m.writeMem(0x6003, 0)                // Y = 20
        m.writeMem(0x6004, 0x05)                                     // ×1, ×1
        repeat(2) { emu.runFrame() }
        val (n1, left1, right1) = countColour(emu, red)
        assertEquals(255, n1)
        assertEquals(15, right1 - left1)
        m.writeMem(0x6000, 100)                                      // X = 100 pixels to the right
        repeat(2) { emu.runFrame() }
        val (_, left2, _) = countColour(emu, red)
        assertEquals(100, left2 - left1)
        m.writeMem(0x6004, 0x0D)                                     // ×4 horizontally, ×1 vertically
        repeat(2) { emu.runFrame() }
        val (n3, left3, right3) = countColour(emu, red)
        assertEquals(255 * 4, n3)
        assertEquals(63, right3 - left3)
        m.writeMem(0x6004, 0x00)                                     // hidden
        repeat(2) { emu.runFrame() }
        assertEquals(0, countColour(emu, red).first)
    }

    @Test
    fun `the raster interrupt replaces the 52 line interrupt`() {
        val emu = boot(im1, countAtA000)
        val m = emu.machine
        m.memory.write(0xA000, 0); m.memory.write(0xA001, 0)
        repeat(10) { emu.runFrame() }
        val classic = counter(m)
        assertTrue(classic in 55..65, "expected ~6 interrupts per frame, got $classic")
        m.unlock()
        m.out(0x7F00, 0xB8)
        m.writeMem(0x6800, 100)                                      // PRI on line 100
        emu.runFrame()
        val before = counter(m)
        repeat(10) { emu.runFrame() }
        val pri = counter(m) - before
        assertTrue(pri in 9..11, "expected one interrupt per frame, got $pri")
        m.writeMem(0x6800, 0)
        emu.runFrame()
        val again = counter(m)
        repeat(10) { emu.runFrame() }
        assertTrue(counter(m) - again in 55..65)
    }

    /** IM 2 table at &8000: vector v points at a handler counting in &A000 + v. */
    private fun installVectors(m: CpcMachine) {
        for (v in 0..7 step 2) {
            val handler = 0x9000 + v * 8
            m.memory.write(0x8000 + v, handler and 0xFF)
            m.memory.write(0x8001 + v, handler ushr 8)
            val code = intArrayOf(0x2A, v, 0xA0, 0x23, 0x22, v, 0xA0, 0xFB, 0xC9)
            code.forEachIndexed { i, b -> m.memory.write(handler + i, b) }
            m.memory.write(0xA000 + v, 0); m.memory.write(0xA001 + v, 0)
        }
        // Vectors with IVR bits set: &40 | source.
        for (v in 0..7 step 2) {
            val handler = 0x9000 + v * 8
            m.memory.write(0x8040 + v, handler and 0xFF)
            m.memory.write(0x8041 + v, handler ushr 8)
        }
    }

    @Test
    fun `IM 2 vectors carry the interrupt source`() {
        val emu = boot(im2)
        val m = emu.machine
        installVectors(m)
        m.unlock()
        m.out(0x7F00, 0xB8)
        m.writeMem(0x6805, 0x40)                                     // IVR: vector base &40, DMA interrupts auto-cleared
        m.writeMem(0x6800, 50)                                       // raster interrupt on line 50
        // DMA list at &8100: LOAD 7,&38 ; INT ; STOP
        val list = intArrayOf(0x0738, 0x4010, 0x4020)
        list.forEachIndexed { i, w -> m.memory.write(0x8100 + i * 2, w and 0xFF); m.memory.write(0x8101 + i * 2, w ushr 8) }
        m.writeMem(0x6C00, 0x00); m.writeMem(0x6C01, 0x81)          // channel 0 address
        m.writeMem(0x6C0F, 0x01)                                     // enable channel 0
        repeat(5) { emu.runFrame() }
        assertEquals(0x38, m.psg.regs[7])
        assertEquals(1, counter(m, 0xA004), "DMA channel 0 vector (4) once")
        assertTrue(counter(m, 0xA006) >= 4, "raster vector (6) every frame")
        assertEquals(0, counter(m, 0xA000))
        assertEquals(0, counter(m, 0xA002))
        assertEquals(0, m.asic!!.read(0x2C0F) and 0x07, "channel stopped")
        assertEquals(0, m.asic!!.read(0x2C0F) and 0x40, "DMA interrupt cleared by the acknowledge")
    }

    @Test
    fun `DMA lists execute one instruction per scan line with PAUSE REPEAT and LOOP`() {
        val emu = boot()
        val m = emu.machine
        m.unlock()
        m.out(0x7F00, 0xB8)
        // LOAD 8,1 ; PAUSE 2 ; REPEAT 3 ; LOAD 9,x ; LOOP ; STOP  where x counts through registers 9 writes
        val list = intArrayOf(0x0801, 0x1002, 0x2003, 0x0905, 0x4001, 0x4020)
        list.forEachIndexed { i, w -> m.memory.write(0x8200 + i * 2, w and 0xFF); m.memory.write(0x8201 + i * 2, w ushr 8) }
        m.writeMem(0x6C00, 0x00); m.writeMem(0x6C01, 0x82)
        m.writeMem(0x6C02, 0)                                        // prescaler 0
        val asic = m.asic!!
        var loads9 = 0
        var lines = 0
        m.writeMem(0x6C0F, 0x01)
        // Tick the DMA by hand: one HSYNC per call. The address register reads
        // &8208 right after the LOAD 9 instruction at &8206 was executed.
        while (asic.read(0x2C0F) and 1 != 0 && lines < 100) {
            lines++
            asic.dmaTick(m.memory::videoRead)
            if ((asic.read(0x2C00) or (asic.read(0x2C01) shl 8)) == 0x8208) loads9++
        }
        assertEquals(1, m.psg.regs[8])
        // LOAD(1) PAUSE(1)+2 idle, REPEAT(1), then 4 × (LOAD 9, LOOP) = 8, STOP(1): 14 lines.
        assertEquals(14, lines)
        assertEquals(4, loads9)
        assertEquals(5, m.psg.regs[9])
    }

    @Test
    fun `save states keep the ASIC`() {
        val emu = boot()
        val m = emu.machine
        m.unlock()
        m.out(0x7F00, 0xB8)
        m.writeMem(0x6400, 0x0F); m.writeMem(0x6401, 0x0F)
        m.writeMem(0x6000, 0x34); m.writeMem(0x6001, 0x12)
        m.writeMem(0x6004, 0x0A)
        m.writeMem(0x4010, 7)
        m.writeMem(0x6804, 0x83)
        val state = emu.saveState()
        emu.reset()
        assertTrue(m.asic!!.locked)
        emu.loadState(state)
        val asic = m.asic!!
        assertFalse(asic.locked)
        assertTrue(m.memory.asicPageMapped)
        assertEquals(0xFF00FFFF.toInt(), asic.paletteArgb[0])
        assertEquals(0x1234, asic.spriteX[0])
        assertEquals(2, asic.spriteMagX[0]); assertEquals(2, asic.spriteMagY[0])
        assertEquals(7, asic.spriteData[0x10].toInt())
        assertEquals(3, asic.hscroll); assertTrue(asic.extendBorder)
        assertEquals(0x0F, m.memory.read(0x6400))
    }

    companion object {
        val UNLOCK = intArrayOf(0xFF, 0x00, 0xFF, 0x77, 0xB3, 0x51, 0xA8, 0xD4, 0x62, 0x39, 0x9C, 0x46, 0x2B, 0x15, 0x8A, 0xCD, 0xEE)
    }
}

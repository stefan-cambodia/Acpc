package dev.stefan.acpc.core

import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.InvalidStateException
import dev.stefan.acpc.core.api.NullAudioSink
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.snapshot.SnaFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnaFormatTest {
    private fun header(version: Int, ramKb: Int, model: Int = 2): ByteArray {
        val h = ByteArray(0x100)
        "MV - SNA".toByteArray().copyInto(h)
        h[0x10] = version.toByte()
        h[0x11] = 0x44; h[0x12] = 0x12.toByte()                       // F, A
        h[0x13] = 0x34; h[0x14] = 0x12                                 // C, B
        h[0x23] = 0x00; h[0x24] = 0x40                                 // PC = 4000
        h[0x21] = 0xFE.toByte(); h[0x22] = 0xBF.toByte()               // SP = BFFE
        h[0x25] = 1                                                    // IM 1
        h[0x1B] = 1                                                    // IFF1
        h[0x2E] = 3                                                    // selected pen
        for (p in 0 until 17) h[0x2F + p] = (p + 1).toByte()           // pens 1..17
        h[0x40] = 0x8E.toByte()                                        // RMR: mode 2, both ROMs disabled (bits ignored above 0x1F)
        h[0x41] = 0xC0.toByte()                                        // RAM config 0
        h[0x43 + 1] = 32                                               // CRTC R1 = 32
        h[0x55] = 7                                                    // upper ROM 7
        h[0x5B + 7] = 0x3F                                             // PSG mixer
        h[0x6B] = (ramKb and 0xFF).toByte(); h[0x6C] = (ramKb shr 8).toByte()
        h[0x6D] = model.toByte()
        return h
    }

    @Test
    fun `header info reports version, model and size`() {
        val i = SnaFormat.info(header(2, 128) + ByteArray(128 * 1024))
        assertEquals(2, i.version); assertEquals(CpcModel.CPC6128, i.model); assertEquals(128, i.ramKb)
        val j = SnaFormat.info(header(2, 64, model = 0) + ByteArray(64 * 1024))
        assertEquals(CpcModel.CPC464, j.model)
        assertFalse(SnaFormat.isSna(ByteArray(300)))
    }

    @Test
    fun `loading restores registers, memory and hardware`() {
        val ram = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        val emu = CpcEmulator.createMachine(CpcModel.CPC6128, TestRoms.synthetic(), NullAudioSink())
        emu.loadSnapshot(header(2, 64) + ram)
        val m = emu.machine
        assertEquals(0x4000, m.cpu.pc); assertEquals(0xBFFE, m.cpu.sp); assertEquals(0x1244, m.cpu.af); assertEquals(0x1234, m.cpu.bc)
        assertEquals(1, m.cpu.im); assertTrue(m.cpu.iff1)
        assertEquals(0x34, m.memory.ram[0x1234].toInt() and 0xFF)
        assertEquals(2, m.gateArray.mode)
        assertEquals(4, m.gateArray.pens[3]); assertEquals(17, m.gateArray.pens[16])
        assertFalse(m.memory.lowerRomEnabled); assertFalse(m.memory.upperRomEnabled)
        assertEquals(7, m.memory.upperRomNumber)
        assertEquals(32, m.crtc.regs[1]); assertEquals(0x3F, m.psg.regs[7])
    }

    @Test
    fun `version 3 compressed memory chunks are expanded`() {
        val h = header(3, 0)
        // MEM0 chunk: run of 0x41 for 65535 bytes plus a literal 0xE5.
        val payload = ArrayList<Byte>()
        repeat(257) { payload += 0xE5.toByte(); payload += 0xFF.toByte(); payload += 0x41 }    // 257 * 255 = 65535
        payload += 0xE5.toByte(); payload += 0
        val chunk = "MEM0".toByteArray() + byteArrayOf(payload.size.toByte(), (payload.size shr 8).toByte(), (payload.size shr 16).toByte(), 0) + payload.toByteArray()
        val emu = CpcEmulator.createMachine(CpcModel.CPC6128, TestRoms.synthetic(), NullAudioSink())
        emu.loadSnapshot(h + chunk)
        assertEquals(0x41, emu.machine.memory.ram[100].toInt() and 0xFF)
        assertEquals(0xE5, emu.machine.memory.ram[65535].toInt() and 0xFF)
    }

    @Test
    fun `save then load round-trips registers, memory and hardware`() {
        val emu = CpcEmulator.createMachine(CpcModel.CPC6128, TestRoms.synthetic(), NullAudioSink())
        val m = emu.machine
        m.cpu.importState(intArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 1, 2, 3, 4, 5, 6, 7, 8, 0x1234, 0x5678, 0xC000, 0x8000, 0x3F, 0x7F, 0, 1, 1, 2, 0, 0, 0))
        m.memory.ram[0x9ABC] = 0x42
        m.memory.ram[0x10000 + 0x100] = 0x43
        m.gateArray.write(0x05); m.gateArray.write(0x40 or 0x1A)
        m.gateArray.write(0x80 or 0x0C or 2); m.gateArray.forceMode(2)
        m.memory.setRamConfig(0xC5)
        m.crtc.selectRegister(6); m.crtc.writeRegister(20)
        m.psg.selectRegister(8); m.psg.writeRegister(9)
        val sna = emu.saveSnapshot()
        val emu2 = CpcEmulator.createMachine(CpcModel.CPC6128, TestRoms.synthetic(), NullAudioSink())
        emu2.loadSnapshot(sna)
        val n = emu2.machine
        assertEquals(m.cpu.exportState().take(26), n.cpu.exportState().take(26))
        assertEquals(0x42, n.memory.ram[0x9ABC].toInt() and 0xFF)
        assertEquals(0x43, n.memory.ram[0x10000 + 0x100].toInt() and 0xFF)
        assertEquals(0x1A, n.gateArray.pens[5]); assertEquals(2, n.gateArray.mode)
        assertEquals(m.memory.ramConfig, n.memory.ramConfig)
        assertEquals(20, n.crtc.regs[6]); assertEquals(9, n.psg.regs[8])
    }

    @Test
    fun `a 128K snapshot is refused on a 64K machine`() {
        val emu = CpcEmulator.createMachine(CpcModel.CPC464, TestRoms.synthetic(), NullAudioSink())
        assertThrows(InvalidStateException::class.java) { emu.loadSnapshot(header(2, 128) + ByteArray(128 * 1024)) }
    }
}

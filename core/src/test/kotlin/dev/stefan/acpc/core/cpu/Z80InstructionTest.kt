package dev.stefan.acpc.core.cpu

import dev.stefan.acpc.core.cpu.z80.Z80
import dev.stefan.acpc.core.cpu.z80.Z80Flags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Z80InstructionTest {
    private val bus = TestBus()
    private val cpu = Z80(bus).apply { reset(); alignBusAccesses = false }

    private fun run(vararg code: Int, at: Int = 0x100): Int {
        bus.load(at, *code)
        cpu.pc = at
        return cpu.step()
    }

    @Test
    fun `LD r,n and LD r,r`() {
        run(0x3E, 0x42) // LD A,42h
        assertEquals(0x42, cpu.a)
        run(0x47)       // LD B,A
        assertEquals(0x42, cpu.b)
        assertEquals(0x101, cpu.pc)
    }

    @Test
    fun `ADD A,n sets flags`() {
        cpu.a = 0x7F
        val t = run(0xC6, 0x01) // ADD A,1 -> 0x80, overflow, half carry, sign
        assertEquals(0x80, cpu.a)
        assertTrue(cpu.f and Z80Flags.S != 0)
        assertTrue(cpu.f and Z80Flags.PV != 0)
        assertTrue(cpu.f and Z80Flags.H != 0)
        assertFalse(cpu.f and Z80Flags.C != 0)
        assertFalse(cpu.f and Z80Flags.N != 0)
        assertEquals(7, t)

        cpu.a = 0xFF
        run(0xC6, 0x01)
        assertEquals(0, cpu.a)
        assertTrue(cpu.f and Z80Flags.Z != 0)
        assertTrue(cpu.f and Z80Flags.C != 0)
    }

    @Test
    fun `SUB and CP flags`() {
        cpu.a = 0x00
        run(0xD6, 0x01) // SUB 1
        assertEquals(0xFF, cpu.a)
        assertTrue(cpu.f and Z80Flags.N != 0)
        assertTrue(cpu.f and Z80Flags.C != 0)
        assertTrue(cpu.f and Z80Flags.H != 0)
        cpu.a = 0x10
        run(0xFE, 0x10) // CP 10h
        assertEquals(0x10, cpu.a)
        assertTrue(cpu.f and Z80Flags.Z != 0)
    }

    @Test
    fun `16-bit loads, stack and jumps`() {
        run(0x21, 0x34, 0x12) // LD HL,1234h
        assertEquals(0x1234, cpu.hl)
        cpu.sp = 0x8000
        run(0xE5)             // PUSH HL
        assertEquals(0x7FFE, cpu.sp)
        assertEquals(0x34, bus.ram[0x7FFE])
        assertEquals(0x12, bus.ram[0x7FFF])
        run(0xD1)             // POP DE
        assertEquals(0x1234, cpu.de)
        assertEquals(0x8000, cpu.sp)
        run(0xC3, 0x00, 0x20) // JP 2000h
        assertEquals(0x2000, cpu.pc)
        run(0xCD, 0x00, 0x30, at = 0x2000) // CALL 3000h
        assertEquals(0x3000, cpu.pc)
        assertEquals(0x03, bus.ram[0x7FFE])
        assertEquals(0x20, bus.ram[0x7FFF])
        run(0xC9, at = 0x3000) // RET
        assertEquals(0x2003, cpu.pc)
    }

    @Test
    fun `DJNZ loops`() {
        cpu.b = 3
        bus.load(0x100, 0x10, 0xFE) // DJNZ -2
        cpu.pc = 0x100
        assertEquals(13, cpu.step()); assertEquals(0x100, cpu.pc); assertEquals(2, cpu.b)
        assertEquals(13, cpu.step()); assertEquals(0x100, cpu.pc)
        assertEquals(8, cpu.step()); assertEquals(0x102, cpu.pc); assertEquals(0, cpu.b)
    }

    @Test
    fun `IX indexed addressing and undocumented halves`() {
        cpu.ix = 0x4000
        bus.ram[0x4005] = 0x99
        run(0xDD, 0x7E, 0x05) // LD A,(IX+5)
        assertEquals(0x99, cpu.a)
        assertEquals(0x4005, cpu.memptr)
        run(0xDD, 0x26, 0xAB) // LD IXH,ABh
        assertEquals(0xAB00 or 0x00, cpu.ix and 0xFF00)
        run(0xDD, 0x2E, 0xCD) // LD IXL,CDh
        assertEquals(0xABCD, cpu.ix)
        cpu.h = 0x11
        run(0xDD, 0x66, 0x00) // LD H,(IX+0) -> real H, not IXH
        assertEquals(bus.ram[0xABCD], cpu.h)
        assertEquals(0xABCD, cpu.ix)
        run(0xDD, 0x36, 0x02, 0x77) // LD (IX+2),77h
        assertEquals(0x77, bus.ram[0xABCF])
    }

    @Test
    fun `CB bit operations`() {
        cpu.a = 0x01
        run(0xCB, 0x07) // RLC A
        assertEquals(0x02, cpu.a)
        run(0xCB, 0x47) // BIT 0,A -> zero
        assertTrue(cpu.f and Z80Flags.Z != 0)
        run(0xCB, 0xC7) // SET 0,A
        assertEquals(0x03, cpu.a)
        run(0xCB, 0x87) // RES 0,A
        assertEquals(0x02, cpu.a)
        cpu.hl = 0x5000
        bus.ram[0x5000] = 0x80
        run(0xCB, 0x7E) // BIT 7,(HL)
        assertTrue(cpu.f and Z80Flags.S != 0)
        assertFalse(cpu.f and Z80Flags.Z != 0)
        // DD CB d op with register copy (undocumented): RLC (IX+1),B
        cpu.ix = 0x5000
        bus.ram[0x5001] = 0x81
        run(0xDD, 0xCB, 0x01, 0x00)
        assertEquals(0x03, bus.ram[0x5001])
        assertEquals(0x03, cpu.b)
        assertTrue(cpu.f and Z80Flags.C != 0)
    }

    @Test
    fun `LDIR copies block`() {
        cpu.hl = 0x1000; cpu.de = 0x2000; cpu.bc = 4
        for (i in 0 until 4) bus.ram[0x1000 + i] = 0x10 + i
        bus.load(0x100, 0xED, 0xB0)
        cpu.pc = 0x100
        var total = 0
        repeat(4) { total += cpu.step() }
        assertEquals(21 * 3 + 16, total)
        assertEquals(0x102, cpu.pc)
        assertEquals(0, cpu.bc)
        for (i in 0 until 4) assertEquals(0x10 + i, bus.ram[0x2000 + i])
        assertFalse(cpu.f and Z80Flags.PV != 0)
    }

    @Test
    fun `IO instructions use full 16-bit port`() {
        cpu.bc = 0x7F8C
        cpu.a = 0x55
        run(0xED, 0x79) // OUT (C),A
        assertEquals(0x7F8C to 0x55, bus.ioWrites.last())
        bus.ioReadValue = 0x80
        run(0xED, 0x40) // IN B,(C)
        assertEquals(0x80, cpu.b)
        assertTrue(cpu.f and Z80Flags.S != 0)
        cpu.a = 0x12
        run(0xD3, 0x34) // OUT (34h),A -> port 1234h
        assertEquals(0x1234 to 0x12, bus.ioWrites.last())
    }

    @Test
    fun `DAA after BCD addition`() {
        cpu.a = 0x19
        run(0xC6, 0x01) // 0x1A
        run(0x27)       // DAA -> 0x20
        assertEquals(0x20, cpu.a)
        cpu.a = 0x99
        run(0xC6, 0x01)
        run(0x27)
        assertEquals(0x00, cpu.a)
        assertTrue(cpu.f and Z80Flags.C != 0)
        assertTrue(cpu.f and Z80Flags.Z != 0)
    }

    @Test
    fun `interrupt mode 1 is serviced after EI plus one instruction`() {
        cpu.sp = 0x8000
        bus.load(0x100, 0xFB, 0x00, 0x00) // EI ; NOP ; NOP
        bus.load(0x38, 0xC9)              // RET at the interrupt vector
        cpu.pc = 0x100
        cpu.im = 1
        cpu.intLine = true
        cpu.step() // EI: interrupts not accepted yet
        assertEquals(0x101, cpu.pc)
        assertTrue(cpu.iff1)
        cpu.step() // NOP, then the interrupt is accepted
        assertEquals(0x38, cpu.pc)
        assertFalse(cpu.iff1)
        assertEquals(0x02, bus.ram[0x7FFE])
        assertEquals(0x01, bus.ram[0x7FFF])
    }

    @Test
    fun `HALT resumes on interrupt`() {
        cpu.sp = 0x8000
        bus.load(0x100, 0x76, 0x00) // HALT ; NOP
        cpu.pc = 0x100
        cpu.im = 1
        cpu.iff1 = true
        cpu.step()
        assertTrue(cpu.halted)
        cpu.step()
        assertTrue(cpu.halted)
        assertEquals(0x101, cpu.pc)
        cpu.intLine = true
        cpu.step()
        assertFalse(cpu.halted)
        assertEquals(0x38, cpu.pc)
        assertEquals(0x01, bus.ram[0x7FFE]) // pushed PC = 0x101 (after HALT)
    }

    @Test
    fun `R register counts M1 cycles`() {
        cpu.r = 0
        run(0x00)
        assertEquals(1, cpu.r)
        run(0xDD, 0x00) // two M1 cycles
        assertEquals(3, cpu.r)
        run(0xED, 0x4F) // LD R,A
        cpu.a = 0x80
        run(0xED, 0x4F)
        run(0x00)
        assertEquals(0x81, cpu.rFull)
    }
}

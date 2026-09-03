package dev.stefan.acpc.core.cpu

import dev.stefan.acpc.core.cpu.z80.Z80
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that the Gate Array wait-state model (bus accesses aligned on 4T
 * boundaries) reproduces the documented Amstrad CPC instruction timings,
 * expressed in NOPs (1 NOP = 4 T-states = 1 µs).
 */
class Z80CpcTimingTest {
    private val bus = TestBus()
    private val cpu = Z80(bus).apply { reset(); alignBusAccesses = true; sp = 0x8000 }

    private fun nops(vararg code: Int, setup: (Z80) -> Unit = {}): Int {
        bus.load(0x100, *code)
        cpu.pc = 0x100
        cpu.cycles = 0
        setup(cpu)
        val t = cpu.step()
        // An instruction may end with internal cycles; the next opcode fetch is
        // what re-aligns the CPU on the 1 µs grid, so the cost is ceil(t / 4).
        return (t + 3) / 4
    }

    @Test
    fun `documented CPC NOP timings`() {
        assertEquals(1, nops(0x00))                         // NOP
        assertEquals(1, nops(0x78))                         // LD A,B
        assertEquals(2, nops(0x3E, 0x00))                   // LD A,n
        assertEquals(2, nops(0x7E))                         // LD A,(HL)
        assertEquals(3, nops(0x36, 0x00))                   // LD (HL),n
        assertEquals(4, nops(0x3A, 0x00, 0x00))             // LD A,(nn)
        assertEquals(4, nops(0x32, 0x00, 0x00))             // LD (nn),A
        assertEquals(3, nops(0x21, 0x00, 0x00))             // LD HL,nn
        assertEquals(5, nops(0x2A, 0x00, 0x00))             // LD HL,(nn)
        assertEquals(5, nops(0x22, 0x00, 0x00))             // LD (nn),HL
        assertEquals(6, nops(0xED, 0x4B, 0x00, 0x00))       // LD BC,(nn)
        assertEquals(2, nops(0xF9))                         // LD SP,HL
        assertEquals(4, nops(0xE5))                         // PUSH HL
        assertEquals(3, nops(0xE1))                         // POP HL
        assertEquals(6, nops(0xE3))                         // EX (SP),HL
        assertEquals(1, nops(0xEB))                         // EX DE,HL
        assertEquals(1, nops(0x3C))                         // INC A
        assertEquals(3, nops(0x34))                         // INC (HL)
        assertEquals(2, nops(0x23))                         // INC HL
        assertEquals(3, nops(0x09))                         // ADD HL,BC
        assertEquals(4, nops(0xED, 0x4A))                   // ADC HL,BC
        assertEquals(1, nops(0x80))                         // ADD A,B
        assertEquals(2, nops(0x86))                         // ADD A,(HL)
        assertEquals(2, nops(0xC6, 0x00))                   // ADD A,n
        assertEquals(3, nops(0xC3, 0x00, 0x01))             // JP nn
        assertEquals(3, nops(0xC2, 0x00, 0x01))             // JP NZ,nn (either way)
        assertEquals(1, nops(0xE9))                         // JP (HL)
        assertEquals(3, nops(0x18, 0x00))                   // JR e
        assertEquals(2, nops(0x20, 0x00) { it.f = 0x40 })   // JR NZ not taken
        assertEquals(3, nops(0x20, 0x00) { it.f = 0x00 })   // JR NZ taken
        assertEquals(4, nops(0x10, 0x00) { it.b = 2 })      // DJNZ taken
        assertEquals(3, nops(0x10, 0x00) { it.b = 1 })      // DJNZ not taken
        assertEquals(5, nops(0xCD, 0x00, 0x01))             // CALL nn
        assertEquals(3, nops(0xC4, 0x00, 0x01) { it.f = 0x40 }) // CALL NZ not taken
        assertEquals(5, nops(0xC4, 0x00, 0x01) { it.f = 0x00 }) // CALL NZ taken
        assertEquals(3, nops(0xC9))                         // RET
        assertEquals(2, nops(0xC0) { it.f = 0x40 })         // RET NZ not taken
        assertEquals(4, nops(0xC0) { it.f = 0x00 })         // RET NZ taken
        assertEquals(4, nops(0xC7))                         // RST 0
        assertEquals(2, nops(0xCB, 0x07))                   // RLC A
        assertEquals(4, nops(0xCB, 0x06))                   // RLC (HL)
        assertEquals(2, nops(0xCB, 0x47))                   // BIT 0,A
        assertEquals(3, nops(0xCB, 0x46))                   // BIT 0,(HL)
        assertEquals(4, nops(0xCB, 0xC6))                   // SET 0,(HL)
        assertEquals(5, nops(0xDD, 0x7E, 0x00))             // LD A,(IX+d)
        assertEquals(5, nops(0xDD, 0x77, 0x00))             // LD (IX+d),A
        assertEquals(6, nops(0xDD, 0x36, 0x00, 0x00))       // LD (IX+d),n
        assertEquals(6, nops(0xDD, 0x34, 0x00))             // INC (IX+d)
        assertEquals(5, nops(0xDD, 0x86, 0x00))             // ADD A,(IX+d)
        assertEquals(4, nops(0xDD, 0x21, 0x00, 0x00))       // LD IX,nn
        assertEquals(4, nops(0xDD, 0x09))                   // ADD IX,BC
        assertEquals(5, nops(0xDD, 0xE5))                   // PUSH IX
        assertEquals(4, nops(0xDD, 0xE1))                   // POP IX
        assertEquals(2, nops(0xDD, 0xE9))                   // JP (IX)
        assertEquals(6, nops(0xDD, 0xCB, 0x00, 0x46))       // BIT 0,(IX+d)
        assertEquals(7, nops(0xDD, 0xCB, 0x00, 0xC6))       // SET 0,(IX+d)
        assertEquals(3, nops(0xDB, 0x00))                   // IN A,(n)
        assertEquals(3, nops(0xD3, 0x00))                   // OUT (n),A
        assertEquals(4, nops(0xED, 0x78))                   // IN A,(C)
        assertEquals(4, nops(0xED, 0x79))                   // OUT (C),A
        assertEquals(5, nops(0xED, 0xA0))                   // LDI
        assertEquals(6, nops(0xED, 0xB0) { it.bc = 2 })     // LDIR (repeating)
        assertEquals(5, nops(0xED, 0xB0) { it.bc = 1 })     // LDIR (last)
        // cpctech measured CPI as 5 µs; the M-cycle structure (4+4+3+5 T) gives 4.
        assertEquals(4, nops(0xED, 0xA1))                   // CPI
        assertEquals(6, nops(0xED, 0xB1) { it.bc = 2; it.a = 1 }) // CPIR repeating
        assertEquals(5, nops(0xED, 0xA3))                   // OUTI
        assertEquals(6, nops(0xED, 0xB3) { it.b = 2 })      // OTIR repeating
        assertEquals(5, nops(0xED, 0xA2))                   // INI
        assertEquals(6, nops(0xED, 0xB2) { it.b = 2 })      // INIR repeating
        assertEquals(7, nops(0xDD, 0xE3))                   // EX (SP),IX
        assertEquals(3, nops(0xDD, 0x23))                   // INC IX
        assertEquals(6, nops(0xDD, 0x22, 0x00, 0x00))       // LD (nn),IX
        assertEquals(6, nops(0xED, 0x43, 0x00, 0x00))       // LD (nn),BC
        assertEquals(2, nops(0xED, 0x00))                   // ED "NOP"
        assertEquals(5, nops(0xED, 0x6F))                   // RLD
        assertEquals(2, nops(0xED, 0x44))                   // NEG
        assertEquals(4, nops(0xED, 0x4D))                   // RETI
        assertEquals(3, nops(0xED, 0x57))                   // LD A,I
        assertEquals(2, nops(0xED, 0x56))                   // IM 1
        assertEquals(1, nops(0xFB))                         // EI
        assertEquals(1, nops(0x27))                         // DAA
        assertEquals(1, nops(0x76))                         // HALT (first NOP)
    }

    @Test
    fun `interrupt acknowledge takes the documented time`() {
        bus.load(0x100, 0x00)
        cpu.pc = 0x100
        cpu.iff1 = true
        cpu.im = 1
        cpu.intLine = true
        cpu.cycles = 0
        val t = cpu.step()
        // NOP (4T) + acknowledge (7T) + two pushes aligned on 4T boundaries: the
        // interrupt itself costs 4 NOPs (16 T-states) before the next fetch.
        assertEquals(19, t)
        assertEquals(5, (t + 3) / 4)
        assertEquals(0x38, cpu.pc)
    }
}

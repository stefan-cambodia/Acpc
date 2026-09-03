package dev.stefan.acpc.core.cpu.z80

import dev.stefan.acpc.core.cpu.z80.Z80Flags.C
import dev.stefan.acpc.core.cpu.z80.Z80Flags.H
import dev.stefan.acpc.core.cpu.z80.Z80Flags.N
import dev.stefan.acpc.core.cpu.z80.Z80Flags.PARITY
import dev.stefan.acpc.core.cpu.z80.Z80Flags.PV
import dev.stefan.acpc.core.cpu.z80.Z80Flags.S
import dev.stefan.acpc.core.cpu.z80.Z80Flags.SZ53
import dev.stefan.acpc.core.cpu.z80.Z80Flags.SZ53P
import dev.stefan.acpc.core.cpu.z80.Z80Flags.XY
import dev.stefan.acpc.core.cpu.z80.Z80Flags.Z

/**
 * Zilog Z80 CPU emulator.
 *
 * Complete instruction set including the undocumented opcodes (IX/IY halves,
 * SLL, DDCB/FDCB register-copy variants), the undocumented X/Y flag results
 * and the internal MEMPTR (WZ) register that leaks into the BIT n,(HL) flags.
 *
 * ## Timing
 * The emulator counts T-states with the exact M-cycle structure of the real
 * CPU (4T opcode fetch, 3T memory read/write, 4T I/O, plus internal cycles).
 * When [alignBusAccesses] is set, every bus transaction is delayed so that it
 * starts on a 4T boundary. This reproduces the wait states inserted by the
 * Amstrad CPC Gate Array and yields, without any per-instruction table, the
 * well known CPC instruction timings in "NOPs" (e.g. LD A,(nn) = 4 µs,
 * CALL = 5 µs, LDIR = 6 µs per iteration, DJNZ = 3/4 µs).
 *
 * ## Interrupts
 * [intLine] models the level-sensitive /INT pin: the peripheral keeps it
 * asserted until the CPU acknowledges (see [Z80Bus.interruptAcknowledge]) or
 * the device withdraws it. NMI is supported for completeness (unused on CPC).
 */
class Z80(private val bus: Z80Bus) {

    // ---- Registers ---------------------------------------------------------

    @JvmField var a = 0
    @JvmField var f = 0
    @JvmField var b = 0
    @JvmField var c = 0
    @JvmField var d = 0
    @JvmField var e = 0
    @JvmField var h = 0
    @JvmField var l = 0

    @JvmField var a2 = 0
    @JvmField var f2 = 0
    @JvmField var b2 = 0
    @JvmField var c2 = 0
    @JvmField var d2 = 0
    @JvmField var e2 = 0
    @JvmField var h2 = 0
    @JvmField var l2 = 0

    @JvmField var ix = 0
    @JvmField var iy = 0
    @JvmField var sp = 0
    @JvmField var pc = 0
    @JvmField var i = 0

    /** Refresh register: low 7 bits count M1 cycles, bit 7 is only set by LD R,A. */
    @JvmField var r = 0

    /** Internal 16-bit address register (WZ). */
    @JvmField var memptr = 0

    @JvmField var iff1 = false
    @JvmField var iff2 = false
    @JvmField var im = 0
    @JvmField var halted = false

    /** T-states elapsed since reset. */
    @JvmField var cycles = 0L

    /** State of the /INT line (true = asserted). Owned by the peripheral. */
    @Volatile @JvmField var intLine = false

    /** Pending NMI (edge triggered). */
    @Volatile @JvmField var nmiPending = false

    /** True when the emulated system stretches bus accesses to 4T boundaries (Amstrad CPC). */
    @JvmField var alignBusAccesses = true

    /** Interrupts are enabled one instruction after EI. */
    private var eiPending = false

    /** Current index prefix: 0 = none, 0xDD = IX, 0xFD = IY. */
    private var prefix = 0

    var af: Int
        get() = (a shl 8) or f
        set(v) { a = (v ushr 8) and 0xFF; f = v and 0xFF }
    var bc: Int
        get() = (b shl 8) or c
        set(v) { b = (v ushr 8) and 0xFF; c = v and 0xFF }
    var de: Int
        get() = (d shl 8) or e
        set(v) { d = (v ushr 8) and 0xFF; e = v and 0xFF }
    var hl: Int
        get() = (h shl 8) or l
        set(v) { h = (v ushr 8) and 0xFF; l = v and 0xFF }
    var af2: Int
        get() = (a2 shl 8) or f2
        set(v) { a2 = (v ushr 8) and 0xFF; f2 = v and 0xFF }
    var bc2: Int
        get() = (b2 shl 8) or c2
        set(v) { b2 = (v ushr 8) and 0xFF; c2 = v and 0xFF }
    var de2: Int
        get() = (d2 shl 8) or e2
        set(v) { d2 = (v ushr 8) and 0xFF; e2 = v and 0xFF }
    var hl2: Int
        get() = (h2 shl 8) or l2
        set(v) { h2 = (v ushr 8) and 0xFF; l2 = v and 0xFF }

    /** Full R register value as read by LD A,R. */
    val rFull: Int get() = r and 0xFF

    fun reset() {
        a = 0xFF; f = 0xFF; b = 0; c = 0; d = 0; e = 0; h = 0; l = 0
        a2 = 0; f2 = 0; b2 = 0; c2 = 0; d2 = 0; e2 = 0; h2 = 0; l2 = 0
        ix = 0; iy = 0; sp = 0xFFFF; pc = 0; i = 0; r = 0; memptr = 0
        iff1 = false; iff2 = false; im = 0; halted = false
        eiPending = false; prefix = 0
        intLine = false; nmiPending = false
        cycles = 0
    }

    // ---- Bus helpers with CPC wait-state alignment -------------------------

    /**
     * Memory cycles sample /WAIT at the end of T2: the Gate Array only leaves
     * one T-state in four free, so a memory cycle can only start on a 4T
     * boundary (phase 0).
     */
    private fun align() {
        if (alignBusAccesses) cycles = (cycles + 3) and 3L.inv()
    }

    /**
     * I/O cycles have an automatic wait state and sample /WAIT at the end of
     * TW (their third T-state), so they start one T-state earlier in the grid
     * (phase 3). This is what makes OUT (C),r cost 4 µs but OUT (n),A only 3.
     */
    private fun alignIo() {
        if (alignBusAccesses) cycles = ((cycles + 4) and 3L.inv()) - 1
    }

    /** M1 cycle: opcode fetch (4T) with refresh. */
    private fun fetchOpcode(): Int {
        align()
        val v = bus.readMem(pc)
        pc = (pc + 1) and 0xFFFF
        r = (r and 0x80) or ((r + 1) and 0x7F)
        cycles += 4
        return v
    }

    /** Fetches an immediate operand byte (3T). */
    private fun fetch8(): Int {
        align()
        val v = bus.readMem(pc)
        pc = (pc + 1) and 0xFFFF
        cycles += 3
        return v
    }

    private fun fetch16(): Int {
        val lo = fetch8()
        return lo or (fetch8() shl 8)
    }

    private fun read8(address: Int): Int {
        align()
        val v = bus.readMem(address and 0xFFFF)
        cycles += 3
        return v
    }

    private fun write8(address: Int, value: Int) {
        align()
        bus.writeMem(address and 0xFFFF, value and 0xFF)
        cycles += 3
    }

    private fun read16(address: Int): Int {
        val lo = read8(address)
        return lo or (read8(address + 1) shl 8)
    }

    private fun write16(address: Int, value: Int) {
        write8(address, value and 0xFF)
        write8(address + 1, value ushr 8)
    }

    private fun ioRead(port: Int): Int {
        alignIo()
        cycles += 4
        return bus.readIo(port and 0xFFFF) and 0xFF
    }

    private fun ioWrite(port: Int, value: Int) {
        alignIo()
        cycles += 4
        bus.writeIo(port and 0xFFFF, value and 0xFF)
    }

    private fun internal(t: Int) {
        cycles += t
    }

    private fun push(value: Int) {
        sp = (sp - 1) and 0xFFFF
        write8(sp, value ushr 8)
        sp = (sp - 1) and 0xFFFF
        write8(sp, value and 0xFF)
    }

    private fun pop(): Int {
        val lo = read8(sp)
        sp = (sp + 1) and 0xFFFF
        val hi = read8(sp)
        sp = (sp + 1) and 0xFFFF
        return lo or (hi shl 8)
    }

    // ---- Interrupts --------------------------------------------------------

    /**
     * Executes one instruction (including any prefix bytes) and then services
     * a pending interrupt if interrupts are enabled. Returns the number of
     * T-states consumed.
     */
    fun step(): Int {
        val start = cycles
        if (nmiPending) {
            nmiPending = false
            serviceNmi()
            return (cycles - start).toInt()
        }
        // EI enables IFF1/IFF2 immediately but the CPU does not accept an
        // interrupt at the end of the EI instruction itself: EI sets
        // eiPending, which blocks the check below until the next instruction.
        eiPending = false
        if (halted) {
            // HALT executes NOPs until an interrupt arrives.
            align()
            cycles += 4
            r = (r and 0x80) or ((r + 1) and 0x7F)
        } else {
            execute(fetchOpcode())
        }
        if (intLine && iff1 && !eiPending) serviceInterrupt()
        return (cycles - start).toInt()
    }

    private fun serviceNmi() {
        halted = false
        iff2 = iff1
        iff1 = false
        // NMI acknowledge M1 is 5T, then the PC is pushed.
        align()
        cycles += 5
        r = (r and 0x80) or ((r + 1) and 0x7F)
        push(pc)
        pc = 0x66
        memptr = pc
    }

    private fun serviceInterrupt() {
        halted = false
        iff1 = false
        iff2 = false
        // Interrupt acknowledge cycle: 7T (M1 with two wait states).
        align()
        val vector = bus.interruptAcknowledge() and 0xFF
        cycles += 7
        r = (r and 0x80) or ((r + 1) and 0x7F)
        when (im) {
            2 -> {
                push(pc)
                val address = ((i shl 8) or vector) and 0xFFFF
                pc = read16(address)
                memptr = pc
            }
            else -> {
                // IM 0 with 0xFF on the bus executes RST 38h, same as IM 1.
                push(pc)
                pc = 0x38
                memptr = pc
            }
        }
    }

    // ---- Register access by index -----------------------------------------

    /** 8-bit register read for indices 0-7 (B C D E H L (HL) A), honouring the index prefix. */
    private fun getReg(idx: Int): Int = when (idx) {
        0 -> b
        1 -> c
        2 -> d
        3 -> e
        4 -> if (prefix == 0) h else if (prefix == 0xDD) ix ushr 8 else iy ushr 8
        5 -> if (prefix == 0) l else if (prefix == 0xDD) ix and 0xFF else iy and 0xFF
        6 -> read8(indexedAddress())
        else -> a
    }

    private fun setReg(idx: Int, v: Int) {
        val value = v and 0xFF
        when (idx) {
            0 -> b = value
            1 -> c = value
            2 -> d = value
            3 -> e = value
            4 -> when (prefix) {
                0 -> h = value
                0xDD -> ix = (value shl 8) or (ix and 0xFF)
                else -> iy = (value shl 8) or (iy and 0xFF)
            }
            5 -> when (prefix) {
                0 -> l = value
                0xDD -> ix = (ix and 0xFF00) or value
                else -> iy = (iy and 0xFF00) or value
            }
            6 -> write8(indexedAddress(), value)
            else -> a = value
        }
    }

    /** Effective address of (HL) or (IX+d)/(IY+d). For the indexed form, fetches d and adds 5 internal T-states. */
    private fun indexedAddress(): Int {
        if (prefix == 0) return hl
        val dd = fetch8().toByte().toInt()
        internal(5)
        val base = if (prefix == 0xDD) ix else iy
        memptr = (base + dd) and 0xFFFF
        return memptr
    }

    /** Effective address of (IX+d) when d has already been fetched. */
    private fun indexedAddressWith(dd: Int): Int {
        val base = if (prefix == 0xDD) ix else iy
        memptr = (base + dd) and 0xFFFF
        return memptr
    }

    private fun getHLPair(): Int = when (prefix) {
        0 -> hl
        0xDD -> ix
        else -> iy
    }

    private fun setHLPair(v: Int) {
        when (prefix) {
            0 -> hl = v and 0xFFFF
            0xDD -> ix = v and 0xFFFF
            else -> iy = v and 0xFFFF
        }
    }

    /** 16-bit register pair for indices 0-3 (BC DE HL SP). */
    private fun getPair(idx: Int): Int = when (idx) {
        0 -> bc
        1 -> de
        2 -> getHLPair()
        else -> sp
    }

    private fun setPair(idx: Int, v: Int) {
        when (idx) {
            0 -> bc = v and 0xFFFF
            1 -> de = v and 0xFFFF
            2 -> setHLPair(v)
            else -> sp = v and 0xFFFF
        }
    }

    /** 16-bit register pair for PUSH/POP indices 0-3 (BC DE HL AF). */
    private fun getPairAf(idx: Int): Int = when (idx) {
        0 -> bc
        1 -> de
        2 -> getHLPair()
        else -> af
    }

    private fun setPairAf(idx: Int, v: Int) {
        when (idx) {
            0 -> bc = v and 0xFFFF
            1 -> de = v and 0xFFFF
            2 -> setHLPair(v)
            else -> af = v and 0xFFFF
        }
    }

    private fun testCondition(cc: Int): Boolean = when (cc) {
        0 -> f and Z == 0
        1 -> f and Z != 0
        2 -> f and C == 0
        3 -> f and C != 0
        4 -> f and PV == 0
        5 -> f and PV != 0
        6 -> f and S == 0
        else -> f and S != 0
    }

    // ---- ALU ---------------------------------------------------------------

    private fun add8(v: Int) {
        val res = a + v
        f = SZ53[res and 0xFF] or ((a xor v xor res) and H) or (((a xor res) and (v xor res) and 0x80) ushr 5) or ((res ushr 8) and C)
        a = res and 0xFF
    }

    private fun adc8(v: Int) {
        val res = a + v + (f and C)
        f = SZ53[res and 0xFF] or ((a xor v xor res) and H) or (((a xor res) and (v xor res) and 0x80) ushr 5) or ((res ushr 8) and C)
        a = res and 0xFF
    }

    private fun sub8(v: Int) {
        val res = a - v
        f = SZ53[res and 0xFF] or N or ((a xor v xor res) and H) or (((a xor v) and (a xor res) and 0x80) ushr 5) or ((res ushr 8) and C)
        a = res and 0xFF
    }

    private fun sbc8(v: Int) {
        val res = a - v - (f and C)
        f = SZ53[res and 0xFF] or N or ((a xor v xor res) and H) or (((a xor v) and (a xor res) and 0x80) ushr 5) or ((res ushr 8) and C)
        a = res and 0xFF
    }

    private fun and8(v: Int) {
        a = a and v
        f = SZ53P[a] or H
    }

    private fun xor8(v: Int) {
        a = (a xor v) and 0xFF
        f = SZ53P[a]
    }

    private fun or8(v: Int) {
        a = a or v
        f = SZ53P[a]
    }

    private fun cp8(v: Int) {
        val res = a - v
        // X and Y come from the operand, not the result.
        f = (SZ53[res and 0xFF] and XY.inv()) or (v and XY) or N or ((a xor v xor res) and H) or (((a xor v) and (a xor res) and 0x80) ushr 5) or ((res ushr 8) and C)
    }

    private fun alu(op: Int, v: Int) {
        when (op) {
            0 -> add8(v)
            1 -> adc8(v)
            2 -> sub8(v)
            3 -> sbc8(v)
            4 -> and8(v)
            5 -> xor8(v)
            6 -> or8(v)
            else -> cp8(v)
        }
    }

    private fun inc8(v: Int): Int {
        val res = (v + 1) and 0xFF
        f = (f and C) or SZ53[res] or (if (res == 0x80) PV else 0) or (if (res and 0x0F == 0) H else 0)
        return res
    }

    private fun dec8(v: Int): Int {
        val res = (v - 1) and 0xFF
        f = (f and C) or N or SZ53[res] or (if (res == 0x7F) PV else 0) or (if (res and 0x0F == 0x0F) H else 0)
        return res
    }

    private fun add16(x: Int, y: Int): Int {
        memptr = (x + 1) and 0xFFFF
        val res = x + y
        f = (f and (S or Z or PV)) or ((res ushr 8) and XY) or ((res ushr 16) and C) or (((x xor y xor res) ushr 8) and H)
        return res and 0xFFFF
    }

    private fun adc16(y: Int) {
        val x = hl
        memptr = (x + 1) and 0xFFFF
        val res = x + y + (f and C)
        val r16 = res and 0xFFFF
        f = ((res ushr 8) and (S or XY)) or (if (r16 == 0) Z else 0) or ((res ushr 16) and C) or
            (((x xor y xor res) ushr 8) and H) or ((((x xor res) and (y xor res)) ushr 13) and PV)
        hl = r16
    }

    private fun sbc16(y: Int) {
        val x = hl
        memptr = (x + 1) and 0xFFFF
        val res = x - y - (f and C)
        val r16 = res and 0xFFFF
        f = N or ((res ushr 8) and (S or XY)) or (if (r16 == 0) Z else 0) or ((res ushr 16) and C) or
            (((x xor y xor res) ushr 8) and H) or ((((x xor y) and (x xor res)) ushr 13) and PV)
        hl = r16
    }

    private fun daa() {
        var add = 0
        var carry = f and C
        if (f and H != 0 || (a and 0x0F) > 9) add = 0x06
        if (carry != 0 || a > 0x99) {
            add = add or 0x60
            carry = C
        }
        val res: Int
        if (f and N != 0) {
            res = (a - add) and 0xFF
            f = N or SZ53P[res] or carry or (if ((a xor res) and H != 0) H else 0)
        } else {
            res = (a + add) and 0xFF
            f = SZ53P[res] or carry or (if ((a xor res) and H != 0) H else 0)
        }
        a = res
    }

    // ---- Rotates / shifts (CB) --------------------------------------------

    private fun rlc(v: Int): Int {
        val res = ((v shl 1) or (v ushr 7)) and 0xFF
        f = SZ53P[res] or (v ushr 7)
        return res
    }

    private fun rrc(v: Int): Int {
        val res = ((v ushr 1) or (v shl 7)) and 0xFF
        f = SZ53P[res] or (v and C)
        return res
    }

    private fun rl(v: Int): Int {
        val res = ((v shl 1) or (f and C)) and 0xFF
        f = SZ53P[res] or (v ushr 7)
        return res
    }

    private fun rr(v: Int): Int {
        val res = ((v ushr 1) or ((f and C) shl 7)) and 0xFF
        f = SZ53P[res] or (v and C)
        return res
    }

    private fun sla(v: Int): Int {
        val res = (v shl 1) and 0xFF
        f = SZ53P[res] or (v ushr 7)
        return res
    }

    private fun sra(v: Int): Int {
        val res = (v ushr 1) or (v and 0x80)
        f = SZ53P[res] or (v and C)
        return res
    }

    private fun sll(v: Int): Int {
        val res = ((v shl 1) or 1) and 0xFF
        f = SZ53P[res] or (v ushr 7)
        return res
    }

    private fun srl(v: Int): Int {
        val res = v ushr 1
        f = SZ53P[res] or (v and C)
        return res
    }

    private fun shiftOp(op: Int, v: Int): Int = when (op) {
        0 -> rlc(v)
        1 -> rrc(v)
        2 -> rl(v)
        3 -> rr(v)
        4 -> sla(v)
        5 -> sra(v)
        6 -> sll(v)
        else -> srl(v)
    }

    /** BIT n,r : X and Y from the tested value. */
    private fun bitReg(n: Int, v: Int) {
        val masked = v and (1 shl n)
        f = (f and C) or H or (v and XY) or (if (masked == 0) Z or PV else 0) or (if (n == 7 && masked != 0) S else 0)
    }

    /** BIT n,(HL) / (IX+d) : X and Y from MEMPTR high byte. */
    private fun bitMem(n: Int, v: Int) {
        val masked = v and (1 shl n)
        f = (f and C) or H or ((memptr ushr 8) and XY) or (if (masked == 0) Z or PV else 0) or (if (n == 7 && masked != 0) S else 0)
    }

    // ---- Main decoder ------------------------------------------------------

    private fun execute(opcode: Int) {
        when (opcode ushr 6) {
            1 -> {
                if (opcode == 0x76) {
                    halted = true
                    return
                }
                val dst = (opcode ushr 3) and 7
                val src = opcode and 7
                if (prefix != 0 && (dst == 6 || src == 6)) {
                    // LD r,(IX+d) / LD (IX+d),r : H and L are the real registers.
                    val addr = indexedAddress()
                    if (dst == 6) write8(addr, getRegNoPrefix(src)) else setRegNoPrefix(dst, read8(addr))
                } else {
                    setReg(dst, getReg(src))
                }
            }
            2 -> alu((opcode ushr 3) and 7, getReg(opcode and 7))
            else -> executeMisc(opcode)
        }
    }

    private fun getRegNoPrefix(idx: Int): Int = when (idx) {
        0 -> b
        1 -> c
        2 -> d
        3 -> e
        4 -> h
        5 -> l
        else -> a
    }

    private fun setRegNoPrefix(idx: Int, v: Int) {
        when (idx) {
            0 -> b = v
            1 -> c = v
            2 -> d = v
            3 -> e = v
            4 -> h = v
            5 -> l = v
            else -> a = v
        }
    }

    private fun executeMisc(opcode: Int) {
        when (opcode) {
            0x00 -> Unit
            0x01, 0x11, 0x21, 0x31 -> setPair(opcode ushr 4, fetch16())
            0x02 -> { write8(bc, a); memptr = ((bc + 1) and 0xFF) or (a shl 8) }
            0x12 -> { write8(de, a); memptr = ((de + 1) and 0xFF) or (a shl 8) }
            0x0A -> { a = read8(bc); memptr = (bc + 1) and 0xFFFF }
            0x1A -> { a = read8(de); memptr = (de + 1) and 0xFFFF }
            0x03, 0x13, 0x23, 0x33 -> { internal(2); setPair(opcode ushr 4, getPair(opcode ushr 4) + 1) }
            0x0B, 0x1B, 0x2B, 0x3B -> { internal(2); setPair(opcode ushr 4, getPair(opcode ushr 4) - 1) }
            0x04, 0x0C, 0x14, 0x1C, 0x24, 0x2C, 0x3C -> { val idx = opcode ushr 3; setReg(idx, inc8(getReg(idx))) }
            0x05, 0x0D, 0x15, 0x1D, 0x25, 0x2D, 0x3D -> { val idx = opcode ushr 3; setReg(idx, dec8(getReg(idx))) }
            0x34 -> { val addr = indexedAddress(); val v = read8(addr); internal(1); write8(addr, inc8(v)) }
            0x35 -> { val addr = indexedAddress(); val v = read8(addr); internal(1); write8(addr, dec8(v)) }
            0x06, 0x0E, 0x16, 0x1E, 0x26, 0x2E, 0x3E -> setReg(opcode ushr 3, fetch8())
            0x36 -> {
                if (prefix == 0) {
                    write8(hl, fetch8())
                } else {
                    val dd = fetch8().toByte().toInt()
                    val n = fetch8()
                    internal(2)
                    write8(indexedAddressWith(dd), n)
                }
            }
            0x07 -> { a = ((a shl 1) or (a ushr 7)) and 0xFF; f = (f and (S or Z or PV)) or (a and XY) or (a and C) }
            0x0F -> { val carry = a and C; a = ((a ushr 1) or (a shl 7)) and 0xFF; f = (f and (S or Z or PV)) or (a and XY) or carry }
            0x17 -> { val carry = a ushr 7; a = ((a shl 1) or (f and C)) and 0xFF; f = (f and (S or Z or PV)) or (a and XY) or carry }
            0x1F -> { val carry = a and C; a = ((a ushr 1) or ((f and C) shl 7)) and 0xFF; f = (f and (S or Z or PV)) or (a and XY) or carry }
            0x08 -> { val t = af; af = af2; af2 = t }
            0x09, 0x19, 0x29, 0x39 -> { internal(7); setHLPair(add16(getHLPair(), getPair(opcode ushr 4))) }
            0x10 -> {
                internal(1)
                val dd = fetch8().toByte().toInt()
                b = (b - 1) and 0xFF
                if (b != 0) {
                    internal(5)
                    pc = (pc + dd) and 0xFFFF
                    memptr = pc
                }
            }
            0x18 -> { val dd = fetch8().toByte().toInt(); internal(5); pc = (pc + dd) and 0xFFFF; memptr = pc }
            0x20, 0x28, 0x30, 0x38 -> {
                val dd = fetch8().toByte().toInt()
                if (testCondition((opcode ushr 3) and 3)) {
                    internal(5)
                    pc = (pc + dd) and 0xFFFF
                    memptr = pc
                }
            }
            0x22 -> { val addr = fetch16(); write16(addr, getHLPair()); memptr = (addr + 1) and 0xFFFF }
            0x2A -> { val addr = fetch16(); setHLPair(read16(addr)); memptr = (addr + 1) and 0xFFFF }
            0x32 -> { val addr = fetch16(); write8(addr, a); memptr = ((addr + 1) and 0xFF) or (a shl 8) }
            0x3A -> { val addr = fetch16(); a = read8(addr); memptr = (addr + 1) and 0xFFFF }
            0x27 -> daa()
            0x2F -> { a = a xor 0xFF; f = (f and (S or Z or PV or C)) or H or N or (a and XY) }
            0x37 -> f = (f and (S or Z or PV)) or C or (a and XY)
            0x3F -> f = ((f and (S or Z or PV or C)) xor C) or (if (f and C != 0) H else 0) or (a and XY)
            0xC0, 0xC8, 0xD0, 0xD8, 0xE0, 0xE8, 0xF0, 0xF8 -> {
                internal(1)
                if (testCondition((opcode ushr 3) and 7)) { pc = pop(); memptr = pc }
            }
            0xC1, 0xD1, 0xE1, 0xF1 -> setPairAf((opcode ushr 4) and 3, pop())
            0xC5, 0xD5, 0xE5, 0xF5 -> { internal(1); push(getPairAf((opcode ushr 4) and 3)) }
            0xC2, 0xCA, 0xD2, 0xDA, 0xE2, 0xEA, 0xF2, 0xFA -> {
                val addr = fetch16()
                memptr = addr
                if (testCondition((opcode ushr 3) and 7)) pc = addr
            }
            0xC3 -> { pc = fetch16(); memptr = pc }
            0xC4, 0xCC, 0xD4, 0xDC, 0xE4, 0xEC, 0xF4, 0xFC -> {
                val addr = fetch16()
                memptr = addr
                if (testCondition((opcode ushr 3) and 7)) {
                    internal(1)
                    push(pc)
                    pc = addr
                }
            }
            0xC6, 0xCE, 0xD6, 0xDE, 0xE6, 0xEE, 0xF6, 0xFE -> alu((opcode ushr 3) and 7, fetch8())
            0xC7, 0xCF, 0xD7, 0xDF, 0xE7, 0xEF, 0xF7, 0xFF -> { internal(1); push(pc); pc = opcode and 0x38; memptr = pc }
            0xC9 -> { pc = pop(); memptr = pc }
            0xCB -> executeCb()
            0xCD -> { val addr = fetch16(); internal(1); push(pc); pc = addr; memptr = pc }
            0xD3 -> { val port = fetch8(); ioWrite((a shl 8) or port, a); memptr = ((port + 1) and 0xFF) or (a shl 8) }
            0xDB -> { val port = fetch8(); memptr = (((a shl 8) or port) + 1) and 0xFFFF; a = ioRead((a shl 8) or port) }
            0xD9 -> {
                var t = bc; bc = bc2; bc2 = t
                t = de; de = de2; de2 = t
                t = hl; hl = hl2; hl2 = t
            }
            0xDD -> { prefix = 0xDD; executePrefixed() }
            0xFD -> { prefix = 0xFD; executePrefixed() }
            0xE3 -> {
                val lo = read8(sp)
                val hi = read8(sp + 1)
                internal(1)
                val old = getHLPair()
                write8(sp + 1, old ushr 8)
                write8(sp, old and 0xFF)
                internal(2)
                val v = lo or (hi shl 8)
                setHLPair(v)
                memptr = v
            }
            0xE9 -> pc = getHLPair()
            0xEB -> { val t = de; de = hl; hl = t }
            0xED -> executeEd()
            0xF3 -> { iff1 = false; iff2 = false }
            0xF9 -> { internal(2); sp = getHLPair() }
            0xFB -> { iff1 = true; iff2 = true; eiPending = true }
            else -> Unit
        }
    }

    /** Executes the opcode following a DD/FD prefix. [prefix] has been set by the caller. */
    private fun executePrefixed() {
        while (true) {
            val op = fetchOpcode()
            when (op) {
                // A prefix followed by another prefix acts as a NOP: the last one wins.
                0xDD -> prefix = 0xDD
                0xFD -> prefix = 0xFD
                0xED -> { prefix = 0; executeEd(); return }
                0xCB -> { executeDdCb(); prefix = 0; return }
                else -> { execute(op); prefix = 0; return }
            }
        }
    }

    private fun executeCb() {
        val op = fetchOpcode()
        val reg = op and 7
        val n = (op ushr 3) and 7
        when (op ushr 6) {
            0 -> {
                if (reg == 6) {
                    val v = read8(hl)
                    internal(1)
                    write8(hl, shiftOp(n, v))
                } else {
                    setRegNoPrefix(reg, shiftOp(n, getRegNoPrefix(reg)))
                }
            }
            1 -> {
                if (reg == 6) {
                    val v = read8(hl)
                    internal(1)
                    bitMem(n, v)
                } else {
                    bitReg(n, getRegNoPrefix(reg))
                }
            }
            2 -> {
                if (reg == 6) {
                    val v = read8(hl)
                    internal(1)
                    write8(hl, v and (1 shl n).inv())
                } else {
                    setRegNoPrefix(reg, getRegNoPrefix(reg) and (1 shl n).inv())
                }
            }
            else -> {
                if (reg == 6) {
                    val v = read8(hl)
                    internal(1)
                    write8(hl, v or (1 shl n))
                } else {
                    setRegNoPrefix(reg, getRegNoPrefix(reg) or (1 shl n))
                }
            }
        }
    }

    /** DD CB d op / FD CB d op. */
    private fun executeDdCb() {
        val dd = fetch8().toByte().toInt()
        val op = fetch8()
        internal(2)
        val addr = indexedAddressWith(dd)
        val reg = op and 7
        val n = (op ushr 3) and 7
        val v = read8(addr)
        internal(1)
        when (op ushr 6) {
            0 -> {
                val res = shiftOp(n, v)
                write8(addr, res)
                if (reg != 6) setRegNoPrefix(reg, res)
            }
            1 -> bitMem(n, v)
            2 -> {
                val res = v and (1 shl n).inv()
                write8(addr, res)
                if (reg != 6) setRegNoPrefix(reg, res)
            }
            else -> {
                val res = v or (1 shl n)
                write8(addr, res)
                if (reg != 6) setRegNoPrefix(reg, res)
            }
        }
    }

    private fun executeEd() {
        val op = fetchOpcode()
        when (op) {
            0x40, 0x48, 0x50, 0x58, 0x60, 0x68, 0x78 -> {
                memptr = (bc + 1) and 0xFFFF
                val v = ioRead(bc)
                f = (f and C) or SZ53P[v]
                setRegNoPrefix((op ushr 3) and 7, v)
            }
            0x70 -> {
                memptr = (bc + 1) and 0xFFFF
                val v = ioRead(bc)
                f = (f and C) or SZ53P[v]
            }
            0x41, 0x49, 0x51, 0x59, 0x61, 0x69, 0x79 -> {
                memptr = (bc + 1) and 0xFFFF
                ioWrite(bc, getRegNoPrefix((op ushr 3) and 7))
            }
            0x71 -> { memptr = (bc + 1) and 0xFFFF; ioWrite(bc, 0) }
            0x42, 0x52, 0x62, 0x72 -> { internal(7); sbc16(getPair((op ushr 4) and 3)) }
            0x4A, 0x5A, 0x6A, 0x7A -> { internal(7); adc16(getPair((op ushr 4) and 3)) }
            0x43, 0x53, 0x63, 0x73 -> { val addr = fetch16(); write16(addr, getPair((op ushr 4) and 3)); memptr = (addr + 1) and 0xFFFF }
            0x4B, 0x5B, 0x6B, 0x7B -> { val addr = fetch16(); setPair((op ushr 4) and 3, read16(addr)); memptr = (addr + 1) and 0xFFFF }
            0x44, 0x4C, 0x54, 0x5C, 0x64, 0x6C, 0x74, 0x7C -> { val v = a; a = 0; sub8(v) }
            0x45, 0x55, 0x65, 0x75, 0x4D, 0x5D, 0x6D, 0x7D -> { iff1 = iff2; pc = pop(); memptr = pc }
            0x46, 0x4E, 0x66, 0x6E -> im = 0
            0x56, 0x76 -> im = 1
            0x5E, 0x7E -> im = 2
            0x47 -> { internal(1); i = a }
            0x4F -> { internal(1); r = a }
            0x57 -> { internal(1); a = i; f = (f and C) or SZ53[a] or (if (iff2) PV else 0) }
            0x5F -> { internal(1); a = r and 0xFF; f = (f and C) or SZ53[a] or (if (iff2) PV else 0) }
            0x67 -> { // RRD
                val v = read8(hl)
                internal(4)
                write8(hl, ((a shl 4) or (v ushr 4)) and 0xFF)
                a = (a and 0xF0) or (v and 0x0F)
                f = (f and C) or SZ53P[a]
                memptr = (hl + 1) and 0xFFFF
            }
            0x6F -> { // RLD
                val v = read8(hl)
                internal(4)
                write8(hl, ((v shl 4) or (a and 0x0F)) and 0xFF)
                a = (a and 0xF0) or (v ushr 4)
                f = (f and C) or SZ53P[a]
                memptr = (hl + 1) and 0xFFFF
            }
            0xA0 -> ldi(1)
            0xA8 -> ldi(-1)
            0xB0 -> { ldi(1); if (bc != 0) repeatBlock() }
            0xB8 -> { ldi(-1); if (bc != 0) repeatBlock() }
            0xA1 -> cpi(1)
            0xA9 -> cpi(-1)
            0xB1 -> { cpi(1); if (bc != 0 && f and Z == 0) repeatBlock() }
            0xB9 -> { cpi(-1); if (bc != 0 && f and Z == 0) repeatBlock() }
            0xA2 -> ini(1)
            0xAA -> ini(-1)
            0xB2 -> { ini(1); if (b != 0) repeatBlock() }
            0xBA -> { ini(-1); if (b != 0) repeatBlock() }
            0xA3 -> outi(1)
            0xAB -> outi(-1)
            0xB3 -> { outi(1); if (b != 0) repeatBlock() }
            0xBB -> { outi(-1); if (b != 0) repeatBlock() }
            else -> Unit // NONI: two NOPs
        }
    }

    private fun repeatBlock() {
        internal(5)
        pc = (pc - 2) and 0xFFFF
        memptr = (pc + 1) and 0xFFFF
    }

    private fun ldi(dir: Int) {
        val v = read8(hl)
        write8(de, v)
        internal(2)
        hl = (hl + dir) and 0xFFFF
        de = (de + dir) and 0xFFFF
        bc = (bc - 1) and 0xFFFF
        val n = (v + a) and 0xFF
        f = (f and (S or Z or C)) or (if (bc != 0) PV else 0) or (n and Z80Flags.X) or ((n shl 4) and Z80Flags.Y)
    }

    private fun cpi(dir: Int) {
        val v = read8(hl)
        internal(5)
        val res = (a - v) and 0xFF
        val halfCarry = (a xor v xor res) and H
        hl = (hl + dir) and 0xFFFF
        bc = (bc - 1) and 0xFFFF
        memptr = (memptr + dir) and 0xFFFF
        val n = (res - (halfCarry ushr 4)) and 0xFF
        f = (f and C) or N or (SZ53[res] and (S or Z)) or halfCarry or (if (bc != 0) PV else 0) or (n and Z80Flags.X) or ((n shl 4) and Z80Flags.Y)
    }

    private fun ini(dir: Int) {
        internal(1)
        memptr = (bc + dir) and 0xFFFF
        val v = ioRead(bc)
        write8(hl, v)
        hl = (hl + dir) and 0xFFFF
        b = (b - 1) and 0xFF
        val k = v + ((c + dir) and 0xFF)
        f = SZ53[b] or (if (v and 0x80 != 0) N else 0) or (if (k > 0xFF) H or C else 0) or PARITY[(k and 7) xor b]
    }

    private fun outi(dir: Int) {
        internal(1)
        val v = read8(hl)
        b = (b - 1) and 0xFF
        memptr = (bc + dir) and 0xFFFF
        ioWrite(bc, v)
        hl = (hl + dir) and 0xFFFF
        val k = v + l
        f = SZ53[b] or (if (v and 0x80 != 0) N else 0) or (if (k > 0xFF) H or C else 0) or PARITY[(k and 7) xor b]
    }

    // ---- Save state --------------------------------------------------------

    /** All register/flag state as an int array (for save states). */
    fun exportState(): IntArray = intArrayOf(
        a, f, b, c, d, e, h, l, a2, f2, b2, c2, d2, e2, h2, l2,
        ix, iy, sp, pc, i, r, memptr,
        if (iff1) 1 else 0, if (iff2) 1 else 0, im, if (halted) 1 else 0,
        if (eiPending) 1 else 0, if (intLine) 1 else 0,
    )

    fun importState(s: IntArray) {
        require(s.size >= 29) { "Invalid Z80 state" }
        a = s[0]; f = s[1]; b = s[2]; c = s[3]; d = s[4]; e = s[5]; h = s[6]; l = s[7]
        a2 = s[8]; f2 = s[9]; b2 = s[10]; c2 = s[11]; d2 = s[12]; e2 = s[13]; h2 = s[14]; l2 = s[15]
        ix = s[16]; iy = s[17]; sp = s[18]; pc = s[19]; i = s[20]; r = s[21]; memptr = s[22]
        iff1 = s[23] != 0; iff2 = s[24] != 0; im = s[25]; halted = s[26] != 0
        eiPending = s[27] != 0; intLine = s[28] != 0
        prefix = 0
    }
}

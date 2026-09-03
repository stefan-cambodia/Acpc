package dev.stefan.acpc.core.ppi

import dev.stefan.acpc.core.ay.Ay38912

/**
 * Intel 8255 Programmable Peripheral Interface as wired in the CPC.
 *
 *  - Port A (&F4xx): data bus of the AY-3-8912.
 *  - Port B (&F5xx): inputs — bit 0 VSYNC, bits 1-3 manufacturer id,
 *    bit 4 50/60 Hz jumper, bit 5 /EXP, bit 6 printer busy, bit 7 cassette read.
 *  - Port C (&F6xx): bits 0-3 keyboard line, bit 4 cassette motor,
 *    bit 5 cassette write, bit 6 BC1 and bit 7 BDIR of the AY.
 *  - Control (&F7xx): mode set or single bit set/reset on port C.
 *
 * The keyboard is therefore read exactly like on the real machine: the CPU
 * selects a matrix line through port C, then reads AY register 14 through
 * port A.
 */
class Ppi8255(
    private val psg: Ay38912,
    private val inputs: Inputs,
) {
    /** Signals fed into the PPI by the rest of the machine. */
    interface Inputs {
        val vsync: Boolean
        val manufacturerId: Int
        val cassetteInput: Boolean
        fun onKeyboardLineSelected(line: Int)
        fun onCassetteMotor(on: Boolean)
    }

    var portA = 0
        private set
    var portB = 0
        private set
    var portC = 0
        private set
    var control = 0
        private set

    init {
        reset()
    }

    fun reset() {
        portA = 0; portB = 0; portC = 0
        control = 0x9B // all ports input (8255 reset state)
        inputs.onKeyboardLineSelected(0)
    }

    private val portAInput: Boolean get() = control and 0x10 != 0
    private val portBInput: Boolean get() = control and 0x02 != 0
    private val portCUpperInput: Boolean get() = control and 0x08 != 0
    private val portCLowerInput: Boolean get() = control and 0x01 != 0

    /** Reads one of the four PPI addresses (0 = A, 1 = B, 2 = C, 3 = control). */
    fun read(index: Int): Int = when (index and 3) {
        0 -> if (portAInput) {
            // The AY drives the bus only in "read register" mode (BDIR = 0, BC1 = 1).
            if (portC and 0xC0 == 0x40) psg.readRegister() else 0xFF
        } else {
            portA
        }
        1 -> if (portBInput) readPortB() else portB
        2 -> {
            var v = portC
            if (portCUpperInput) v = v or 0xF0
            if (portCLowerInput) v = v or 0x0F
            v
        }
        else -> 0xFF
    }

    private fun readPortB(): Int {
        var v = 0
        if (inputs.vsync) v = v or 0x01
        v = v or ((inputs.manufacturerId and 7) shl 1)
        v = v or 0x10 // 50 Hz
        v = v or 0x20 // no expansion signal
        v = v or 0x40 // printer busy (no printer)
        if (inputs.cassetteInput) v = v or 0x80
        return v
    }

    fun write(index: Int, value: Int) {
        val v = value and 0xFF
        when (index and 3) {
            0 -> {
                portA = v
                if (!portAInput) updatePsg()
            }
            1 -> portB = v
            2 -> {
                portC = v
                applyPortC()
            }
            else -> {
                if (v and 0x80 != 0) {
                    control = v
                    // A mode-set resets all output latches.
                    portA = 0; portB = 0; portC = 0
                    applyPortC()
                } else {
                    val bit = (v ushr 1) and 7
                    portC = if (v and 1 != 0) portC or (1 shl bit) else portC and (1 shl bit).inv()
                    applyPortC()
                }
            }
        }
    }

    private fun applyPortC() {
        inputs.onKeyboardLineSelected(portC and 0x0F)
        inputs.onCassetteMotor(portC and 0x10 != 0)
        updatePsg()
    }

    private fun updatePsg() {
        when (portC and 0xC0) {
            0xC0 -> psg.selectRegister(portA)
            0x80 -> psg.writeRegister(portA)
            else -> Unit // inactive or read
        }
    }

    fun exportState(): IntArray = intArrayOf(portA, portB, portC, control)

    fun importState(s: IntArray) {
        require(s.size >= 4) { "Invalid PPI state" }
        portA = s[0]; portB = s[1]; portC = s[2]; control = s[3]
        inputs.onKeyboardLineSelected(portC and 0x0F)
    }
}

package dev.stefan.acpc.core.cpu.z80

/**
 * The world as seen by the Z80: memory and I/O ports.
 *
 * The CPU calls these methods at the exact T-state where the bus transaction
 * would happen (see [Z80.cycles]); implementations that need cycle-accurate
 * ordering (CRTC/Gate Array register writes) can synchronise their own clock
 * with the CPU's before applying the access.
 */
interface Z80Bus {
    /** Memory read (also used for opcode fetch). Must return 0..255. */
    fun readMem(address: Int): Int

    fun writeMem(address: Int, value: Int)

    /** I/O read with the full 16-bit port address on the bus. Must return 0..255. */
    fun readIo(port: Int): Int

    fun writeIo(port: Int, value: Int)

    /**
     * Called during the interrupt acknowledge cycle. Returns the value the
     * interrupting device puts on the data bus (0xFF on the CPC: no device
     * drives it, which makes IM 0 behave as RST 38h and IM 2 use vector
     * I:FF).
     */
    fun interruptAcknowledge(): Int = 0xFF
}

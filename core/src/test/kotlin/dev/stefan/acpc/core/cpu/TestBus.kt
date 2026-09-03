package dev.stefan.acpc.core.cpu

import dev.stefan.acpc.core.cpu.z80.Z80Bus

/** Flat 64 KB RAM with recorded I/O, for CPU unit tests. */
class TestBus : Z80Bus {
    val ram = IntArray(0x10000)
    val ioWrites = mutableListOf<Pair<Int, Int>>()
    var ioReadValue = 0xFF
    val ioReads = mutableListOf<Int>()

    override fun readMem(address: Int): Int = ram[address and 0xFFFF]
    override fun writeMem(address: Int, value: Int) { ram[address and 0xFFFF] = value and 0xFF }
    override fun readIo(port: Int): Int { ioReads += port; return ioReadValue }
    override fun writeIo(port: Int, value: Int) { ioWrites += port to value }

    fun load(address: Int, vararg bytes: Int) {
        bytes.forEachIndexed { i, b -> ram[(address + i) and 0xFFFF] = b and 0xFF }
    }
}

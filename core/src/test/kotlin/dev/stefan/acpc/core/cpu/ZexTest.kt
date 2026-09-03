package dev.stefan.acpc.core.cpu

import dev.stefan.acpc.core.cpu.z80.Z80
import dev.stefan.acpc.core.cpu.z80.Z80Bus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs Frank Cringle's Z80 instruction exerciser (zexdoc / zexall) under a
 * minimal CP/M BDOS emulation. Every instruction group is executed with a
 * large set of operand combinations and a CRC of all register/flag results is
 * compared with values recorded on a real Z80.
 *
 * The binaries are not part of the repository: set ACPC_Z80_TEST_DIR (default
 * ~/.acpc/z80tests) to a directory containing zexdoc.com / zexall.com.
 */
@Tag("slow")
class ZexTest {
    private class CpmBus : Z80Bus {
        val ram = IntArray(0x10000)
        override fun readMem(address: Int) = ram[address and 0xFFFF]
        override fun writeMem(address: Int, value: Int) { ram[address and 0xFFFF] = value and 0xFF }
        override fun readIo(port: Int) = 0xFF
        override fun writeIo(port: Int, value: Int) = Unit
    }

    private fun runExerciser(name: String): String {
        val dir = System.getProperty("acpc.z80TestDir") ?: System.getenv("ACPC_Z80_TEST_DIR") ?: (System.getProperty("user.home") + "/.acpc/z80tests")
        val file = File(dir, name)
        assumeTrue(file.exists(), "$name not found in $dir")
        val program = file.readBytes()
        val bus = CpmBus()
        program.forEachIndexed { i, b -> bus.ram[0x100 + i] = b.toInt() and 0xFF }
        bus.ram[0x0000] = 0x76 // HALT at warm boot address: end of test
        bus.ram[0x0005] = 0xC9 // RET at BDOS entry
        val cpu = Z80(bus)
        cpu.reset()
        cpu.alignBusAccesses = false
        cpu.pc = 0x100
        cpu.sp = 0xF000
        val out = StringBuilder()
        val start = System.nanoTime()
        while (true) {
            if (cpu.pc == 0x0005) {
                when (cpu.c) {
                    2 -> out.append(cpu.e.toChar())
                    9 -> {
                        var addr = cpu.de
                        while (bus.ram[addr] != '$'.code) {
                            out.append(bus.ram[addr].toChar())
                            addr = (addr + 1) and 0xFFFF
                        }
                    }
                }
            }
            if (cpu.pc == 0x0000 || cpu.halted) break
            cpu.step()
        }
        val seconds = (System.nanoTime() - start) / 1e9
        println("$name: ${cpu.cycles} T-states in %.1f s (%.0f MHz equivalent)".format(seconds, cpu.cycles / seconds / 1e6))
        println(out)
        return out.toString()
    }

    @Test
    fun zexdoc() {
        val out = runExerciser("zexdoc.com")
        assertTrue(out.contains("Tests complete"), "exerciser did not complete")
        assertFalse(out.contains("ERROR"), "zexdoc reported errors:\n$out")
    }

    @Test
    fun zexall() {
        val out = runExerciser("zexall.com")
        assertTrue(out.contains("Tests complete"), "exerciser did not complete")
        assertFalse(out.contains("ERROR"), "zexall reported errors:\n$out")
    }
}

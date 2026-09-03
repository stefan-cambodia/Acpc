package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.TestRoms
import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Diagnostic tool, not a regression test: loads one tape on a CPC 464 like
 * [TapeIntegrationTest] does and traces what the loader is doing. Does nothing
 * unless `ACPC_TRACE_TAPE` names a `.cdt` file. Run with
 * `./gradlew :core:test --rerun -PslowTests --tests '*TapeTraceTest*'`.
 *
 * Environment variables:
 *  - `ACPC_TRACE_TAPE`: the tape to load (required).
 *  - `ACPC_TRACE_FRAMES`: frames to run after `RUN"` (default 6000 = 120 s).
 *  - `ACPC_TRACE_OUT`: output directory (default /tmp): a screenshot every
 *    500 frames, `hlog.txt`, `bytes.bin`, `dump-<frame>.bin`.
 *  - `ACPC_TRACE_H_PC`: hex PC at which to log the tape position and H, L
 *    (a Speedlock loader compares its pulse counter H at `CP H`).
 *  - `ACPC_TRACE_BYTE_PCS`: comma-separated hex PCs at which to log L as one
 *    received byte, with its tape position in `bytes.times`.
 *  - `ACPC_TRACE_SCAN_FRAMES`: frames at which RAM is scanned for the
 *    Speedlock bit-threshold routine (`LD A,n / CP H / RL L / LD H,n`).
 *  - `ACPC_TRACE_DUMP`: `from:len` in hex, printed at the end, and saved as
 *    a binary file at the frame given by `ACPC_TRACE_DUMP_FRAME`.
 *
 * Every motor relay change is printed with the PC, the last 32 PCs and the
 * stack, the firmware's keyboard scanner (which rewrites port C at every
 * interrupt) included.
 */
@Tag("slow")
class TapeTraceTest {
    private val outDir = File(System.getenv("ACPC_TRACE_OUT") ?: "/tmp")

    @Test
    fun trace() {
        val path = System.getenv("ACPC_TRACE_TAPE") ?: return
        assumeTrue(TestRoms.realAvailable(CpcModel.CPC464))
        val file = File(path)
        val emu = CpcEmulator.createMachine(CpcModel.CPC464, TestRoms.real(CpcModel.CPC464), NullAudioSink())
        val m = emu.machine
        emu.insertTape(file.readBytes(), file.name)
        repeat(130) { emu.runFrame() }
        emu.typeText("|tape\n"); repeat(25) { emu.runFrame() }
        emu.typeText("run\"\n"); repeat(50) { emu.runFrame() }
        emu.typeText(" ")

        val ring = IntArray(32); var ri = 0
        var motor = m.cassetteMotor
        var frames = 0
        val byteLog = java.io.ByteArrayOutputStream()
        val byteTimes = ArrayList<Long>()
        val hPc = (System.getenv("ACPC_TRACE_H_PC") ?: "-1").toInt(16)
        val hLog = StringBuilder()
        val bytePcs = (System.getenv("ACPC_TRACE_BYTE_PCS") ?: "").split(",").filter { it.isNotEmpty() }.map { it.toInt(16) }.toSet()
        m.instructionHook = { mm ->
            val cpu = mm.cpu
            val pos = mm.tape!!.position
            if (cpu.pc == hPc) hLog.append(pos).append(' ').append((cpu.hl ushr 8) and 0xFF).append(' ').append(cpu.hl and 0xFF).append('\n')
            if (cpu.pc in bytePcs) { byteLog.write(cpu.hl and 0xFF); byteTimes += pos }
            if (mm.cassetteMotor != motor) {
                motor = mm.cassetteMotor
                val sb = StringBuilder()
                sb.append("frame $frames motor=$motor pos=${pos / 4_000_000.0}s pc=${"%04X".format(cpu.pc)} sp=${"%04X".format(cpu.sp)} iff1=${cpu.iff1} lowerRom=${mm.memory.lowerRomEnabled} upperRom=${mm.memory.upperRomEnabled}\n")
                sb.append(" last pcs:")
                for (k in 0 until 32) sb.append(" %04X".format(ring[(ri + k) % 32]))
                sb.append("\n stack:")
                for (k in 0 until 12) sb.append(" %04X".format(mm.memory.read(cpu.sp + 2 * k) or (mm.memory.read(cpu.sp + 2 * k + 1) shl 8)))
                println(sb)
            }
            ring[ri] = cpu.pc; ri = (ri + 1) % 32
        }

        val maxFrames = (System.getenv("ACPC_TRACE_FRAMES") ?: "6000").toInt()
        val scanFrames = (System.getenv("ACPC_TRACE_SCAN_FRAMES") ?: "").split(",").toSet()
        val dumpSpec = System.getenv("ACPC_TRACE_DUMP")?.split(":")?.map { it.toInt(16) }
        val hist = HashMap<Int, Int>()
        while (frames < maxFrames) {
            emu.runFrame(); frames++
            if (frames.toString() in scanFrames) scanSpeedlock(m, frames)
            if (dumpSpec != null && frames.toString() == System.getenv("ACPC_TRACE_DUMP_FRAME")) {
                File(outDir, "dump-$frames.bin").writeBytes(ByteArray(dumpSpec[1]) { m.memory.read(dumpSpec[0] + it).toByte() })
            }
            if (frames == maxFrames - 50) m.instructionHook = { mm -> hist.merge(mm.cpu.pc, 1, Int::plus) }
            if (frames % 500 == 0) CompatibilityRunTest.savePng(emu.runFrame(), File(outDir, "trace-$frames.png"))
        }
        m.instructionHook = null

        if (hPc >= 0) File(outDir, "hlog.txt").writeText(hLog.toString())
        if (bytePcs.isNotEmpty()) {
            File(outDir, "bytes.bin").writeBytes(byteLog.toByteArray())
            File(outDir, "bytes.times").writeText(byteTimes.joinToString("\n"))
        }
        println("hot pcs in last second: " + hist.entries.sortedByDescending { it.value }.take(24).joinToString { "%04X=%d".format(it.key, it.value) })
        if (dumpSpec != null) {
            val sb = StringBuilder()
            for (a in dumpSpec[0] until dumpSpec[0] + dumpSpec[1]) {
                if ((a - dumpSpec[0]) % 16 == 0) sb.append("\n%04X: ".format(a))
                sb.append("%02X ".format(m.memory.read(a)))
            }
            println("dump:$sb")
        }
        val cpu = m.cpu
        println("regs: a=%02X bc=%04X de=%04X hl=%04X sp=%04X pc=%04X ix=%04X iy=%04X".format(cpu.a, cpu.bc, cpu.de, cpu.hl, cpu.sp, cpu.pc, cpu.ix, cpu.iy))
        println("iff1=${cpu.iff1} im=${cpu.im} halted=${cpu.halted} lowerRom=${m.memory.lowerRomEnabled} upperRom=${m.memory.upperRomEnabled} mode=${emu.debugInfo().gaMode}")
        println(ScreenReader.readText(m).joinToString("\n"))
    }

    /** Finds the Speedlock bit-threshold routine `LD A,thr / CP H / RL L / LD H,h0` and prints its constants. */
    private fun scanSpeedlock(m: CpcMachine, frames: Int) {
        val pat = intArrayOf(0x3E, -1, 0xBC, 0xCB, 0x15, 0x26, -1)
        val hits = StringBuilder()
        for (a in 0 until 0x10000 - pat.size) {
            if (pat.indices.all { pat[it] < 0 || m.memory.read(a + it) == pat[it] }) {
                hits.append(" %04X:thr=%02X,H0=%02X".format(a, m.memory.read(a + 1), m.memory.read(a + 6)))
            }
        }
        println("frame $frames pos=${"%.1f".format(m.tape!!.position / 4e6)}s speedlock pattern:$hits")
    }
}

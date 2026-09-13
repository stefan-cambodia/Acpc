package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.TestRoms
import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import dev.stefan.acpc.core.disk.AmsdosCatalog
import dev.stefan.acpc.core.disk.DskFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Diagnostic tool, not a regression test: boots one disc on a CPC 6128 like
 * [CompatibilityRunTest] does and records the instructions that led to a
 * chosen event. Does nothing unless `ACPC_TRACE_DISC` names a `.dsk` file.
 * Run with `./gradlew :core:test --rerun -PslowTests --tests '*DiscTraceTest*'`.
 *
 * Environment variables:
 *  - `ACPC_TRACE_DISC`: the disc to boot (required).
 *  - `ACPC_TRACE_CMD`: command typed after boot (default: the auto-start command).
 *  - `ACPC_TRACE_FRAMES`: frames to run after the command (default 3000).
 *  - `ACPC_TRACE_STOP_PCS`: comma-separated hex PCs; the first time one is
 *    reached after `ACPC_TRACE_ARM_FRAME` (default 150) the trace stops and
 *    the last `ACPC_TRACE_RING` (default 4000) instructions are printed with
 *    their registers and the memory configuration. `*` stops at the first
 *    instruction of the arm frame (to see what a stuck program loops on).
 *  - `ACPC_TRACE_JUMPS`: size of a second ring that keeps only control
 *    transfers (an instruction that does not follow the previous one), printed
 *    at the stop as `from -> to`; it reaches much further back than the
 *    instruction ring (default 400).
 *  - `ACPC_TRACE_VIDEO_FRAMES`: comma-separated frame numbers during which every
 *    CRTC and Gate Array write is printed with the CRTC counters (VCC, RLC,
 *    HCC) and the PC.
 *  - `ACPC_TRACE_KEYS`: `second:text` pairs separated by `;`, typed at those
 *    seconds after the command (`\n` for RETURN, `~` for the joystick fire).
 *  - `ACPC_TRACE_DUMP`: `from:len` in hex, memory as mapped at the end of the
 *    run, saved as `dump-<from>.bin`.
 *  - `ACPC_TRACE_FDC`: print every disc controller command and result with
 *    the frame number and the PC.
 *  - `ACPC_TRACE_OUT`: output directory for screenshots (default /tmp).
 *  - `ACPC_TRACE_464`: boot a CPC 464 instead.
 */
@Tag("slow")
class DiscTraceTest {
    private val outDir = File(System.getenv("ACPC_TRACE_OUT") ?: "/tmp")

    @Test
    fun trace() {
        val path = System.getenv("ACPC_TRACE_DISC") ?: return
        val model = if (System.getenv("ACPC_TRACE_464") != null) CpcModel.CPC464 else CpcModel.CPC6128
        assumeTrue(TestRoms.realAvailable(model))
        val file = File(path)
        val emu = CpcEmulator.createMachine(model, TestRoms.real(model), NullAudioSink())
        val m = emu.machine
        val bytes = file.readBytes()
        emu.loadDisk(0, bytes, file.name)
        repeat(130) { emu.runFrame() }
        val command = System.getenv("ACPC_TRACE_CMD")?.replace("\\n", "\n")
            ?: AmsdosCatalog.autoStartCommand(DskFormat.read(bytes, file.name))!!
        emu.typeText(command)

        val ringSize = (System.getenv("ACPC_TRACE_RING") ?: "4000").toInt()
        // Per instruction: pc, a, f, bc, de, hl, ix, sp, iff1, memory configuration.
        val ring = IntArray(ringSize * 10)
        var ri = 0
        var filled = 0
        val stopPcs = (System.getenv("ACPC_TRACE_STOP_PCS") ?: "").split(",").filter { it.isNotEmpty() && it != "*" }.map { it.toInt(16) }.toSet()
        val armFrame = (System.getenv("ACPC_TRACE_ARM_FRAME") ?: "150").toInt()
        val stopAnywhere = System.getenv("ACPC_TRACE_STOP_PCS") == "*"
        var frames = 0
        var stopped = false
        // Row changes and sync edges during ACPC_TRACE_VIDEO_FRAMES, to follow split and "rupture" screens.
        var rowLogging = false
        var lastVcc = -1
        var lastVsync = false
        val jumpSize = (System.getenv("ACPC_TRACE_JUMPS") ?: "400").toInt()
        val jumps = IntArray(jumpSize * 4)
        var ji = 0
        var jumpsFilled = 0
        var prevPc = -1
        m.instructionHook = { mm ->
            val c = mm.cpu
            val delta = c.pc - prevPc
            if (prevPc >= 0 && (delta < 0 || delta > 4)) {
                jumps[ji * 4] = prevPc; jumps[ji * 4 + 1] = c.pc; jumps[ji * 4 + 2] = frames
                jumps[ji * 4 + 3] = (if (mm.memory.lowerRomEnabled) 1 else 0) or (if (mm.memory.upperRomEnabled) 2 else 0)
                ji = (ji + 1) % jumpSize
                if (jumpsFilled < jumpSize) jumpsFilled++
            }
            prevPc = c.pc
            if (rowLogging) {
                val crtc = mm.crtc
                if (crtc.vcc != lastVcc || crtc.vsync != lastVsync) {
                    println("f=$frames vcc=%3d rlc=%2d vsync=%b R4=%d R6=%d R7=%d R9=%d ma=%04X".format(crtc.vcc, crtc.rlc, crtc.vsync, crtc.regs[4], crtc.regs[6], crtc.regs[7], crtc.regs[9], crtc.ma))
                    lastVcc = crtc.vcc; lastVsync = crtc.vsync
                }
            }
            val mem = mm.memory
            val o = ri * 10
            ring[o] = c.pc; ring[o + 1] = c.a; ring[o + 2] = c.f; ring[o + 3] = c.bc; ring[o + 4] = c.de
            ring[o + 5] = c.hl; ring[o + 6] = c.ix; ring[o + 7] = c.sp; ring[o + 8] = if (c.iff1) 1 else 0
            ring[o + 9] = (if (mem.lowerRomEnabled) 0x10000 else 0) or (if (mem.upperRomEnabled) 0x20000 else 0) or (mem.upperRomNumber shl 8) or mem.ramConfig
            ri = (ri + 1) % ringSize
            if (filled < ringSize) filled++
            if (!stopped && frames >= armFrame && (stopAnywhere || c.pc in stopPcs)) {
                stopped = true
                println("stop at frame $frames pc=%04X, last $jumpsFilled control transfers:".format(c.pc))
                val jb = StringBuilder()
                for (k in 0 until jumpsFilled) {
                    val e = ((ji - jumpsFilled + k + jumpSize * 2) % jumpSize) * 4
                    val roms = jumps[e + 3]
                    jb.append("%04X->%04X@%d%s%s ".format(jumps[e], jumps[e + 1], jumps[e + 2], if (roms and 1 != 0) "L" else "", if (roms and 2 != 0) "U" else ""))
                    if (k % 8 == 7) jb.append('\n')
                }
                println(jb)
                println("last $filled instructions:")
                val sb = StringBuilder()
                var lastPc = -1
                var repeats = 0
                for (k in 0 until filled) {
                    val e = ((ri - filled + k + ringSize * 2) % ringSize) * 10
                    if (ring[e] == lastPc) { repeats++; continue }
                    if (repeats > 0) sb.append("   (x$repeats)\n")
                    repeats = 0
                    lastPc = ring[e]
                    val cfg = ring[e + 9]
                    sb.append("%04X a=%02X f=%02X bc=%04X de=%04X hl=%04X ix=%04X sp=%04X i=%d rom=%s%s/%d ram=%02X\n".format(
                        ring[e], ring[e + 1], ring[e + 2], ring[e + 3], ring[e + 4], ring[e + 5], ring[e + 6], ring[e + 7], ring[e + 8],
                        if (cfg and 0x10000 != 0) "L" else "-", if (cfg and 0x20000 != 0) "U" else "-", (cfg ushr 8) and 0xFF, cfg and 0xFF,
                    ))
                }
                println(sb)
            }
        }

        val videoFrames = (System.getenv("ACPC_TRACE_VIDEO_FRAMES") ?: "").split(",").filter { it.isNotEmpty() }.map { it.trim().toInt() }.toSet()
        val keys = (System.getenv("ACPC_TRACE_KEYS") ?: "").split(";").filter { it.contains(':') }
            .associate { it.substringBefore(':').toInt() * 50 to it.substringAfter(':').replace("\\n", "\n") }
        val crtc = m.crtc
        val videoLog = { port: Int, value: Int ->
            val where = "f=$frames vcc=%3d rlc=%2d hcc=%3d pc=%04X".format(crtc.vcc, crtc.rlc, crtc.hcc, m.cpu.pc)
            if (port and 0x8000 == 0) println("$where GA %02X".format(value))
            if (port and 0x4000 == 0) when ((port ushr 8) and 3) {
                0 -> println("$where CRTC select %d".format(value and 0x1F))
                1 -> println("$where CRTC R%d=%d (&%02X)".format(crtc.selectedRegister, value, value))
            }
        }
        if (System.getenv("ACPC_TRACE_FDC") != null) {
            m.fdc.traceListener = { line -> println("f=$frames pc=%04X FDC $line".format(m.cpu.pc)) }
        }
        val maxFrames = (System.getenv("ACPC_TRACE_FRAMES") ?: "3000").toInt()
        while (frames < maxFrames && !stopped) {
            m.ioWriteHook = if (frames in videoFrames) videoLog else null
            rowLogging = frames in videoFrames
            keys[frames]?.let { text ->
                if (text == "~") { emu.setJoystick(0, dev.stefan.acpc.core.joystick.JoystickButton.FIRE1, true) } else emu.typeText(text)
            }
            if (keys.containsKey(frames - 10) && keys[frames - 10] == "~") emu.setJoystick(0, dev.stefan.acpc.core.joystick.JoystickButton.FIRE1, false)
            emu.runFrame(); frames++
            if (frames % 250 == 0) CompatibilityRunTest.savePng(emu.runFrame(), File(outDir, "disctrace-$frames.png"))
        }
        m.instructionHook = null
        m.ioWriteHook = null
        System.getenv("ACPC_TRACE_DUMP")?.split(":")?.map { it.toInt(16) }?.let { (from, len) ->
            File(outDir, "dump-%04X.bin".format(from)).writeBytes(ByteArray(len) { m.memory.read((from + it) and 0xFFFF).toByte() })
        }
        val cpu = m.cpu
        println("frames=$frames regs: a=%02X bc=%04X de=%04X hl=%04X sp=%04X pc=%04X ix=%04X iy=%04X".format(cpu.a, cpu.bc, cpu.de, cpu.hl, cpu.sp, cpu.pc, cpu.ix, cpu.iy))
        println(ScreenReader.readText(m).joinToString("\n"))
    }
}
